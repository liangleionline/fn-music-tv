package com.fnmusic.tv.update

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.net.toUri
import com.fnmusic.tv.BuildConfig
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class UpdateCoordinator(
    private val application: Application,
    private val scope: CoroutineScope,
    private val preferences: UpdatePreferences = UpdatePreferences(application),
    private val client: UpdateManifestSource? = when {
        !BuildConfig.SELF_UPDATE_ENABLED -> null
        BuildConfig.UPDATE_GITHUB_RELEASES_API.isNotBlank() ->
            GitHubReleaseUpdateSource(BuildConfig.UPDATE_GITHUB_RELEASES_API, BuildConfig.VERSION_NAME)
        else -> BuildConfig.UPDATE_MANIFEST_URL.takeIf { it.isNotBlank() }?.let(::UpdateClient)
    },
    private val downloader: UpdateApkDownloader = UpdateDownloader(application),
    private val verifier: UpdateApkVerifier = ApkVerifier(application),
    private val installer: UpdateApkInstaller = UpdateInstaller(application),
    private val canRequestPackageInstalls: () -> Boolean = application::canRequestPackageInstallsCompat,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : UpdateController {
    private val mutableState = MutableStateFlow<UpdateUiState>(if (client == null) UpdateUiState.Disabled else UpdateUiState.Idle)
    override val state: StateFlow<UpdateUiState> = mutableState.asStateFlow()
    private val effectChannel = Channel<UpdateEffect>(Channel.BUFFERED)
    override val effects: Flow<UpdateEffect> = effectChannel.receiveAsFlow()
    override val enabled: Boolean = client != null

    private var visible = false
    private var startupTriggered = false
    private var lastCheckStartedAt: Long? = null
    private var automaticPromptAllowed = true
    private var pendingAutomaticManifest: UpdateManifest? = null
    private var nextAutomaticCheckAt: Long? = null
    private var checkJob: Job? = null
    private var timerJob: Job? = null
    private var downloadJob: Job? = null
    private var manualDemand = false
    private var verifiedApk: File? = null
    private var systemHandoff: SystemHandoff? = null

    init {
        preferences.cleanBelow(BuildConfig.VERSION_CODE.toLong())
        downloader.cleanAll()
    }

    /**
     * 启动时检查一次；之后每次回到前台，只要距上次检查超过
     * [FOREGROUND_CHECK_INTERVAL_MS] 也再检查一次（电视端应用常驻后台，
     * 只靠 12 小时定时器会让「重新打开应用」迟迟看不到新版本）。
     */
    fun onForeground() {
        visible = true
        if (!enabled) return
        val sinceLastCheck = lastCheckStartedAt?.let { clock() - it }
        when {
            !startupTriggered -> {
                startupTriggered = true
                check(UpdateCheckSource.Automatic)
            }
            sinceLastCheck == null || sinceLastCheck >= FOREGROUND_CHECK_INTERVAL_MS ->
                check(UpdateCheckSource.Automatic)
            else -> scheduleTimer()
        }
    }

    fun onResumeFromSystem() {
        val current = mutableState.value
        when (systemHandoff) {
            SystemHandoff.InstallPermission -> {
                if (current !is UpdateUiState.AwaitingInstallPermission) return
                systemHandoff = null
                if (canRequestPackageInstalls()) {
                    prepareInstaller(current.manifest)
                } else {
                    finishInstallError("未允许回声台安装更新，请重新下载后再试", current.manifest)
                }
            }
            SystemHandoff.Installer -> {
                if (current !is UpdateUiState.AwaitingSystemConfirmation) return
                finishInstallError("安装已取消或未完成，请重新下载后再试", current.manifest)
            }
            null -> Unit
        }
    }

    fun onBackground(changingConfigurations: Boolean) {
        if (changingConfigurations) return
        visible = false
        timerJob?.cancel()
        timerJob = null
        if (systemHandoff == null) cancelDownload()
    }

    override fun checkManually() {
        if (!enabled) {
            mutableState.value = UpdateUiState.Error("当前版本不支持应用内更新")
            return
        }
        check(UpdateCheckSource.Manual)
    }

    private fun check(source: UpdateCheckSource) {
        val updateClient = client ?: return
        if (checkJob?.isActive == true) {
            if (source == UpdateCheckSource.Manual) {
                manualDemand = true
                mutableState.value = UpdateUiState.Checking(UpdateCheckSource.Manual)
            }
            return
        }
        if (downloadJob?.isActive == true) return
        lastCheckStartedAt = clock()
        checkJob = scope.launch {
            mutableState.value = UpdateUiState.Checking(source)
            var succeeded = false
            try {
                val manifest = updateClient.fetchManifest()
                succeeded = true
                val effectiveSource = if (source == UpdateCheckSource.Manual || manualDemand) {
                    UpdateCheckSource.Manual
                } else {
                    UpdateCheckSource.Automatic
                }
                manualDemand = false
                nextAutomaticCheckAt = clock() + AUTO_CHECK_INTERVAL_MS
                when {
                    manifest.versionCode <= BuildConfig.VERSION_CODE.toLong() -> {
                        mutableState.value = if (effectiveSource == UpdateCheckSource.Manual) {
                            UpdateUiState.UpToDate(BuildConfig.VERSION_NAME)
                        } else {
                            UpdateUiState.Idle
                        }
                    }
                    effectiveSource == UpdateCheckSource.Manual -> showAvailable(manifest, effectiveSource)
                    preferences.isIgnored(manifest.versionCode) -> mutableState.value = UpdateUiState.Idle
                    automaticPromptAllowed -> showAvailable(manifest, effectiveSource)
                    else -> {
                        pendingAutomaticManifest = manifest
                        mutableState.value = UpdateUiState.Idle
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                val requestedManually = source == UpdateCheckSource.Manual || manualDemand
                manualDemand = false
                if (requestedManually) {
                    mutableState.value = UpdateUiState.Error(error.message ?: "检查更新失败")
                } else {
                    nextAutomaticCheckAt = clock() + AUTO_RETRY_INTERVAL_MS
                    mutableState.value = UpdateUiState.Idle
                }
            } catch (_: Throwable) {
                val requestedManually = source == UpdateCheckSource.Manual || manualDemand
                manualDemand = false
                if (requestedManually) {
                    mutableState.value = UpdateUiState.Error("检查更新失败，请稍后重试")
                } else {
                    nextAutomaticCheckAt = clock() + AUTO_RETRY_INTERVAL_MS
                    mutableState.value = UpdateUiState.Idle
                }
            } finally {
                checkJob = null
                if (!succeeded && nextAutomaticCheckAt == null) {
                    nextAutomaticCheckAt = clock() + AUTO_RETRY_INTERVAL_MS
                }
                scheduleTimer()
            }
        }
    }

    private fun showAvailable(manifest: UpdateManifest, source: UpdateCheckSource) {
        pendingAutomaticManifest = null
        mutableState.value = UpdateUiState.Available(
            manifest = manifest,
            ignored = preferences.isIgnored(manifest.versionCode),
            source = source,
        )
    }

    override fun setAutomaticPromptAllowed(allowed: Boolean) {
        automaticPromptAllowed = allowed
        if (!allowed) {
            val current = mutableState.value as? UpdateUiState.Available
            if (current?.source == UpdateCheckSource.Automatic) {
                pendingAutomaticManifest = current.manifest
                mutableState.value = UpdateUiState.Idle
            }
            return
        }
        if (allowed) {
            pendingAutomaticManifest?.let { manifest ->
                if (!preferences.isIgnored(manifest.versionCode)) showAvailable(manifest, UpdateCheckSource.Automatic)
                else pendingAutomaticManifest = null
            }
        }
    }

    override fun ignoreAvailableVersion() {
        val available = mutableState.value as? UpdateUiState.Available ?: return
        preferences.ignore(available.manifest.versionCode)
        pendingAutomaticManifest = null
        mutableState.value = UpdateUiState.Idle
    }

    override fun dismiss() {
        when (mutableState.value) {
            is UpdateUiState.Downloading, is UpdateUiState.Verifying -> cancelDownload()
            is UpdateUiState.PreparingInstaller, is UpdateUiState.AwaitingSystemConfirmation -> Unit
            else -> {
                verifiedApk?.delete()
                verifiedApk = null
                mutableState.value = if (enabled) UpdateUiState.Idle else UpdateUiState.Disabled
            }
        }
    }

    override fun startDownload() {
        val manifest = when (val current = mutableState.value) {
            is UpdateUiState.Available -> current.manifest
            is UpdateUiState.Error -> current.manifest
            else -> null
        } ?: return
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            try {
                mutableState.value = UpdateUiState.Downloading(manifest, 0)
                val file = downloader.download(manifest) { downloaded ->
                    mutableState.value = UpdateUiState.Downloading(manifest, downloaded)
                }
                mutableState.value = UpdateUiState.Verifying(manifest)
                verifiedApk = verifier.verify(file, manifest)
                if (canRequestPackageInstalls()) {
                    prepareInstaller(manifest)
                } else {
                    mutableState.value = UpdateUiState.AwaitingInstallPermission(manifest)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                downloader.cleanAll()
                verifiedApk?.delete()
                verifiedApk = null
                mutableState.value = UpdateUiState.Error(
                    message = (error as? UpdateFailure)?.message ?: "更新文件处理失败，请重试",
                    manifest = manifest,
                )
            } finally {
                downloadJob = null
            }
        }
    }

    override fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        downloader.cleanAll()
        verifiedApk?.delete()
        verifiedApk = null
        systemHandoff = null
        if (
            mutableState.value is UpdateUiState.Downloading ||
            mutableState.value is UpdateUiState.Verifying ||
            mutableState.value is UpdateUiState.AwaitingInstallPermission
        ) {
            mutableState.value = UpdateUiState.Idle
        }
    }

    override fun openInstallPermissionSettings() {
        if (mutableState.value !is UpdateUiState.AwaitingInstallPermission) return
        systemHandoff = SystemHandoff.InstallPermission
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${application.packageName}".toUri())
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        effectChannel.trySend(
            UpdateEffect.LaunchIntent(intent),
        )
    }

    private fun prepareInstaller(manifest: UpdateManifest) {
        val apk = verifiedApk ?: run {
            mutableState.value = UpdateUiState.Error("更新文件已失效，请重新下载", manifest)
            return
        }
        mutableState.value = UpdateUiState.PreparingInstaller(manifest)
        scope.launch {
            try {
                systemHandoff = SystemHandoff.Installer
                mutableState.value = UpdateUiState.AwaitingSystemConfirmation(manifest)
                installer.install(apk)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                finishInstallError("无法打开系统安装程序，请重新下载后再试", manifest)
            }
        }
    }

    fun handleIntentLaunchFailure() {
        val current = mutableState.value
        val manifest = when (current) {
            is UpdateUiState.AwaitingInstallPermission -> current.manifest
            is UpdateUiState.AwaitingSystemConfirmation -> current.manifest
            else -> return
        }
        systemHandoff = null
        finishInstallError("无法打开系统设置页，请重新下载后再试", manifest)
    }

    private fun finishInstallError(message: String, manifest: UpdateManifest) {
        verifiedApk?.delete()
        verifiedApk = null
        systemHandoff = null
        mutableState.value = UpdateUiState.Error(message, manifest)
    }

    private fun scheduleTimer() {
        timerJob?.cancel()
        timerJob = null
        if (!visible || !enabled) return
        val dueAt = nextAutomaticCheckAt ?: return
        timerJob = scope.launch {
            val wait = (dueAt - clock()).coerceAtLeast(0)
            delay(wait)
            if (visible && isActive) check(UpdateCheckSource.Automatic)
        }
    }

    suspend fun shutdownForExit() {
        timerJob?.cancel()
        checkJob?.cancel()
        downloadJob?.cancel()
        downloader.cleanAll()
        effectChannel.close()
    }

    private companion object {
        const val AUTO_CHECK_INTERVAL_MS = 12L * 60L * 60L * 1_000L
        const val AUTO_RETRY_INTERVAL_MS = 30L * 60L * 1_000L
        /** 回到前台时距上次检查不足该间隔则不重复请求（GitHub API 未认证只有 60 次/小时）。 */
        const val FOREGROUND_CHECK_INTERVAL_MS = 30L * 60L * 1_000L
    }

    private enum class SystemHandoff { InstallPermission, Installer }
}

private fun Application.canRequestPackageInstallsCompat(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        packageManager.canRequestPackageInstalls()
    } else {
        true
    }

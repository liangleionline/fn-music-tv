package com.fnmusic.tv.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 本仓库的更新元数据文件名。每个 GitHub Release 都必须同时上传它，
 * 内容与 [UpdateManifestDto] 一致；客户端从 `releases/latest` 的资产列表中读取。
 */
internal const val GITHUB_UPDATE_INFO_ASSET = "update.json"
private const val MAX_GITHUB_RELEASE_BYTES = 512 * 1024

@Serializable
internal data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    val body: String = "",
    @SerialName("published_at") val publishedAt: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAssetDto> = emptyList(),
)

@Serializable
internal data class GitHubAssetDto(
    val name: String = "",
    val size: Long = 0,
    @SerialName("browser_download_url") val downloadUrl: String = "",
)

/**
 * 从 GitHub `releases/latest`（本仓库）读取更新信息：
 * 1. 调 GitHub API 拿最新 Release（draft / prerelease 不会出现在 latest 中）；
 * 2. 从资产列表里找到 `update.json`，下载并解析为既有的更新清单；
 * 3. 复用 [validateManifest] 做包名 / 版本 / 大小 / SHA256 / 域名校验；
 * 4. 展示文案取 Release 的 name 与 body（body 的 Markdown 会被轻度清洗）。
 */
internal class GitHubReleaseUpdateSource(
    private val apiUrl: String,
    private val versionName: String,
    private val client: OkHttpClient = defaultUpdateNetworkClient(),
) : UpdateManifestSource {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    override suspend fun fetchManifest(): UpdateManifest {
        validateHttpsUri(apiUrl, "更新检查地址")
        val request = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "${UPDATE_USER_AGENT_PREFIX}/$versionName")
            .header("Cache-Control", "no-cache")
            .build()
        val release = executeFollowingUpdateRedirects(client, request).use { response ->
            when {
                response.code == 403 || response.code == 429 ->
                    throw UpdateFailure("检查更新太频繁，请稍后再试")
                response.code == 404 -> throw UpdateFailure("仓库还没有可用的版本")
                !response.isSuccessful -> throw UpdateFailure("检查更新失败（${response.code}）")
            }
            val bytes = response.readLimitedBody(MAX_GITHUB_RELEASE_BYTES, "更新信息过大")
            try {
                json.decodeFromString<GitHubReleaseDto>(bytes.decodeToString())
            } catch (_: SerializationException) {
                throw UpdateFailure("更新信息格式无效")
            } catch (_: IllegalArgumentException) {
                throw UpdateFailure("更新信息格式无效")
            }
        }
        if (release.draft || release.prerelease) throw UpdateFailure("暂无可用更新")
        val tag = release.tagName.trim()
        if (tag.isBlank()) throw UpdateFailure("更新信息缺少版本号")
        val infoAsset = release.assets.firstOrNull { it.name.equals(GITHUB_UPDATE_INFO_ASSET, ignoreCase = true) }
            ?: throw UpdateFailure("最新版本缺少 $GITHUB_UPDATE_INFO_ASSET，请等待发布者补齐")
        if (infoAsset.size <= 0 || infoAsset.size > MAX_UPDATE_MANIFEST_BYTES) {
            throw UpdateFailure("更新信息文件大小无效")
        }
        val infoUri = validateHttpsUri(infoAsset.downloadUrl, "更新信息地址")
        val infoRequest = Request.Builder()
            .url(infoAsset.downloadUrl)
            .header("User-Agent", "${UPDATE_USER_AGENT_PREFIX}/$versionName")
            .header("Cache-Control", "no-cache")
            .build()
        val dto = executeFollowingUpdateRedirects(client, infoRequest).use { response ->
            if (!response.isSuccessful) throw UpdateFailure("读取更新信息失败（${response.code}）")
            val bytes = response.readLimitedBody(MAX_UPDATE_MANIFEST_BYTES, "更新信息过大")
            try {
                json.decodeFromString<UpdateManifestDto>(bytes.decodeToString())
            } catch (_: SerializationException) {
                throw UpdateFailure("更新信息格式无效")
            } catch (_: IllegalArgumentException) {
                throw UpdateFailure("更新信息格式无效")
            }
        }
        val expectedVersion = tag.removePrefix("v").removePrefix("V")
        if (dto.versionName.trim() != expectedVersion) {
            throw UpdateFailure("更新信息版本号与 Release 不一致")
        }
        val manifest = validateManifest(dto, infoUri)
        return manifest.copy(
            title = releaseTitleOf(release, manifest.title),
            notes = releaseNotesOf(release.body, manifest.notes),
        )
    }
}

private const val UPDATE_USER_AGENT_PREFIX = "fn-music-tv"

private fun releaseTitleOf(release: GitHubReleaseDto, fallback: String): String {
    val candidate = release.name.trim().ifBlank { fallback }.replace('\n', ' ')
    return candidate.take(120)
}

private fun releaseNotesOf(body: String, fallback: String): String {
    val cleaned = sanitizeReleaseNotes(body)
    val notes = cleaned.ifBlank { fallback }
    return notes.take(MAX_UPDATE_NOTES_LENGTH)
}

/**
 * 把 GitHub Release 的 Markdown 正文压成弹窗能直接读的纯文本段落：
 * 去掉图片、链接语法、粗体/代码标记与分隔线，保留 `#` 标题与 `-` 列表（弹窗已有专门排版）。
 */
internal fun sanitizeReleaseNotes(markdown: String): String = markdown
    .replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), "")
    .replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
    .replace(Regex("`{1,3}"), "")
    .replace(Regex("(\\*\\*|__|~~)"), "")
    .replace(Regex("<[^>]+>"), "")
    .lineSequence()
    .map { it.replace(Regex("^>\\s*"), "").trimEnd() }
    .filterNot { it.trim().matches(Regex("^(-{3,}|\\*{3,}|_{3,}|\\+{3,})$")) }
    .joinToString("\n")
    .replace(Regex("\\n{3,}"), "\n\n")
    .trim()

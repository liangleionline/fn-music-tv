package com.fnmusic.tv.update

import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * GitHub 直链下载会经过若干次 302：github.com → objects.githubusercontent.com /
 * release-assets.githubusercontent.com。因此这里统一用「手动跟随 + 主机白名单」的方式，
 * 保证每一跳仍然必须是 HTTPS，且只落在受信任的 GitHub 域上。
 */
internal val UPDATE_GITHUB_HOSTS = setOf(
    "github.com",
    "www.github.com",
    "api.github.com",
    "objects.githubusercontent.com",
    "release-assets.githubusercontent.com",
)

internal const val MAX_UPDATE_REDIRECT_HOPS = 5

internal fun defaultUpdateNetworkClient(): OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
    .build()

/**
 * 执行请求并手动跟随重定向。每一跳都要求 HTTPS，且主机必须属于 [UPDATE_GITHUB_HOSTS]
 * 或与当前地址同主机（避免跨站跳转）。
 *
 * @param onCall 每次新建 Call 时回调，便于调用方在协程取消时 cancel 当前这一跳。
 */
internal suspend fun executeFollowingUpdateRedirects(
    client: OkHttpClient,
    request: Request,
    onCall: (Call) -> Unit = {},
): Response {
    var current = request
    var hops = 0
    while (true) {
        val call = client.newCall(current)
        onCall(call)
        val response = call.await()
        val location = response.header("Location")
        if (response.code !in 300..399 || location.isNullOrBlank()) {
            if (response.code in 300..399) {
                response.close()
                throw UpdateFailure("下载地址重定向无效")
            }
            return response
        }
        response.close()
        if (hops >= MAX_UPDATE_REDIRECT_HOPS) throw UpdateFailure("下载地址重定向次数过多")
        val next = resolveUpdateRedirect(current.url.toString(), location)
        current = current.newBuilder().url(next.toString()).build()
        hops++
    }
}

internal fun resolveUpdateRedirect(baseUrl: String, location: String): URI {
    val base = runCatching { URI(baseUrl) }.getOrNull()
        ?: throw UpdateFailure("下载地址无效")
    val target = runCatching { base.resolve(location.trim()) }.getOrNull()
        ?: throw UpdateFailure("下载地址重定向无效")
    if (!target.scheme.equals("https", ignoreCase = true) || target.host.isNullOrBlank()) {
        throw UpdateFailure("下载地址必须使用 HTTPS")
    }
    if (target.userInfo != null || target.fragment != null) throw UpdateFailure("下载地址重定向无效")
    val host = target.host.lowercase()
    if (host !in UPDATE_GITHUB_HOSTS && !host.equals(base.host?.lowercase(), ignoreCase = true)) {
        throw UpdateFailure("下载地址发生了不受信任的跳转")
    }
    return target
}

/** 读取响应体，超过 [limit] 字节即中止，避免异常巨大的响应拖垮低端电视。 */
internal fun Response.readLimitedBody(limit: Int, tooLargeMessage: String): ByteArray {
    val contentLength = body.contentLength()
    if (contentLength > limit) throw UpdateFailure(tooLargeMessage)
    val output = java.io.ByteArrayOutputStream()
    body.byteStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            if (output.size() > limit) throw UpdateFailure(tooLargeMessage)
        }
    }
    return output.toByteArray()
}

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
}

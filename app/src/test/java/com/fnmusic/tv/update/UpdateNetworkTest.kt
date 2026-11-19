package com.fnmusic.tv.update

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class UpdateNetworkTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before fun setUp() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory())
        server.start()
        client = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            // 与生产环境的 defaultUpdateNetworkClient 保持一致：重定向由 executeFollowingUpdateRedirects 手动处理
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    @After fun tearDown() {
        server.close()
    }

    @Test fun `follows a same-host redirect and returns the final response`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", server.url("/release-assets/app.apk").toString())
                .build(),
        )
        server.enqueue(MockResponse.Builder().body("apk-bytes").build())

        val request = Request.Builder().url(server.url("/download/app.apk").toString()).build()
        val response = executeFollowingUpdateRedirects(client, request)

        response.use {
            assertEquals(200, it.code)
            assertEquals("apk-bytes", it.body.string())
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun `accepts the github object storage host used by release assets`() {
        val target = resolveUpdateRedirect(
            "https://github.com/liangleionline/fn-music-tv/releases/download/v1.2.15/app.apk",
            "https://objects.githubusercontent.com/github-production-release-asset/123?X-Amz-Signature=abc",
        )
        assertEquals("objects.githubusercontent.com", target.host)
    }

    @Test fun `rejects redirects to unrelated hosts`() {
        assertThrows(UpdateFailure::class.java) {
            resolveUpdateRedirect("https://github.com/liangleionline/fn-music-tv", "https://evil.example.com/app.apk")
        }
        Unit
    }

    @Test fun `rejects plain http redirects`() {
        assertThrows(UpdateFailure::class.java) {
            resolveUpdateRedirect("https://github.com/liangleionline/fn-music-tv", "http://github.com/app.apk")
        }
        Unit
    }

    @Test fun `rejects redirects with embedded credentials`() {
        assertThrows(UpdateFailure::class.java) {
            resolveUpdateRedirect("https://github.com/a/b", "https://user:pass@github.com/app.apk")
        }
        Unit
    }

    @Test fun `gives up after too many redirects`() = runBlocking {
        repeat(MAX_UPDATE_REDIRECT_HOPS + 1) {
            server.enqueue(
                MockResponse.Builder()
                    .code(302)
                    .addHeader("Location", server.url("/loop.apk").toString())
                    .build(),
            )
        }
        val request = Request.Builder().url(server.url("/loop.apk").toString()).build()

        assertThrows(UpdateFailure::class.java) {
            runBlocking { executeFollowingUpdateRedirects(client, request) }
        }
        Unit
    }
}

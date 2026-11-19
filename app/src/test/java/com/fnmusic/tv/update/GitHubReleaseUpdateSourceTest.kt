package com.fnmusic.tv.update

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GitHubReleaseUpdateSourceTest {
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
            .build()
    }

    @After fun tearDown() {
        server.close()
    }

    @Test fun `reads the newest release from the GitHub api and prefers the release body`() = runBlocking {
        val infoUrl = server.url("/download/v1.2.15/update.json").toString()
        val apkUrl = server.url("/download/v1.2.15/app.apk").toString()
        server.enqueue(
            MockResponse.Builder().body(
                """
                {
                  "tag_name": "v1.2.15",
                  "name": "飞牛音乐 TV 增强版 v1.2.15",
                  "body": "## 新增\\n- **设置页检查更新** 改为读取本仓库最新 Release",
                  "published_at": "2026-11-19T02:00:00Z",
                  "draft": false,
                  "prerelease": false,
                  "assets": [
                    { "name": "update.json", "size": ${updateInfo(apkUrl).length}, "browser_download_url": "$infoUrl" },
                    { "name": "app.apk", "size": 1024, "browser_download_url": "$apkUrl" }
                  ]
                }
                """.trimIndent(),
            ).build(),
        )
        server.enqueue(MockResponse.Builder().body(updateInfo(apkUrl)).build())

        val manifest = GitHubReleaseUpdateSource(server.url("/repos/o/r/releases/latest").toString(), "1.2.14", client)
            .fetchManifest()

        assertEquals("1.2.15", manifest.versionName)
        assertEquals(49L, manifest.versionCode)
        assertEquals(apkUrl, manifest.apkUrl)
        assertEquals("飞牛音乐 TV 增强版 v1.2.15", manifest.title)
        assertTrue(manifest.notes.contains("## 新增"))
        assertTrue(manifest.notes.contains("设置页检查更新"))
        assertTrue(!manifest.notes.contains("**"))
        val apiRequest = server.takeRequest()
        assertTrue(apiRequest.headers["User-Agent"].orEmpty().startsWith("fn-music-tv/1.2.14"))
        assertEquals("application/vnd.github+json", apiRequest.headers["Accept"])
    }

    @Test fun `rejects a release whose tagged version differs from the update info`() = runBlocking {
        val infoUrl = server.url("/download/v1.2.15/update.json").toString()
        val apkUrl = server.url("/download/v1.2.15/app.apk").toString()
        server.enqueue(
            MockResponse.Builder().body(
                """
                {
                  "tag_name": "v1.2.16",
                  "name": "v1.2.16",
                  "body": "",
                  "published_at": "2026-11-19T02:00:00Z",
                  "assets": [
                    { "name": "update.json", "size": ${updateInfo(apkUrl).length}, "browser_download_url": "$infoUrl" }
                  ]
                }
                """.trimIndent(),
            ).build(),
        )
        server.enqueue(MockResponse.Builder().body(updateInfo(apkUrl)).build())

        assertThrows(UpdateFailure::class.java) {
            runBlocking {
                GitHubReleaseUpdateSource(server.url("/repos/o/r/releases/latest").toString(), "1.2.14", client)
                    .fetchManifest()
            }
        }
        Unit
    }

    @Test fun `fails clearly when the release has no update info asset`() = runBlocking {
        server.enqueue(
            MockResponse.Builder().body(
                """
                {
                  "tag_name": "v1.2.15",
                  "name": "v1.2.15",
                  "body": "",
                  "published_at": "2026-11-19T02:00:00Z",
                  "assets": [
                    { "name": "app.apk", "size": 1024, "browser_download_url": "https://github.com/o/r/app.apk" }
                  ]
                }
                """.trimIndent(),
            ).build(),
        )

        val error = assertThrows(UpdateFailure::class.java) {
            runBlocking {
                GitHubReleaseUpdateSource(server.url("/repos/o/r/releases/latest").toString(), "1.2.14", client)
                    .fetchManifest()
            }
        }
        assertTrue(error.message.orEmpty().contains("update.json"))
        Unit
    }

    @Test fun `reports rate limiting without treating it as a crash`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(403).body("{\"message\":\"rate limit\"}").build())

        val error = assertThrows(UpdateFailure::class.java) {
            runBlocking {
                GitHubReleaseUpdateSource(server.url("/repos/o/r/releases/latest").toString(), "1.2.14", client)
                    .fetchManifest()
            }
        }
        assertTrue(error.message.orEmpty().contains("频繁"))
        Unit
    }

    @Test fun `sanitizes markdown so the dialog shows readable text`() {
        val notes = sanitizeReleaseNotes(
            """
            ## 修复
            - **同一专辑** 不再拆卡，见 [说明](https://example.com/a)
            - 使用 `AudioTagReader` 兜底

            ---

            > 覆盖安装即可
            """.trimIndent(),
        )

        assertTrue(!notes.contains("**"))
        assertTrue(!notes.contains("`"))
        assertTrue(!notes.contains("](http"))
        assertTrue(!notes.contains("---"))
        assertEquals(
            listOf(
                com.fnmusic.tv.ui.UpdateNoteBlock.Heading("修复"),
                com.fnmusic.tv.ui.UpdateNoteBlock.Item("同一专辑 不再拆卡，见 说明"),
                com.fnmusic.tv.ui.UpdateNoteBlock.Item("使用 AudioTagReader 兜底"),
                com.fnmusic.tv.ui.UpdateNoteBlock.Paragraph("覆盖安装即可"),
            ),
            com.fnmusic.tv.ui.parseUpdateNotes(notes),
        )
    }

    private fun updateInfo(apkUrl: String) = """
        {
          "schemaVersion": 1,
          "packageName": "com.fnmusic.tv",
          "versionName": "1.2.15",
          "versionCode": 49,
          "title": "v1.2.15",
          "notes": "更新内容",
          "apk": { "url": "$apkUrl", "size": 1024, "sha256": "${"b".repeat(64)}" },
          "publishedAt": "2026-11-19T02:00:00Z",
          "githubReleaseUrl": "https://github.com/liangleionline/fn-music-tv/releases/tag/v1.2.15"
        }
    """.trimIndent()
}

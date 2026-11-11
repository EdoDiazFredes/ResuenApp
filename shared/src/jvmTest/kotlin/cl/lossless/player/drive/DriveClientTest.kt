package cl.lossless.player.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DriveClientTest {

    private class FakeTokens : DriveTokenSource {
        var issued = 0
        val invalidated = mutableListOf<String>()
        override fun accessToken() = "t${++issued}"
        override fun invalidate(token: String) { invalidated += token }
    }

    private fun page(vararg files: String, next: String? = null) =
        """{${next?.let { "\"nextPageToken\":\"$it\"," } ?: ""}"files":[${files.joinToString(",")}]}"""

    @Test
    fun listsFoldersAndAudioOnlyInNaturalOrder() {
        val client = DriveClient(FakeTokens(), "test", transport = { _, _ ->
            DriveClient.Response(200, page(
                """{"id":"a","name":"10 Final.flac","mimeType":"audio/flac","size":"100","md5Checksum":"abc"}""",
                """{"id":"b","name":"2 Inicio.flac","mimeType":"audio/flac","size":"50"}""",
                """{"id":"c","name":"cover.jpg","mimeType":"image/jpeg"}""",
                """{"id":"d","name":"Discos","mimeType":"application/vnd.google-apps.folder"}""",
                """{"id":"e","name":"Notas","mimeType":"application/vnd.google-apps.document"}""",
                """{"id":"f","name":"Atajo","mimeType":"application/vnd.google-apps.shortcut","shortcutDetails":{"targetId":"g","targetMimeType":"application/vnd.google-apps.folder"}}""",
            ))
        })
        val items = client.list(DriveClient.ROOT, setOf("flac"))
        assertEquals(listOf("Atajo", "Discos", "2 Inicio.flac", "10 Final.flac"), items.map { it.name })
        assertEquals("g", items[0].id)
        assertTrue(items[0].isFolder)
        assertEquals(100L, items[3].sizeBytes)
    }

    @Test
    fun followsPages() {
        val urls = mutableListOf<String>()
        val client = DriveClient(FakeTokens(), "test", transport = { url, _ ->
            urls += url
            if ("pageToken" in url) DriveClient.Response(200, page("""{"id":"2","name":"b.mp3","mimeType":"audio/mpeg"}"""))
            else DriveClient.Response(200, page("""{"id":"1","name":"a.mp3","mimeType":"audio/mpeg"}""", next = "p2"))
        })
        assertEquals(listOf("a.mp3", "b.mp3"), client.list("x", setOf("mp3")).map { it.name })
        assertEquals(2, urls.size)
    }

    @Test
    fun retriesOnceWithNewTokenAfter401() {
        val tokens = FakeTokens()
        val seen = mutableListOf<String?>()
        val client = DriveClient(tokens, "test", transport = { _, headers ->
            seen += headers["Authorization"]
            if (headers["Authorization"] == "Bearer t1") DriveClient.Response(401, "{}")
            else DriveClient.Response(200, page())
        })
        client.list(DriveClient.ROOT, setOf("flac"))
        assertEquals(listOf("Bearer t1", "Bearer t2"), seen)
        assertEquals(listOf("t1"), tokens.invalidated)
    }

    @Test(expected = DriveAuthException::class)
    fun failsWithAuthErrorWhenSecondTokenAlsoRejected() {
        DriveClient(FakeTokens(), "test", transport = { _, _ -> DriveClient.Response(401, "{}") })
            .list(DriveClient.ROOT, setOf("flac"))
    }

    @Test
    fun sharedWithMeUsesItsOwnQuery() {
        var url = ""
        DriveClient(FakeTokens(), "test", transport = { u, _ -> url = u; DriveClient.Response(200, page()) })
            .list(DriveClient.SHARED, setOf("flac"))
        assertTrue(url.contains("sharedWithMe"))
    }

    @Test
    fun cacheNamesChangeWithVersionAndTrimOldest() {
        val dir = Files.createTempDirectory("drive").toFile()
        try {
            val cache = DriveCache(dir, maxBytes = 15)
            val a = DriveItem("id1", "a.flac", false, sizeBytes = 10, md5 = "aaa")
            val a2 = a.copy(md5 = "bbb")
            assertTrue(cache.fileFor(a) != cache.fileFor(a2))
            assertNull(cache.cached(a))
            val fa = cache.fileFor(a).apply { writeBytes(ByteArray(10)); setLastModified(1_000) }
            assertEquals(fa, cache.cached(a))
            val b = DriveItem("id2", "b.flac", false, sizeBytes = 10, md5 = "ccc")
            val fb = cache.fileFor(b).apply { writeBytes(ByteArray(10)) }
            cache.trim(keep = setOf(fb))
            assertTrue(!fa.exists() && fb.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun parsesTokensWithMargin() {
        val t = GoogleOAuth.parseTokens("""{"access_token":"x","expires_in":3600,"refresh_token":"r"}""", now = 0)
        assertEquals("x", t.accessToken)
        assertEquals(3_540_000L, t.expiresAt)
        assertEquals("r", t.refreshToken)
        assertNull(GoogleOAuth.parseTokens("""{"access_token":"x"}""").refreshToken)
    }

    @Test
    fun pkceChallengeMatchesRfcExample() {
        // RFC 7636, apéndice B.
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            GoogleOAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

}

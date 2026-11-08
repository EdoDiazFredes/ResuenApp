package cl.lossless.player.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CancellationException

class UpdateCheckerTest {

    private val releases = """
        [
          {"tag_name": "exp-1.2.60", "draft": false, "body": "Mini reproductor",
           "assets": [
             {"name": "ResuenApp-1.2.60-Setup.exe", "browser_download_url": "https://x/setup60.exe", "size": 10},
             {"name": "ResuenApp-exp-1.2.60.apk", "browser_download_url": "https://x/60.apk", "size": 20}
           ]},
          {"tag_name": "exp-1.2.61", "draft": true, "body": "",
           "assets": [{"name": "ResuenApp-exp-1.2.61.apk", "browser_download_url": "https://x/61.apk", "size": 20}]},
          {"tag_name": "exp-1.2.59", "draft": false, "body": "",
           "assets": [{"name": "ResuenApp-exp-1.2.59.apk", "browser_download_url": "https://x/59.apk", "size": 20}]},
          {"tag_name": "v1.3.0", "draft": false, "body": "",
           "assets": [{"name": "ResuenApp-1.3.0.apk", "browser_download_url": "https://x/s.apk", "size": 20}]},
          {"tag_name": "exp-1.2.100", "draft": false, "body": "",
           "assets": [{"name": "ResuenApp-1.2.100-macOS-intel.dmg", "browser_download_url": "https://x/i.dmg", "size": 30}]}
        ]
    """.trimIndent()

    private val checker = UpdateChecker("test", fetch = { releases })

    @Test
    fun `elige la versión más nueva del canal que trae el archivo pedido`() {
        val update = checker.check("1.2.55-exp", "exp") { it.endsWith(".apk") }
        assertEquals("1.2.60", update?.version)
        assertEquals("https://x/60.apk", update?.assetUrl)
        assertEquals("Mini reproductor", update?.notes)
    }

    @Test
    fun `ignora borradores, otros canales y versiones sin archivo para este equipo`() {
        val update = checker.check("1.2.55", "exp") { it.endsWith("-Setup.exe") }
        assertEquals("1.2.60", update?.version)
        val mac = checker.check("1.2.55", "exp") { it.endsWith("macOS-intel.dmg") }
        assertEquals("1.2.100", mac?.version)
    }

    @Test
    fun `al día no devuelve nada`() {
        assertNull(checker.check("1.2.60", "exp") { it.endsWith(".apk") })
    }

    @Test
    fun `compara número por número`() {
        assertTrue(UpdateChecker.compareVersions("1.2.100", "1.2.99") > 0)
        assertTrue(UpdateChecker.compareVersions("1.2.9-exp", "1.2.10") < 0)
        assertEquals(0, UpdateChecker.compareVersions("1.2", "1.2.0"))
    }

    // --- Descarga reanudable --------------------------------------------------------------

    private val payload = ByteArray(300_000) { (it % 251).toByte() }

    /** Servidor local que respeta Range; con [cutFirst] el primer pedido se corta a la mitad. */
    private fun serve(cutFirst: Boolean = false, block: (url: String, ranges: MutableList<String?>) -> Unit) {
        val ranges = mutableListOf<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/app.apk") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            ranges += range
            val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
            val body = payload.copyOfRange(from, payload.size)
            val cut = cutFirst && ranges.size == 1
            ex.sendResponseHeaders(if (range != null) 206 else 200, body.size.toLong())
            try {
                ex.responseBody.write(body, 0, if (cut) body.size / 2 else body.size)
            } finally {
                ex.close()
            }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}/app.apk", ranges)
        } finally {
            server.stop(0)
        }
    }

    private fun update(url: String) = AvailableUpdate("1.2.99", "", "app.apk", url, payload.size.toLong())

    @Test
    fun `continúa una descarga cortada pidiendo solo lo que falta`() = serve { url, ranges ->
        val dir = Files.createTempDirectory("upd").toFile()
        val target = File(dir, "app.apk")
        File(dir, "app.apk.part").writeBytes(payload.copyOfRange(0, 100_000))
        checker.download(update(url), target) {}
        assertEquals(listOf<String?>("bytes=100000-"), ranges)
        assertTrue(payload.contentEquals(target.readBytes()))
        assertTrue(!File(dir, "app.apk.part").exists())
    }

    @Test
    fun `si la conexión se corta reintenta desde donde quedó`() = serve(cutFirst = true) { url, ranges ->
        val dir = Files.createTempDirectory("upd").toFile()
        val target = File(dir, "app.apk")
        checker.download(update(url), target, retryDelayMs = 10) {}
        assertEquals(2, ranges.size)
        assertNull(ranges[0])
        assertTrue(ranges[1]!!.startsWith("bytes="))
        assertTrue(payload.contentEquals(target.readBytes()))
    }

    @Test
    fun `al cancelar deja el archivo parcial para reanudar`() = serve { url, _ ->
        val dir = Files.createTempDirectory("upd").toFile()
        val target = File(dir, "app.apk")
        var progress = 0f
        try {
            checker.download(update(url), target, isActive = { progress < 0.2f }) { progress = it }
            throw AssertionError("debió cancelarse")
        } catch (e: CancellationException) {
        }
        assertTrue(!target.exists())
        assertTrue(File(dir, "app.apk.part").length() in 1 until payload.size)
    }

    @Test
    fun `si ya estaba descargado no vuelve a pedirlo`() = serve { url, ranges ->
        val dir = Files.createTempDirectory("upd").toFile()
        val target = File(dir, "app.apk").apply { writeBytes(payload) }
        checker.download(update(url), target) {}
        assertTrue(ranges.isEmpty())
    }
}

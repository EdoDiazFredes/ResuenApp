package cl.lossless.player.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SeekableFileStreamTest {

    private fun tempFile(name: String, bytes: ByteArray): File {
        val dir = Files.createTempDirectory("ñandú había").toFile()
        return File(dir, name).apply { writeBytes(bytes); deleteOnExit() }.also { dir.deleteOnExit() }
    }

    @Test
    fun seeksWithMarkResetAndSkip() {
        val data = ByteArray(10_000) { (it % 251).toByte() }
        val file = tempFile("Aquel que había muerto - Quieren.flac", data)
        SeekableFileStream(file).use { s ->
            assertTrue(s.markSupported())
            s.mark(0)
            assertEquals(4_000L, s.skip(4_000))
            assertEquals(data[4_000].toInt() and 0xFF, s.read())
            s.reset()
            assertEquals(data[0].toInt() and 0xFF, s.read())
            // Saltar más allá del final se detiene en el final y luego devuelve 0.
            assertEquals(9_999L, s.skip(Long.MAX_VALUE))
            assertEquals(0L, s.skip(10))
            assertEquals(-1, s.read())
            s.reset()
            val buf = ByteArray(16)
            assertEquals(16, s.read(buf, 0, 16))
            assertEquals(data.copyOfRange(0, 16).toList(), buf.toList())
        }
    }

    @Test
    fun nameGivenToFfmpegIsAsciiWithExtension() {
        val file = tempFile("Obstáculo.flac", ByteArray(4))
        SeekableFileStream(file).use { assertEquals("resuenapp-input.flac", it.toString()) }
    }
}

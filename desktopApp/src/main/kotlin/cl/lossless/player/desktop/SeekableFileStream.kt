package cl.lossless.player.desktop

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Archivo leído desde Java para pasárselo a FFmpeg sin su ruta (ver [Decoder.grabberFor]).
 *
 * javacv busca dentro del flujo con mark/reset + skip: aquí ambos son saltos directos en el
 * archivo, así que adelantar o retroceder no lee ni guarda en memoria lo que queda entre medio.
 */
internal class SeekableFileStream(file: File) : InputStream() {
    private val raf = RandomAccessFile(file, "r")
    private val ext = file.extension.filter { it.code < 0x80 && it.isLetterOrDigit() }
    private var markPos = 0L

    override fun read(): Int = raf.read()

    override fun read(b: ByteArray, off: Int, len: Int): Int = raf.read(b, off, len)

    override fun skip(n: Long): Long {
        if (n <= 0) return 0
        val pos = raf.filePointer
        val step = minOf(n, raf.length() - pos).coerceAtLeast(0)
        raf.seek(pos + step)
        return step
    }

    override fun available(): Int = (raf.length() - raf.filePointer).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    override fun markSupported(): Boolean = true

    @Synchronized override fun mark(readlimit: Int) {
        markPos = raf.filePointer
    }

    @Synchronized override fun reset() {
        raf.seek(markPos)
    }

    override fun close() = raf.close()

    /** javacv usa este texto como nombre para FFmpeg: solo ASCII y con la extensión, que ayuda a reconocer el formato. */
    override fun toString(): String = if (ext.isEmpty()) "resuenapp-input" else "resuenapp-input.$ext"
}

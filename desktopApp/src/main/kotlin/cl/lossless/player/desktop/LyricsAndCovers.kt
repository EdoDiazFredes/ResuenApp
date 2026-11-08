package cl.lossless.player.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import cl.lossless.player.lyrics.ChannelSource
import cl.lossless.player.lyrics.EmbeddedLyricsReader
import cl.lossless.player.lyrics.LrcLibClient
import cl.lossless.player.lyrics.LrcParser
import cl.lossless.player.lyrics.Lyrics
import cl.lossless.player.lyrics.LyricsOvhClient
import cl.lossless.player.lyrics.LyricsSource
import cl.lossless.player.lyrics.TrackMetadataCleaner
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/** Resultado de buscar la letra de una pista. */
sealed interface LyricsResult {
    data class Found(val lyrics: Lyrics) : LyricsResult
    data object None : LyricsResult
    /** No hubo respuesta (sin red, DNS, TLS, tiempo agotado). [detail] ayuda a diagnosticar. */
    data class Offline(val detail: String? = null) : LyricsResult
    /** LRCLIB respondió, pero con un error suyo (saturado o caído): no es la conexión del equipo. */
    data class ServerError(val code: Int) : LyricsResult

    /** Fallo pasajero: no se guarda y se puede reintentar. */
    val isTransient: Boolean get() = this is Offline || this is ServerError
}

/**
 * Busca la letra igual que en Android: .lrc junto al archivo, etiquetas del archivo y por
 * último LRCLIB. Lo de LRCLIB se guarda en disco para no volver a pedirlo.
 */
object DesktopLyrics {

    private val userAgent = "ResuenApp/1.2 (${System.getProperty("os.name") ?: "desktop"} lossless music player)"
    private val client = LrcLibClient(userAgent = userAgent)
    private val fallback = LyricsOvhClient(userAgent = userAgent)
    private val memory = ConcurrentHashMap<String, LyricsResult>()
    private val cacheDir get() = File(AppDirs.data, "lyrics").apply { mkdirs() }

    /** Sube cada vez que el usuario elige una letra a mano, para que el panel la vuelva a cargar. */
    var version by mutableIntStateOf(0)
        private set

    /** Bloqueante: llamar desde un hilo de E/S. */
    fun load(track: Track): LyricsResult {
        memory[track.path]?.let { return it }
        val result = readManual(track) ?: readSidecar(track) ?: readEmbedded(track) ?: readCached(track) ?: fetchOnline(track)
        if (!result.isTransient) memory[track.path] = result
        return result
    }

    /** Búsqueda a mano en LRCLIB con el título y el artista que escriba el usuario. Bloqueante. */
    @Throws(IOException::class)
    fun search(title: String, artist: String): List<LrcLibClient.Record> =
        client.search(title.trim(), artist.trim()).filter { it.hasLyrics }

    /** Guarda la letra elegida a mano; pasa por delante de .lrc, etiquetas y LRCLIB. */
    fun setManual(track: Track, record: LrcLibClient.Record) {
        val text = record.syncedLyrics?.takeIf { it.isNotBlank() } ?: record.plainLyrics.orEmpty()
        runCatching { manualFile(track).writeText(text) }
        memory.remove(track.path)
        version++
    }

    /** Olvida lo cargado de una pista (por ejemplo, tras editar sus etiquetas). */
    fun forget(paths: Collection<String>) {
        paths.forEach(memory::remove)
    }

    private fun manualFile(track: Track): File =
        File(File(cacheDir, "manual").apply { mkdirs() }, Integer.toHexString(track.path.hashCode()) + ".lrc")

    private fun readManual(track: Track): LyricsResult? {
        val f = manualFile(track)
        if (!f.isFile) return null
        return runCatching { LrcParser.toLyrics(f.readText(), LyricsSource.LRCLIB) }.getOrNull()?.let { LyricsResult.Found(it) }
    }

    private fun readSidecar(track: Track): LyricsResult? {
        val lrc = File(track.file.parentFile, track.file.nameWithoutExtension + ".lrc")
        if (!lrc.isFile) return null
        return runCatching { LrcParser.toLyrics(decodeText(lrc.readBytes()), LyricsSource.SIDECAR) }
            .getOrNull()?.let { LyricsResult.Found(it) }
    }

    private fun readEmbedded(track: Track): LyricsResult? {
        val fromReader = runCatching {
            FileChannel.open(track.file.toPath(), StandardOpenOption.READ).use { EmbeddedLyricsReader.read(ChannelSource(it)) }
        }.getOrNull()
        if (fromReader != null) return LyricsResult.Found(fromReader)
        // M4A/ALAC y otros: lo que jaudiotagger encuentre en el campo de letra.
        val text = runCatching { AudioFileIO.read(track.file).tag?.getFirst(FieldKey.LYRICS) }.getOrNull()
        return text?.takeIf { it.isNotBlank() }?.let { LrcParser.toLyrics(it, LyricsSource.EMBEDDED) }?.let { LyricsResult.Found(it) }
    }

    private fun cacheFile(track: Track): File {
        val key = Integer.toHexString(track.path.hashCode()) + "_" + track.file.length()
        return File(cacheDir, "$key.txt")
    }

    private fun readCached(track: Track): LyricsResult? {
        val f = cacheFile(track)
        if (!f.isFile) return null
        val text = runCatching { f.readText() }.getOrNull() ?: return null
        if (text == NONE_MARK) return LyricsResult.None
        if (text == INSTRUMENTAL_MARK) return LyricsResult.Found(Lyrics(null, null, LyricsSource.LRCLIB, instrumental = true))
        if (text.startsWith(OVH_MARK)) return LyricsResult.Found(Lyrics(null, text.removePrefix(OVH_MARK), LyricsSource.LYRICS_OVH))
        return LrcParser.toLyrics(text, LyricsSource.LRCLIB)?.let { LyricsResult.Found(it) }
    }

    private fun fetchOnline(track: Track): LyricsResult {
        val (title, artist) = TrackMetadataCleaner.clean(track.title, track.artist)
        if (title.isBlank()) return LyricsResult.None
        val durationSec = track.durationMs / 1000
        // LRCLIB primero (puede traer tiempos); si falla o no tiene nada, Lyrics.ovh (como en Android).
        var lrclibError: LyricsResult? = null
        val record = try {
            var exact: LrcLibClient.Record? = null
            if (artist.isNotBlank() && durationSec > 0) {
                exact = try { client.get(title, artist, durationSec) } catch (e: LrcLibClient.ServerError) { null }
            }
            exact?.takeIf { it.hasLyrics || it.instrumental }
                ?: LrcLibClient.pickBest(client.search(title, artist), title, artist, durationSec)
        } catch (e: LrcLibClient.ServerError) {
            System.err.println("LRCLIB respondió HTTP ${e.code} para ${track.path}")
            lrclibError = LyricsResult.ServerError(e.code)
            null
        } catch (e: IOException) {
            System.err.println("Sin respuesta de LRCLIB para ${track.path}: $e")
            lrclibError = LyricsResult.Offline(describe(e))
            null
        }
        val f = cacheFile(track)
        if (record != null) {
            val synced = record.syncedLyrics
            val plain = record.plainLyrics
            val text = when {
                !synced.isNullOrBlank() -> synced
                !plain.isNullOrBlank() -> plain
                else -> null
            }
            if (text != null) {
                runCatching { f.writeText(text) }
                LrcParser.toLyrics(text, LyricsSource.LRCLIB)?.let { return LyricsResult.Found(it) }
            } else if (record.instrumental) {
                runCatching { f.writeText(INSTRUMENTAL_MARK) }
                return LyricsResult.Found(Lyrics(null, null, LyricsSource.LRCLIB, instrumental = true))
            }
        }

        var fallbackFailed = false
        val fromOvh = try {
            fallback.get(artist, title)
        } catch (e: IOException) {
            fallbackFailed = true
            null
        }
        if (fromOvh != null) {
            // Se guarda con su marca para recordar de dónde vino.
            runCatching { f.writeText(OVH_MARK + fromOvh) }
            return LyricsResult.Found(Lyrics(null, fromOvh, LyricsSource.LYRICS_OVH))
        }
        return when {
            // Algo no respondió: no se guarda "sin letra" para reintentar la próxima vez.
            lrclibError != null -> lrclibError
            fallbackFailed -> LyricsResult.None
            else -> {
                runCatching { f.writeText(NONE_MARK) }
                LyricsResult.None
            }
        }
    }

    private fun describe(e: IOException): String = when (e) {
        is java.net.UnknownHostException -> "DNS"
        is java.net.SocketTimeoutException -> "timeout"
        is javax.net.ssl.SSLException -> "TLS: ${e.message.orEmpty()}".trim().removeSuffix(":")
        else -> e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
    }

    /** Los .lrc suelen ser UTF-8; los antiguos de Windows, CP1252. */
    private fun decodeText(bytes: ByteArray): String =
        try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("﻿")
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }

    private const val NONE_MARK = "\u0000none"
    private const val INSTRUMENTAL_MARK = "\u0000instrumental"
    private const val OVH_MARK = "\u0000ovh\n"
}

/** Carátulas: la incrustada en el archivo o cover.jpg/folder.jpg en la carpeta, reducidas. */
object Covers {

    private const val MAX_ENTRIES = 400
    private val cache = object : LinkedHashMap<String, ImageBitmap?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap?>?) = size > MAX_ENTRIES
    }
    private val folderNames = listOf("cover", "folder", "front", "album", "albumart")

    /** Sube al cambiar una carátula, para que las imágenes en pantalla se vuelvan a leer. */
    var version by mutableIntStateOf(0)
        private set

    /** Olvida todas las carátulas leídas (tras guardar una nueva en los archivos). */
    fun invalidate() {
        synchronized(cache) { cache.clear() }
        version++
    }

    fun cached(key: String, size: Int): ImageBitmap? = synchronized(cache) { cache["$key@$size"] }
    fun isCached(key: String, size: Int): Boolean = synchronized(cache) { cache.containsKey("$key@$size") }

    /** Bloqueante. [size] es el lado máximo en píxeles. */
    fun load(track: Track, size: Int): ImageBitmap? {
        val key = "${track.albumKey}@$size"
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        val bytes = embedded(track) ?: folderImage(track)
        val image = bytes?.let { decode(it, size) }
        synchronized(cache) { cache[key] = image }
        return image
    }

    private fun embedded(track: Track): ByteArray? = runCatching {
        AudioFileIO.read(track.file).tag?.firstArtwork?.binaryData
    }.getOrNull()

    private fun folderImage(track: Track): ByteArray? {
        val dir = track.file.parentFile ?: return null
        val files = dir.listFiles() ?: return null
        val image = files.filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png") }
        val chosen = image.firstOrNull { it.nameWithoutExtension.lowercase() in folderNames } ?: image.singleOrNull()
        return chosen?.let { runCatching { it.readBytes() }.getOrNull() }
    }

    private fun decode(bytes: ByteArray, size: Int): ImageBitmap? = runCatching {
        val src = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        val scale = minOf(1.0, size.toDouble() / maxOf(src.width, src.height))
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(src, 0, 0, w, h, null)
        g.dispose()
        out.toComposeImageBitmap()
    }.getOrNull()
}

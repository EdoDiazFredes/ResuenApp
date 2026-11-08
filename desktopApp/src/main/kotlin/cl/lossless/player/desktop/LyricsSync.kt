package cl.lossless.player.desktop

import cl.lossless.player.lyrics.LrcParser
import cl.lossless.player.lyrics.LrcWriter
import cl.lossless.player.lyrics.LyricLine
import cl.lossless.player.lyrics.Lyrics
import cl.lossless.player.lyrics.LyricsAligner
import cl.lossless.player.lyrics.LyricsSource
import cl.lossless.player.lyrics.TrackMetadataCleaner
import cl.lossless.player.lyrics.VocalActivity
import cl.lossless.player.lyrics.VocalSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Sincronizar letras que vienen solo como texto, igual que en Android:
 *
 * - **Estimación**: se analiza el audio (tramos con voz, [VocalActivity]) y se reparten las
 *   líneas con [LyricsAligner]. Es aproximada, sin internet; se guarda para no repetirla.
 * - **A mano**: el usuario marca cuándo empieza cada línea mientras suena; se guarda como .lrc
 *   junto a la canción (si se puede escribir en la carpeta) y siempre una copia en la app.
 */
object LyricsSync {

    private val dir get() = File(AppDirs.data, "lyrics").apply { mkdirs() }
    private val memory = ConcurrentHashMap<String, Lyrics>()
    /** Un análisis por pista a la vez: el panel y el mini reproductor lo piden juntos. */
    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun hash(track: Track) = Integer.toHexString(track.path.hashCode())

    // ---------- Sincronización a mano ----------

    private fun userFile(track: Track): File = File(File(dir, "user").apply { mkdirs() }, hash(track) + ".lrc")

    /** Lo sincronizado a mano en esta app, o null. Bloqueante. */
    fun readUser(track: Track): Lyrics? {
        val f = userFile(track)
        if (!f.isFile) return null
        return runCatching { LrcParser.toLyrics(f.readText(), LyricsSource.USER) }.getOrNull()?.takeIf { it.isSynced }
    }

    /** Olvida lo sincronizado a mano (al elegir otra letra con "Buscar letra…"). */
    fun clearUser(track: Track) {
        runCatching { userFile(track).delete() }
    }

    /**
     * Guarda los tiempos marcados a mano. Devuelve true si además se escribió "<canción>.lrc"
     * junto al audio (lo leerán también otros reproductores).
     */
    suspend fun saveUser(track: Track, lines: List<LyricLine>): Boolean {
        val toFolder = withContext(Dispatchers.IO) {
            val (title, artist) = TrackMetadataCleaner.clean(track.title, track.artist)
            val lrc = LrcWriter.write(lines, title, artist)
            runCatching { userFile(track).writeText(lrc) }
            val sidecar = File(track.file.parentFile, track.file.nameWithoutExtension + ".lrc")
            runCatching { sidecar.writeText(lrc); true }.getOrDefault(false)
        }
        // En el hilo de la interfaz: [DesktopLyrics.version] es estado de Compose.
        DesktopLyrics.changed(track)
        return toFolder
    }

    // ---------- Estimación ----------

    private fun estimateFile(track: Track, plain: String): File =
        File(File(dir, "estimate").apply { mkdirs() }, "${hash(track)}_${track.file.length()}_${Integer.toHexString(plain.hashCode())}.lrc")

    /** La estimación ya hecha, sin analizar nada. Bloqueante. */
    fun cachedEstimate(track: Track, lyrics: Lyrics): Lyrics? {
        val plain = lyrics.plain?.takeIf { it.isNotBlank() } ?: return null
        val key = estimateFile(track, plain).name
        memory[key]?.let { return it }
        val f = File(File(dir, "estimate"), key)
        if (!f.isFile) return null
        val synced = runCatching { LrcParser.parse(f.readText()) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return lyrics.copy(synced = synced, estimated = true).also { memory[key] = it }
    }

    /**
     * Estima los tiempos de una letra sin marcas analizando el audio. null si no hay líneas.
     * Se puede cancelar (al cambiar de pista se deja de decodificar).
     */
    suspend fun estimate(track: Track, lyrics: Lyrics): Lyrics? {
        val plain = lyrics.plain?.takeIf { it.isNotBlank() } ?: return null
        val lines = LyricsAligner.splitLines(plain)
        if (lines.isEmpty()) return null
        val file = estimateFile(track, plain)
        return locks.computeIfAbsent(file.name) { Mutex() }.withLock {
            withContext(Dispatchers.IO) { cachedEstimate(track, lyrics) }?.let { return@withLock it }
            val segments = withContext(Dispatchers.Default) { vocalSegments(track) }.orEmpty()
            val duration = track.durationMs.takeIf { it > 0 } ?: (segments.lastOrNull()?.endMs ?: 0)
            val synced = LyricsAligner.align(lines, segments, duration)
            withContext(Dispatchers.IO) { runCatching { file.writeText(LrcWriter.write(synced)) } }
            lyrics.copy(synced = synced, estimated = true).also { memory[file.name] = it }
        }
    }

    /** Decodifica la pista con FFmpeg y detecta los tramos con voz. null si no se pudo leer. */
    private suspend fun vocalSegments(track: Track): List<VocalSegment>? {
        val grabber = runCatching { Decoder.open(track.file) }.getOrNull() ?: return null
        try {
            val channels = grabber.audioChannels.coerceAtLeast(1)
            val accumulator = VocalActivity.Accumulator(grabber.sampleRate)
            while (true) {
                currentCoroutineContext().ensureActive()
                val frame = grabber.grabSamples() ?: break
                val samples = Decoder.toInt32Interleaved(frame, channels) ?: continue
                var i = 0
                while (i + channels - 1 < samples.size) {
                    val left = samples[i] / 2_147_483_648.0
                    val right = if (channels >= 2) samples[i + 1] / 2_147_483_648.0 else left
                    accumulator.add(left, right)
                    i += channels
                }
            }
            return VocalActivity.detect(accumulator.features())
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return null
        } finally {
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
        }
    }
}

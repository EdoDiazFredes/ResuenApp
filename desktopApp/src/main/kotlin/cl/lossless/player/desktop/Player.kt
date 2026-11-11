package cl.lossless.player.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import java.nio.ByteBuffer
import java.nio.DoubleBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.ShortBuffer
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import kotlin.math.log10
import kotlin.math.roundToInt

/** Repetir: nada, toda la cola o solo la canción actual. [next] es el que sigue al tocar el botón. */
enum class RepeatMode {
    OFF, ALL, ONE;

    val next: RepeatMode get() = entries[(ordinal + 1) % entries.size]
}

/** Permutación aleatoria de 0 until [size] que empieza por [first] (si es un índice válido). */
internal fun shuffledOrder(size: Int, first: Int): List<Int> {
    val rest = (0 until size).filter { it != first }.shuffled()
    return if (first in 0 until size) listOf(first) + rest else rest
}

data class PlayerState(
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    val playing: Boolean = false,
    val durationMs: Long = 0,
    /** "24 bits / 96 kHz" de la salida abierta, o null si no hay nada sonando. */
    val outputFormat: String? = null,
    val error: String? = null,
) {
    val current: Track? get() = queue.getOrNull(index)
}

/**
 * Cola de reproducción y motor de audio: FFmpeg decodifica a PCM de 32 bits y Java Sound lo
 * envía a la salida predeterminada de Windows con la frecuencia del archivo, en 24 bits si se
 * puede (16 si no). Es modo compartido: Windows mezcla y puede remuestrear.
 */
class Player {

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    @Volatile private var session: Session? = null
    @Volatile private var thread: Thread? = null
    private val _volume = MutableStateFlow(AppSettings.volume)
    /** Volumen 0..1; lo comparten la barra inferior y el mini reproductor. */
    val volumeState: StateFlow<Float> = _volume.asStateFlow()
    val volume: Float get() = _volume.value
    private var beforeMute = if (AppSettings.volume > 0.001f) AppSettings.volume else 1f

    private val _repeat = MutableStateFlow(AppSettings.repeat)
    val repeat: StateFlow<RepeatMode> = _repeat.asStateFlow()

    /** Pasa a la siguiente opción: no repetir → repetir todo → repetir una. */
    fun cycleRepeat() {
        val mode = _repeat.value.next
        _repeat.value = mode
        AppSettings.repeat = mode
    }

    private val _shuffle = MutableStateFlow(AppSettings.shuffle)
    /** Aleatorio: la cola se muestra en su orden, pero se recorre según [order]. */
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    /** Orden de reproducción en aleatorio (índices de la cola); vacío si está apagado. */
    @Volatile private var order: List<Int> = emptyList()

    fun toggleShuffle() {
        val on = !_shuffle.value
        _shuffle.value = on
        AppSettings.shuffle = on
        rebuildOrder()
    }

    /** La cola se terminó de reproducir; si llegan más pistas con [append], sigue con ellas. */
    @Volatile private var endedAtTail = false

    fun playQueue(tracks: List<Track>, start: Int) {
        if (tracks.isEmpty()) return
        endedAtTail = false
        _state.update { it.copy(queue = tracks, index = start.coerceIn(tracks.indices), error = null) }
        rebuildOrder()
        startCurrent()
    }

    /** Baraja la cola dejando la canción actual primero, así lo que suena no se interrumpe. */
    private fun rebuildOrder() {
        val st = _state.value
        order = if (!_shuffle.value || st.queue.isEmpty()) emptyList()
        else shuffledOrder(st.queue.size, st.index)
    }

    /**
     * Índice de la cola a [step] posiciones de la actual (en el orden aleatorio si está activo),
     * o -1 si no hay. Con [wrap] da la vuelta: tras la última vuelve a la primera.
     */
    private fun neighbor(step: Int, wrap: Boolean): Int {
        val st = _state.value
        val n = st.queue.size
        if (n == 0) return -1
        val seq = order.takeIf { _shuffle.value && it.size == n }
        val pos = seq?.indexOf(st.index) ?: st.index
        val target = pos + step
        val i = when {
            target in 0 until n -> target
            wrap -> Math.floorMod(target, n)
            else -> return -1
        }
        return seq?.get(i) ?: i
    }

    fun toggle() {
        val s = session
        if (s == null) {
            if (_state.value.current != null) startCurrent()
            return
        }
        s.paused = !s.paused
        _state.update { it.copy(playing = !s.paused) }
    }

    /** Siguiente a mano: con "repetir todo" vuelve a la primera tras la última. */
    fun next() {
        val target = neighbor(1, wrap = _repeat.value == RepeatMode.ALL)
        if (target < 0) return
        _state.update { it.copy(index = target, error = null) }
        startCurrent()
    }

    fun previous() {
        val target = neighbor(-1, wrap = false)
        if (positionMs() > 3_000 || target < 0) {
            seek(0)
        } else {
            _state.update { it.copy(index = target, error = null) }
            startCurrent()
        }
    }

    fun seek(ms: Long) {
        session?.seekToMs = ms.coerceAtLeast(0)
    }

    /** Avanza o retrocede [deltaMs] dentro de la pista actual. */
    fun seekBy(deltaMs: Long) {
        val s = session ?: return
        val duration = _state.value.durationMs
        val target = s.positionMs() + deltaMs
        seek(if (duration > 0) target.coerceAtMost(duration - 500) else target)
    }

    /** Detiene la reproducción y vuelve al inicio de la pista (Reproducir la empieza de nuevo). */
    fun stop() {
        session?.stop()
        session = null
        _state.update { it.copy(playing = false, outputFormat = null) }
    }

    fun positionMs(): Long = session?.positionMs() ?: 0

    fun setVolume(v: Float) {
        val value = v.coerceIn(0f, 1f)
        _volume.value = value
        AppSettings.volume = value
        session?.applyVolume(value)
    }

    /** Silencia, o vuelve al volumen que había antes de silenciar. */
    fun toggleMute() {
        if (volume > 0.001f) {
            beforeMute = volume
            setVolume(0f)
        } else {
            setVolume(beforeMute)
        }
    }

    /**
     * Corre [block] con los archivos [paths] cerrados: si uno es el que suena, detiene la salida,
     * espera a que FFmpeg lo suelte (Windows no deja reescribir un archivo abierto) y luego
     * retoma en el mismo punto, en pausa si lo estaba. Bloqueante.
     */
    fun <T> withFilesReleased(paths: Set<String>, block: () -> T): T {
        val s = session
        val current = _state.value.current
        if (s == null || current == null || current.path !in paths) return block()
        val position = s.positionMs()
        val paused = s.paused
        s.stop()
        thread?.join(2_000)
        try {
            return block()
        } finally {
            if (session === s) {
                startCurrent()
                session?.let { it.seekToMs = position; it.paused = paused }
                if (paused) _state.update { it.copy(playing = false) }
            }
        }
    }

    /**
     * Agrega [tracks] al final de la cola (las pistas de Drive llegan a medida que se descargan).
     * Si la cola ya se había terminado esperando la siguiente, sigue con la primera agregada.
     */
    fun append(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val firstNew = _state.value.queue.size
        _state.update { it.copy(queue = it.queue + tracks) }
        // En aleatorio, las que llegan se suman al final del recorrido ya barajado.
        if (_shuffle.value && order.size == firstNew) order = order + (firstNew until firstNew + tracks.size)
        if (endedAtTail && session == null) {
            endedAtTail = false
            _state.update { it.copy(index = firstNew, error = null) }
            startCurrent()
        }
    }

    /** Cambia en la cola las pistas cuyas etiquetas se editaron, sin cortar la reproducción. */
    fun replaceTracks(updated: Map<String, Track>) {
        _state.update { st -> st.copy(queue = st.queue.map { updated[it.path] ?: it }) }
    }

    fun shutdown() {
        session?.stop()
        session = null
    }

    private fun startCurrent() {
        endedAtTail = false
        session?.stop()
        val track = _state.value.current ?: return
        val s = Session(track)
        session = s
        _state.update { it.copy(playing = true, durationMs = track.durationMs, outputFormat = null) }
        thread = Thread({ s.run() }, "resuenapp-audio").apply { isDaemon = true; start() }
    }

    private fun onEnded(s: Session) {
        if (session !== s) return
        val target = when (_repeat.value) {
            RepeatMode.ONE -> _state.value.index
            else -> neighbor(1, wrap = _repeat.value == RepeatMode.ALL)
        }
        if (target >= 0) {
            _state.update { it.copy(index = target) }
            startCurrent()
        } else {
            session = null
            endedAtTail = true
            _state.update { it.copy(playing = false, outputFormat = null) }
        }
    }

    private fun onFailed(s: Session, error: Throwable) {
        if (session !== s) return
        session = null
        _state.update { it.copy(playing = false, outputFormat = null, error = error.message ?: error.javaClass.simpleName) }
    }

    /** Una pista sonando. Corre en su propio hilo; los controles le llegan por campos volátiles. */
    private inner class Session(private val track: Track) {
        @Volatile var paused = false
        @Volatile var seekToMs = -1L
        @Volatile private var stopped = false
        @Volatile private var line: SourceDataLine? = null
        @Volatile private var baseMs = 0L
        @Volatile private var baseFrame = 0L
        @Volatile private var rate = 44_100

        fun stop() {
            stopped = true
        }

        fun positionMs(): Long {
            val l = line ?: return baseMs
            return baseMs + (l.longFramePosition - baseFrame) * 1000 / rate
        }

        fun applyVolume(v: Float) {
            val l = line ?: return
            if (!l.isControlSupported(FloatControl.Type.MASTER_GAIN)) return
            val gain = l.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
            val db = if (v <= 0.001f) gain.minimum else (20 * log10(v)).coerceIn(gain.minimum, gain.maximum)
            gain.value = db
        }

        fun run() {
            var grabber: FFmpegFrameGrabber? = null
            var out: SourceDataLine? = null
            try {
                grabber = Decoder.open(track.file)
                rate = grabber.sampleRate
                val channels = grabber.audioChannels
                val (opened, bits) = openLine(rate, channels, track.bitsPerSample)
                out = opened
                line = opened
                applyVolume(volume)
                _state.update { it.copy(outputFormat = "$bits bits / ${khz(rate)} kHz", durationMs = durationOf(grabber)) }
                opened.start()
                val bytesPerFrame = channels * bits / 8
                val eq = EqProcessor(rate, channels)
                var buffer = ByteArray(0)

                while (!stopped) {
                    val seek = seekToMs
                    if (seek >= 0) {
                        seekToMs = -1
                        grabber.setTimestamp(seek * 1000)
                        opened.flush()
                        baseMs = seek
                        baseFrame = opened.longFramePosition
                        eq.reset()
                    }
                    if (paused) {
                        if (opened.isRunning) opened.stop()
                        Thread.sleep(20)
                        continue
                    } else if (!opened.isRunning) {
                        opened.start()
                    }
                    val frame = grabber.grabSamples() ?: break
                    val samples = Decoder.toInt32Interleaved(frame, channels) ?: continue
                    eq.process(samples)
                    val needed = samples.size / channels * bytesPerFrame
                    if (buffer.size < needed) buffer = ByteArray(needed)
                    val n = Decoder.pack(samples, bits, buffer)
                    var offset = 0
                    while (offset < n && !stopped && seekToMs < 0) {
                        offset += opened.write(buffer, offset, n - offset)
                    }
                }
                if (!stopped) {
                    opened.drain()
                    onEnded(this)
                }
            } catch (t: Throwable) {
                if (!stopped) onFailed(this, t)
            } finally {
                line = null
                runCatching { out?.stop() }
                runCatching { out?.close() }
                runCatching { grabber?.stop() }
                runCatching { grabber?.release() }
            }
        }

        private fun durationOf(g: FFmpegFrameGrabber): Long =
            (g.lengthInTime / 1000).takeIf { it > 0 } ?: track.durationMs
    }

    /** Abre la salida en 24 bits si el archivo lo es y el sistema lo acepta; si no, en 16. */
    private fun openLine(rate: Int, channels: Int, sourceBits: Int): Pair<SourceDataLine, Int> {
        val depths = if (sourceBits > 16) listOf(24, 16) else listOf(16)
        var last: Exception? = null
        for (bits in depths) {
            val format = AudioFormat(rate.toFloat(), bits, channels, true, false)
            try {
                val line = AudioSystem.getSourceDataLine(format)
                val bytesPerSecond = rate * channels * bits / 8
                line.open(format, bytesPerSecond / 4)
                return line to bits
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("No audio output")
    }
}

/** Decodificación con FFmpeg (javacv), separada para poder probarla sin salida de audio. */
object Decoder {

    fun open(file: java.io.File): FFmpegFrameGrabber {
        val g = FFmpegFrameGrabber(file)
        g.sampleFormat = avutil.AV_SAMPLE_FMT_S32
        g.start()
        if (g.audioChannels > 2) {
            // Java Sound no abre salidas multicanal de forma fiable: se mezcla a estéreo.
            g.stop(); g.release()
            return FFmpegFrameGrabber(file).apply {
                sampleFormat = avutil.AV_SAMPLE_FMT_S32
                audioChannels = 2
                start()
            }
        }
        return g
    }

    /** Muestras del cuadro como enteros de 32 bits intercalados (L R L R…). */
    fun toInt32Interleaved(frame: Frame, channels: Int): IntArray? {
        val buffers = frame.samples ?: return null
        if (buffers.isEmpty()) return null
        val planes = buffers.map { readPlane(it) }
        if (planes.size == 1) return planes[0]
        // Formato planar: un búfer por canal.
        val perChannel = planes.minOf { it.size }
        val out = IntArray(perChannel * channels)
        for (i in 0 until perChannel) for (c in 0 until channels) out[i * channels + c] = planes[c.coerceAtMost(planes.lastIndex)][i]
        return out
    }

    private fun readPlane(b: java.nio.Buffer): IntArray {
        val buf = b.duplicate().rewind()
        return when (buf) {
            is IntBuffer -> IntArray(buf.remaining()).also { buf.get(it) }
            is ShortBuffer -> IntArray(buf.remaining()) { buf.get().toInt() shl 16 }
            is FloatBuffer -> IntArray(buf.remaining()) { (buf.get().coerceIn(-1f, 1f) * Int.MAX_VALUE).roundToInt() }
            is DoubleBuffer -> IntArray(buf.remaining()) { (buf.get().coerceIn(-1.0, 1.0) * Int.MAX_VALUE).toInt() }
            is ByteBuffer -> IntArray(buf.remaining()) { ((buf.get().toInt() and 0xFF) - 128) shl 24 }
            else -> IntArray(0)
        }
    }

    /** Escribe las muestras en little-endian con [bits] (24 o 16). Devuelve los bytes escritos. */
    fun pack(samples: IntArray, bits: Int, out: ByteArray): Int {
        var o = 0
        if (bits == 24) {
            for (v in samples) {
                out[o++] = (v shr 8).toByte()
                out[o++] = (v shr 16).toByte()
                out[o++] = (v shr 24).toByte()
            }
        } else {
            for (v in samples) {
                out[o++] = (v shr 16).toByte()
                out[o++] = (v shr 24).toByte()
            }
        }
        return o
    }
}

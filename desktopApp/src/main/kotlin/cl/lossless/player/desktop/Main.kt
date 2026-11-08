package cl.lossless.player.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import cl.lossless.player.desktop.ui.Accent
import cl.lossless.player.desktop.ui.App
import cl.lossless.player.desktop.ui.AppIcon
import cl.lossless.player.desktop.ui.Appearance
import cl.lossless.player.desktop.ui.MiniPlayer
import cl.lossless.player.desktop.ui.NeonTheme
import cl.lossless.player.desktop.ui.miniPlayerSize
import cl.lossless.player.lyrics.LrcParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.GraphicsEnvironment
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.sin
import javax.swing.UIManager
import kotlin.system.exitProcess

/** "1.2.N", generada al compilar (ver generateVersion en build.gradle.kts). */
val APP_VERSION: String by lazy {
    Player::class.java.classLoader.getResourceAsStream("resuenapp-version.txt")?.use { it.readBytes().decodeToString().trim() } ?: "1.2.0"
}

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--self-test") {
        exitProcess(selfTest(args.getOrNull(1)?.let(::File)))
    }
    // Ya hay una ResuenApp abierta: le pasa los archivos (si los hay) y esta copia se cierra.
    if (!SingleInstance.acquire(args)) exitProcess(0)
    // Diálogos de Swing (elegir carpeta) con el aspecto nativo del sistema.
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    val player = Player()
    Shortcuts.start(player)
    // Archivos abiertos con la app ("Abrir con", doble clic, Finder): suenan de inmediato.
    SingleInstance.onFiles { files ->
        val tracks = files.mapNotNull { runCatching { LibraryScanner.readTrack(it.toPath()) }.getOrNull() }
        player.playQueue(tracks, 0)
    }
    application {
        val searchFocus = remember { FocusRequester() }
        var mini by remember { mutableStateOf(false) }
        var miniOnTop by remember { mutableStateOf(AppSettings.miniOnTop) }
        // Se abrió la app otra vez: se muestra la ventana que esté en uso (completa o mini).
        val raise by SingleInstance.raise.collectAsState()
        val miniState = rememberWindowState(size = miniPlayerSize(), position = miniStartPosition())

        fun showFull() {
            AppSettings.miniPosition = (miniState.position as? WindowPosition.Absolute)?.let { it.x.value to it.y.value }
            mini = false
        }

        // La ventana completa solo se oculta mientras el mini reproductor está abierto, así la
        // biblioteca, la búsqueda y la pestaña abierta siguen igual al volver.
        Window(
            onCloseRequest = {
                player.shutdown()
                Shortcuts.shutdown()
                SingleInstance.release()
                exitApplication()
            },
            visible = !mini,
            title = "ResuenApp",
            icon = BitmapPainter(AppIcon),
            state = rememberWindowState(width = 1360.dp, height = 860.dp),
            // Ctrl+F (⌘F en macOS) lleva a la búsqueda, como en la maqueta.
            onPreviewKeyEvent = { e ->
                if (Shortcuts.handleCapture(e)) {
                    true
                } else if (e.type == KeyEventType.KeyDown && (if (IsMac) e.isMetaPressed else e.isCtrlPressed) && e.key == Key.F) {
                    runCatching { searchFocus.requestFocus() }
                    true
                } else {
                    false
                }
            },
            // Atajos de reproducción: solo si nada enfocado (la búsqueda, un campo) usó la tecla.
            onKeyEvent = { e -> Shortcuts.handle(e, player) },
        ) {
            LaunchedEffect(raise) { if (raise > 0 && !mini) SingleInstance.bringToFront(window) }
            // Barra de título de Windows con los colores de la app (fondo del tema, borde de acento).
            val palette = Appearance.palette
            LaunchedEffect(palette, mini) {
                WindowsTitleBar.apply(window, dark = palette.dark, caption = palette.night, text = palette.text, border = palette.accent)
            }
            // "Color del sistema": sigue el color de énfasis si se cambia con la app abierta.
            LaunchedEffect(Appearance.accent) {
                while (Appearance.accent == Accent.SYSTEM) {
                    SystemAccent.update(withContext(Dispatchers.IO) { SystemAccent.read() })
                    delay(5_000)
                }
            }
            App(player, searchFocus, onMiniPlayer = {
                miniState.size = miniPlayerSize()
                mini = true
            })
        }

        if (mini) {
            Window(
                // Cerrar el mini reproductor (Alt+F4) vuelve a la vista completa, no sale de la app.
                onCloseRequest = ::showFull,
                title = "ResuenApp",
                icon = BitmapPainter(AppIcon),
                state = miniState,
                undecorated = true,
                transparent = true,
                resizable = false,
                alwaysOnTop = miniOnTop,
                onPreviewKeyEvent = { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { showFull(); true } else false
                },
                onKeyEvent = { e -> Shortcuts.handle(e, player) },
            ) {
                LaunchedEffect(raise) { if (raise > 0) SingleInstance.bringToFront(window) }
                CompositionLocalProvider(LocalLang provides (AppSettings.language ?: Lang.system())) {
                    NeonTheme {
                        MiniPlayer(
                            player,
                            pinned = miniOnTop,
                            onPin = { miniOnTop = !miniOnTop; AppSettings.miniOnTop = miniOnTop },
                            onExpand = ::showFull,
                        )
                    }
                }
            }
        }
    }
}

/** Donde se dejó el mini reproductor, si sigue dentro de alguna pantalla; si no, abajo a la derecha. */
private fun miniStartPosition(): WindowPosition {
    val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
    AppSettings.miniPosition?.let { (x, y) ->
        val visible = runCatching {
            env.screenDevices.any { it.defaultConfiguration.bounds.contains(x.toInt() + 40, y.toInt() + 20) }
        }.getOrDefault(false)
        if (visible) return WindowPosition(x.dp, y.dp)
    }
    val area = runCatching { env.maximumWindowBounds }.getOrNull() ?: return WindowPosition(Alignment.BottomEnd)
    val size = miniPlayerSize()
    return WindowPosition(
        (area.x + area.width - size.width.value - 24).dp,
        (area.y + area.height - size.height.value - 24).dp,
    )
}

/**
 * Prueba rápida que corre CI sobre el ejecutable ya empaquetado: comprueba que FFmpeg y las
 * etiquetas funcionan dentro del instalador (sin abrir la salida de audio, que el servidor no
 * tiene). Escribe "OK" o el error en [report].
 */
private fun selfTest(report: File?): Int {
    val log = StringBuilder()
    val code = try {
        val wav = File.createTempFile("resuenapp-selftest", ".wav")
        writeSineWav(wav, rate = 96_000, bits = 24, seconds = 1)
        val track = LibraryScanner.readTrack(wav.toPath())
        log.appendLine("tags: ${track.formatLabel}, ${track.durationMs} ms")
        check(track.sampleRate == 96_000) { "frecuencia leída: ${track.sampleRate}" }

        val grabber = Decoder.open(wav)
        var frames = 0L
        try {
            while (true) {
                val frame = grabber.grabSamples() ?: break
                val samples = Decoder.toInt32Interleaved(frame, grabber.audioChannels) ?: continue
                frames += samples.size / grabber.audioChannels
            }
        } finally {
            grabber.stop(); grabber.release()
        }
        log.appendLine("ffmpeg: $frames frames")
        check(frames in 90_000..100_000) { "cuadros decodificados: $frames" }

        check(AppIcon.width == 256) { "ícono: ${AppIcon.width}px" }
        log.appendLine("icon: ok")

        val lines = LrcParser.parse("[00:01.00]Hola\n[00:02.50]Mundo")
        check(lines?.size == 2) { "LRC" }
        wav.delete()
        log.insert(0, "OK\n")
        0
    } catch (t: Throwable) {
        log.insert(0, "FAIL ${t}\n")
        log.appendLine(t.stackTraceToString())
        1
    }
    if (report != null) report.writeText(log.toString()) else print(log)
    return code
}

private fun writeSineWav(file: File, rate: Int, bits: Int, seconds: Int) {
    val channels = 2
    val frames = rate * seconds
    val bytesPerSample = bits / 8
    val dataSize = frames * channels * bytesPerSample
    DataOutputStream(FileOutputStream(file).buffered()).use { out ->
        fun le32(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
        fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        out.writeBytes("RIFF"); le32(36 + dataSize); out.writeBytes("WAVE")
        out.writeBytes("fmt "); le32(16); le16(1); le16(channels); le32(rate)
        le32(rate * channels * bytesPerSample); le16(channels * bytesPerSample); le16(bits)
        out.writeBytes("data"); le32(dataSize)
        val max = (1 shl (bits - 1)) - 1
        for (i in 0 until frames) {
            val v = (sin(2 * PI * 440 * i / rate) * max * 0.5).toInt()
            repeat(channels) { for (b in 0 until bytesPerSample) out.write((v shr (8 * b)) and 0xFF) }
        }
    }
}

package cl.lossless.player.desktop

import java.awt.Desktop
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Una sola ResuenApp abierta a la vez. La primera que arranca toma un candado
 * (instance.lock en la carpeta de datos) y escucha en un puerto local; si se vuelve a abrir la
 * app, por ejemplo con "Abrir con" sobre un archivo de audio, la nueva copia le pasa los
 * archivos a la que ya está abierta por ese puerto y se cierra sin mostrar nada.
 *
 * En macOS el sistema ya evita la segunda copia; ahí los archivos llegan como evento de
 * "abrir archivo" del Finder ([Desktop.setOpenFileHandler]), que también pasa por aquí.
 */
object SingleInstance {

    private val lockFile get() = File(AppDirs.data, "instance.lock")
    private val portFile get() = File(AppDirs.data, "instance.port")

    // Se guardan para que el candado y el servidor vivan mientras la app esté abierta.
    private var channel: FileChannel? = null
    private var lock: FileLock? = null
    private var server: ServerSocket? = null
    private var token = ""

    /** Sube cada vez que otra copia pide mostrar la ventana (para traerla al frente). */
    private val _raise = MutableStateFlow(0)
    val raise: StateFlow<Int> = _raise.asStateFlow()

    /** Quien reproduce los archivos recibidos; se fija con [onFiles] cuando el reproductor existe. */
    @Volatile private var handler: ((List<File>) -> Unit)? = null
    private val pending = mutableListOf<File>()
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "resuenapp-open").apply { isDaemon = true } }
    private var flush: ScheduledFuture<*>? = null

    /**
     * Devuelve false si ya hay otra ResuenApp abierta y se le pasaron [args]: quien llama debe
     * salir. Devuelve true si esta es la primera copia (o si no se pudo hablar con la otra).
     */
    fun acquire(args: Array<String>): Boolean {
        val raf = runCatching { RandomAccessFile(lockFile, "rw") }.getOrNull() ?: return true
        val ch = raf.channel
        val got = runCatching { ch.tryLock() }.getOrNull()
        if (got == null) {
            ch.close()
            // La primera copia puede estar recién arrancando y aún no escribe su puerto.
            repeat(25) {
                if (sendToRunning(args)) return false
                Thread.sleep(200)
            }
            return true
        }
        channel = ch
        lock = got
        startServer()
        receive(args.toList())
        if (IsMac) {
            runCatching {
                Desktop.getDesktop().setOpenFileHandler { e ->
                    _raise.update { it + 1 }
                    enqueue(e.files)
                }
            }
        }
        return true
    }

    /** [block] recibe los archivos abiertos desde fuera, agrupados (ver [enqueue]). */
    fun onFiles(block: (List<File>) -> Unit) {
        handler = block
        synchronized(pending) { if (pending.isNotEmpty()) scheduleFlush() }
    }

    private fun startServer() {
        runCatching {
            val s = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
            server = s
            token = UUID.randomUUID().toString()
            portFile.writeText("${s.localPort}\n$token")
            Thread({
                while (!s.isClosed) {
                    val client = runCatching { s.accept() }.getOrNull() ?: break
                    runCatching {
                        client.use { c ->
                            c.soTimeout = 3_000
                            val lines = c.getInputStream().bufferedReader(Charsets.UTF_8).readLines()
                            if (lines.firstOrNull() == token) {
                                _raise.update { it + 1 }
                                receive(lines.drop(1))
                            }
                        }
                    }
                }
            }, "resuenapp-instance").apply { isDaemon = true }.start()
        }
    }

    private fun sendToRunning(args: Array<String>): Boolean = runCatching {
        val (port, secret) = portFile.readLines().let { it[0].trim().toInt() to it[1].trim() }
        Socket().use { s ->
            s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1_000)
            val out = s.getOutputStream().bufferedWriter(Charsets.UTF_8)
            out.write(secret)
            out.write("\n")
            // Rutas absolutas: la otra copia no comparte la carpeta de trabajo de esta.
            args.forEach { out.write(File(it).absolutePath); out.write("\n") }
            out.flush()
        }
        true
    }.getOrDefault(false)

    private fun receive(args: List<String>) {
        enqueue(args.filter { it.isNotBlank() && !it.startsWith("--") }.map(::File))
    }

    /**
     * El Explorador de Windows abre la app una vez por archivo cuando se eligen varios, casi a
     * la vez: se juntan los que llegan seguidos y se reproducen como una sola cola.
     */
    private fun enqueue(files: List<File>) {
        val audio = files.filter { it.isFile && it.extension.lowercase() in LibraryScanner.EXTENSIONS }
        if (audio.isEmpty()) return
        synchronized(pending) {
            pending += audio
            scheduleFlush()
        }
    }

    private fun scheduleFlush() {
        flush?.cancel(false)
        flush = timer.schedule(Runnable {
            val block = handler ?: return@Runnable
            val batch = synchronized(pending) {
                pending.distinctBy { it.absolutePath }.also { pending.clear() }
            }
            // Varios archivos a la vez: en el orden de sus nombres, como en la carpeta.
            if (batch.isNotEmpty()) block(if (batch.size > 1) batch.sortedBy { it.name.lowercase() } else batch)
        }, 350, TimeUnit.MILLISECONDS)
    }

    /** Muestra [window] delante de las demás, también si estaba minimizada. */
    fun bringToFront(window: java.awt.Frame) {
        if ((window.extendedState and java.awt.Frame.ICONIFIED) != 0) {
            window.extendedState = window.extendedState and java.awt.Frame.ICONIFIED.inv()
        }
        // Windows solo deja pasar al frente a la app que el usuario está usando; fijarla
        // encima un instante y soltarla la trae igual (y deja el "siempre visible" como estaba).
        val onTop = window.isAlwaysOnTop
        window.isAlwaysOnTop = true
        window.toFront()
        window.requestFocus()
        window.isAlwaysOnTop = onTop
    }

    fun release() {
        runCatching { server?.close() }
        runCatching { portFile.delete() }
        runCatching { lock?.release() }
        runCatching { channel?.close() }
    }
}

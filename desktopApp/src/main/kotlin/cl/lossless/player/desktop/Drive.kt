package cl.lossless.player.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cl.lossless.player.drive.DriveAccount
import cl.lossless.player.drive.DriveAuthException
import cl.lossless.player.drive.DriveCache
import cl.lossless.player.drive.DriveClient
import cl.lossless.player.drive.DriveItem
import cl.lossless.player.drive.DriveTokenSource
import cl.lossless.player.drive.GoogleOAuth
import cl.lossless.player.drive.OAuthTokens
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.awt.Desktop
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.util.Properties

/** Descarga en curso de una canción de Drive: [index] de [total] en la carpeta que se reproduce. */
data class DriveDownload(val name: String, val index: Int, val total: Int, val progress: Float)

/**
 * Google Drive en la versión de escritorio. El inicio de sesión abre el navegador y Google vuelve
 * a un puerto local de este equipo (flujo para apps instaladas). Cada canción se descarga entera
 * a la caché antes de sonar: el archivo es idéntico al de Drive, así que se reproduce sin pérdida.
 * El identificador del cliente OAuth lo pone el CI al compilar (resuenapp-drive.properties).
 */
object DesktopDrive {

    /** Extensiones que el motor de escritorio reproduce (las mismas que la biblioteca). */
    val audioExtensions: Set<String> get() = LibraryScanner.EXTENSIONS

    private val oauth: GoogleOAuth? by lazy {
        val props = Properties()
        DesktopDrive::class.java.getResourceAsStream("/resuenapp-drive.properties")?.use { props.load(it) }
        val id = props.getProperty("clientId").orEmpty().trim()
        val secret = props.getProperty("clientSecret").orEmpty().trim()
        if (id.isEmpty()) null else GoogleOAuth(id, secret)
    }

    /** Esta compilación trae el cliente OAuth de Google (si no, Drive no se puede usar). */
    val configured: Boolean get() = oauth != null

    var account by mutableStateOf(AppSettings.driveAccount?.let { DriveAccount(it.first, it.second) })
        private set
    var signingIn by mutableStateOf(false)
        private set
    var download by mutableStateOf<DriveDownload?>(null)
        private set

    val cache = DriveCache(File(AppDirs.data, "drive-cache"))

    private var tokens: OAuthTokens? = null

    private val tokenSource = object : DriveTokenSource {
        @Synchronized
        override fun accessToken(): String {
            tokens?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let { return it.accessToken }
            val refresh = AppSettings.driveRefreshToken ?: throw DriveAuthException("sin sesión de Google")
            val auth = oauth ?: throw DriveAuthException("Google Drive no está configurado")
            val fresh = try {
                auth.refresh(refresh)
            } catch (e: DriveAuthException) {
                forgetSession()
                throw e
            }
            tokens = fresh
            fresh.refreshToken?.let { AppSettings.driveRefreshToken = it }
            return fresh.accessToken
        }

        @Synchronized
        override fun invalidate(token: String) {
            if (tokens?.accessToken == token) tokens = null
        }
    }

    val client: DriveClient by lazy { DriveClient(tokenSource, "ResuenApp/$APP_VERSION (${System.getProperty("os.name")})") }

    /**
     * Abre la página de Google en el navegador y espera (hasta 5 minutos) a que el usuario dé
     * permiso. Lanza una excepción si se cancela o falla.
     */
    suspend fun signIn(lang: Lang): Unit = withContext(Dispatchers.IO) {
        val auth = oauth ?: throw DriveAuthException("Google Drive no está configurado")
        signingIn = true
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        try {
            val redirect = "http://127.0.0.1:${server.address.port}"
            val verifier = auth.newVerifier()
            val state = auth.newState()
            val reply = CompletableDeferred<Map<String, String>>()
            server.createContext("/") { exchange ->
                val params = parseQuery(exchange.requestURI.rawQuery)
                val known = "code" in params || "error" in params
                val ok = params["code"] != null && params["state"] == state
                val html = if (known) browserPage(lang, ok) else ""
                val bytes = html.toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                exchange.sendResponseHeaders(if (known) 200 else 404, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
                exchange.close()
                if (known) reply.complete(params)
            }
            server.start()
            Desktop.getDesktop().browse(URI(auth.authorizationUrl(redirect, verifier, state)))
            val params = withTimeout(5 * 60_000L) { reply.await() }
            if (params["state"] != state) throw DriveAuthException("respuesta de Google no válida")
            val code = params["code"] ?: throw DriveAuthException(params["error"] ?: "acceso cancelado")
            val fresh = auth.exchangeCode(code, verifier, redirect)
            synchronized(tokenSource) { tokens = fresh }
            fresh.refreshToken?.let { AppSettings.driveRefreshToken = it }
            val who = client.account()
            AppSettings.driveAccount = who.name to who.email
            account = who
        } finally {
            server.stop(0)
            signingIn = false
        }
    }

    /** Cierra la sesión y le quita el permiso a la app en la cuenta de Google. */
    suspend fun signOut(): Unit = withContext(Dispatchers.IO) {
        cancelDownloads()
        AppSettings.driveRefreshToken?.let(GoogleOAuth::revoke)
        forgetSession()
    }

    private fun forgetSession() {
        synchronized(tokenSource) { tokens = null }
        AppSettings.driveRefreshToken = null
        AppSettings.driveAccount = null
        account = null
    }

    private var job: Job? = null

    fun cancelDownloads() {
        job?.cancel()
        job = null
        download = null
    }

    /**
     * Reproduce [files] desde [start]: descarga la primera, empieza a sonar y va sumando las
     * siguientes a la cola a medida que terminan de bajar. Se detiene si el usuario pone otra cosa.
     */
    fun play(scope: CoroutineScope, files: List<DriveItem>, start: Int, folderName: String, player: Player, onError: (Throwable) -> Unit) {
        cancelDownloads()
        val pending = files.drop(start)
        if (pending.isEmpty()) return
        job = scope.launch(Dispatchers.IO) {
            var firstPath: String? = null
            try {
                pending.forEachIndexed { i, item ->
                    download = DriveDownload(item.name, i + 1, pending.size, 0f)
                    val file = runInterruptible {
                        cache.fetch(item, client) { p -> download = DriveDownload(item.name, i + 1, pending.size, p) }
                    }
                    val track = toTrack(file, item, folderName)
                    withContext(Dispatchers.Main) {
                        if (firstPath == null) {
                            firstPath = track.path
                            player.playQueue(listOf(track), 0)
                        } else if (player.state.value.queue.firstOrNull()?.path == firstPath) {
                            player.append(listOf(track))
                        } else {
                            // El usuario puso otra música: no se mezcla con lo que suena ahora.
                            throw CancellationException()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            } finally {
                download = null
            }
        }
    }

    /** Etiquetas del archivo descargado; sin etiquetas, el nombre que tiene en Drive. */
    private fun toTrack(file: File, item: DriveItem, folderName: String): Track {
        val track = LibraryScanner.readTrack(file.toPath())
        return track.copy(
            title = track.title.takeUnless { it == file.nameWithoutExtension } ?: item.name.substringBeforeLast('.'),
            album = track.album.ifBlank { folderName },
        )
    }

    private fun parseQuery(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').filter { '=' in it }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }

    private fun browserPage(lang: Lang, ok: Boolean): String {
        val title = (if (ok) S.driveBrowserOk else S.driveBrowserFailed).get(lang)
        val body = (if (ok) S.driveBrowserOkBody else S.driveBrowserFailedBody).get(lang)
        return """<!doctype html><html><head><meta charset="utf-8"><title>ResuenApp</title>
            <style>body{font-family:system-ui,sans-serif;background:#0b0f14;color:#e6edf3;display:flex;
            align-items:center;justify-content:center;height:100vh;margin:0}div{text-align:center}
            h1{color:#22d3ee;font-size:22px}</style></head>
            <body><div><h1>$title</h1><p>$body</p></div></body></html>"""
    }
}

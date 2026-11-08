package cl.lossless.player.drive

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** Un archivo o carpeta de Google Drive, con lo justo para navegar y descargar. */
data class DriveItem(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val sizeBytes: Long = 0L,
    /** MD5 del contenido (Drive lo da para archivos binarios); sirve para saber si cambió. */
    val md5: String = "",
    val modifiedTime: String = "",
) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()

    /** Cambia cuando cambia el contenido del archivo en Drive. */
    val version: String get() = md5.ifEmpty { modifiedTime.ifEmpty { sizeBytes.toString() } }
}

/** Cuenta con la que se inició sesión ("Conectado como …"). */
data class DriveAccount(val name: String, val email: String)

/**
 * Entrega tokens de acceso de Google con el permiso de solo lectura de Drive. Cada plataforma
 * tiene el suyo: Android usa el inicio de sesión de Google Play Services y el escritorio el flujo
 * OAuth con el navegador (ver GoogleOAuth).
 */
interface DriveTokenSource {
    /** Bloqueante. Lanza [DriveAuthException] si hay que volver a iniciar sesión. */
    fun accessToken(): String

    /** Google rechazó [token] (venció o se revocó): el próximo [accessToken] debe pedir otro. */
    fun invalidate(token: String)
}

/** No hay sesión de Google válida: el usuario tiene que conectar Drive de nuevo. */
class DriveAuthException(message: String) : IOException(message)

/**
 * Cliente mínimo de la API de Google Drive v3: lista carpetas y descarga archivos tal cual
 * (byte a byte, sin recomprimir), para reproducirlos sin pérdida desde la caché local.
 * No depende de Android: lo usan la app Android y la de escritorio.
 */
class DriveClient(
    private val tokens: DriveTokenSource,
    private val userAgent: String,
    private val transport: Transport = HttpTransport,
) {

    data class Response(val code: Int, val body: String)

    fun interface Transport {
        fun get(url: String, headers: Map<String, String>): Response
    }

    /** Carpetas y archivos de audio dentro de [folderId] ([ROOT] = Mi unidad, [SHARED] = Compartido conmigo). */
    fun list(folderId: String, audioExtensions: Set<String>): List<DriveItem> {
        val q = if (folderId == SHARED) "sharedWithMe = true and trashed = false"
        else "'${folderId.replace("'", "\\'")}' in parents and trashed = false"
        val result = mutableListOf<DriveItem>()
        var pageToken: String? = null
        do {
            val params = buildMap {
                put("q", q)
                put("fields", "nextPageToken,files(id,name,mimeType,size,md5Checksum,modifiedTime,shortcutDetails)")
                put("pageSize", "1000")
                put("supportsAllDrives", "true")
                put("includeItemsFromAllDrives", "true")
                if (folderId != SHARED) put("orderBy", "folder,name_natural")
                pageToken?.let { put("pageToken", it) }
            }
            val json = JSONObject(getJson("$API/files?${encode(params)}"))
            val files = json.optJSONArray("files")
            if (files != null) for (i in 0 until files.length()) {
                parseItem(files.getJSONObject(i))?.let { result += it }
            }
            pageToken = json.optString("nextPageToken").ifEmpty { null }
        } while (pageToken != null)
        return sort(result.filter { it.isFolder || it.extension in audioExtensions })
    }

    /** Todos los archivos de audio de [folder] y sus subcarpetas, en orden de carpeta y nombre. */
    fun listRecursive(folder: DriveItem, audioExtensions: Set<String>, limit: Int = 2000): List<DriveItem> {
        val result = mutableListOf<DriveItem>()
        val pending = ArrayDeque(listOf(folder.id))
        val seen = mutableSetOf<String>()
        while (pending.isNotEmpty() && result.size < limit) {
            val id = pending.removeFirst()
            if (!seen.add(id)) continue
            val children = list(id, audioExtensions)
            result += children.filterNot { it.isFolder }
            pending.addAll(0, children.filter { it.isFolder }.map { it.id })
        }
        return result.take(limit)
    }

    fun account(): DriveAccount {
        val user = JSONObject(getJson("$API/about?fields=user(displayName,emailAddress)")).optJSONObject("user")
        return DriveAccount(user?.optString("displayName").orEmpty(), user?.optString("emailAddress").orEmpty())
    }

    /**
     * Descarga el contenido de [item] en [target] informando el avance (0..1). Escribe primero
     * a un .part y lo renombra al terminar: nunca queda un archivo a medias con el nombre final.
     */
    fun download(item: DriveItem, target: File, onProgress: (Float) -> Unit = {}) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        val url = "$API/files/${item.id}?alt=media&supportsAllDrives=true"
        val connection = withToken { token -> openMedia(url, token) }
        try {
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: item.sizeBytes
            connection.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buffer = ByteArray(128 * 1024)
                    var done = 0L
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw IOException("cancelado")
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        } finally {
            connection.disconnect()
        }
        if (item.sizeBytes > 0 && tmp.length() != item.sizeBytes) {
            tmp.delete()
            throw IOException("descarga incompleta: ${item.name}")
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) throw IOException("no se pudo guardar ${target.name}")
    }

    private fun openMedia(url: String, token: String): HttpURLConnection {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Authorization", "Bearer $token")
        val code = connection.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) throw Unauthorized()
            throw IOException("Google Drive respondió HTTP $code")
        }
        return connection
    }

    private fun getJson(url: String): String = withToken { token ->
        val response = transport.get(url, mapOf("Authorization" to "Bearer $token", "User-Agent" to userAgent))
        when (response.code) {
            HttpURLConnection.HTTP_OK -> response.body
            HttpURLConnection.HTTP_UNAUTHORIZED -> throw Unauthorized()
            else -> throw IOException("Google Drive respondió HTTP ${response.code}: ${errorMessage(response.body)}")
        }
    }

    /** Corre [block] con un token; si Google lo rechaza, lo descarta y reintenta una vez con otro. */
    private fun <T> withToken(block: (String) -> T): T {
        val first = tokens.accessToken()
        try {
            return block(first)
        } catch (e: Unauthorized) {
            tokens.invalidate(first)
        }
        val second = tokens.accessToken()
        try {
            return block(second)
        } catch (e: Unauthorized) {
            tokens.invalidate(second)
            throw DriveAuthException("Google rechazó la sesión")
        }
    }

    private class Unauthorized : IOException("HTTP 401")

    companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val SCOPE = "https://www.googleapis.com/auth/drive.readonly"

        /** Carpeta raíz de "Mi unidad". */
        const val ROOT = "root"

        /** Seudocarpeta "Compartido conmigo". */
        const val SHARED = "sharedWithMe"

        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val SHORTCUT_MIME = "application/vnd.google-apps.shortcut"

        /** Un acceso directo se trata como lo que apunta (la carpeta o el archivo de destino). */
        internal fun parseItem(o: JSONObject): DriveItem? {
            val mime = o.optString("mimeType")
            val name = o.optString("name")
            if (mime == SHORTCUT_MIME) {
                val target = o.optJSONObject("shortcutDetails") ?: return null
                val targetId = target.optString("targetId").ifEmpty { return null }
                return DriveItem(targetId, name, isFolder = target.optString("targetMimeType") == FOLDER_MIME)
            }
            // Documentos, hojas de cálculo, etc. de Google no tienen contenido descargable.
            if (mime.startsWith("application/vnd.google-apps.") && mime != FOLDER_MIME) return null
            return DriveItem(
                id = o.optString("id").ifEmpty { return null },
                name = name,
                isFolder = mime == FOLDER_MIME,
                sizeBytes = o.optString("size").toLongOrNull() ?: 0L,
                md5 = o.optString("md5Checksum"),
                modifiedTime = o.optString("modifiedTime"),
            )
        }

        /** Carpetas primero; luego por nombre con números en orden natural ("2 …" antes de "10 …"). */
        internal fun sort(items: List<DriveItem>): List<DriveItem> =
            items.sortedWith(compareBy<DriveItem> { !it.isFolder }.then { a, b -> naturalCompare(a.name, b.name) })

        internal fun naturalCompare(a: String, b: String): Int {
            val re = Regex("\\d+|\\D+")
            val pa = re.findAll(a.lowercase()).map { it.value }.toList()
            val pb = re.findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(pa.size, pb.size)) {
                val x = pa[i]
                val y = pb[i]
                val c = if (x[0].isDigit() && y[0].isDigit()) {
                    (x.toBigInteger()).compareTo(y.toBigInteger())
                } else x.compareTo(y)
                if (c != 0) return c
            }
            return pa.size.compareTo(pb.size)
        }

        private fun errorMessage(body: String): String =
            runCatching { JSONObject(body).getJSONObject("error").optString("message") }.getOrNull() ?: body.take(200)

        private fun encode(params: Map<String, String>) =
            params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
    }
}

/** Transporte real con HttpURLConnection (Android y escritorio lo traen sin dependencias). */
object HttpTransport : DriveClient.Transport {
    override fun get(url: String, headers: Map<String, String>): DriveClient.Response {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return DriveClient.Response(code, body)
        } finally {
            connection.disconnect()
        }
    }
}

package cl.lossless.player.update

import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CancellationException

/** Una versión publicada más nueva que la instalada, con el archivo que le toca a este equipo. */
data class AvailableUpdate(
    val version: String,
    val notes: String,
    val assetName: String,
    val assetUrl: String,
    val sizeBytes: Long,
)

/**
 * Busca versiones nuevas en el repositorio público de instaladores (EdoGris/ResuenApp-releases),
 * donde el CI publica cada compilación de `experimental` con la etiqueta `exp-1.2.N`.
 * Lo usan la app Android y la de escritorio; no depende de Android.
 */
class UpdateChecker(
    private val userAgent: String,
    private val repo: String = REPO,
    private val fetch: (String) -> String = { url -> httpGetText(url, userAgent) },
) {

    /**
     * La versión más nueva de [channel] ("exp" o "v") que sea mayor que [current] y traiga un
     * archivo que cumpla [pickAsset], o null si ya está al día. Bloqueante; lanza IOException
     * sin conexión.
     */
    fun check(current: String, channel: String, pickAsset: (String) -> Boolean): AvailableUpdate? {
        val releases = JSONArray(fetch("https://api.github.com/repos/$repo/releases?per_page=30"))
        val prefix = "$channel-"
        var best: AvailableUpdate? = null
        for (i in 0 until releases.length()) {
            val r = releases.getJSONObject(i)
            if (r.optBoolean("draft")) continue
            val tag = r.optString("tag_name")
            if (!tag.startsWith(prefix)) continue
            val version = tag.removePrefix(prefix)
            if (compareVersions(version, current) <= 0) continue
            if (best != null && compareVersions(version, best.version) <= 0) continue
            val assets = r.optJSONArray("assets") ?: continue
            val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { pickAsset(it.optString("name")) } ?: continue
            best = AvailableUpdate(
                version = version,
                notes = r.optString("body").trim(),
                assetName = asset.optString("name"),
                assetUrl = asset.optString("browser_download_url"),
                sizeBytes = asset.optLong("size"),
            )
        }
        return best
    }

    /**
     * Descarga [update] en [target] informando el avance (0..1). Bloqueante.
     *
     * Escribe en `<target>.part` y, si ese archivo ya existe (una descarga anterior cortada, aunque
     * la app se haya cerrado), pide solo lo que falta con `Range`. Si la conexión se corta a medio
     * camino reintenta sola hasta [retries] veces, siempre continuando donde quedó. [isActive] se
     * consulta en cada bloque: cuando devuelve false la descarga se detiene con
     * CancellationException y deja el `.part` para reanudar más tarde.
     */
    fun download(
        update: AvailableUpdate,
        target: File,
        retries: Int = 3,
        retryDelayMs: Long = 2_000,
        isActive: () -> Boolean = { true },
        onProgress: (Float) -> Unit,
    ) {
        target.parentFile?.mkdirs()
        val expected = update.sizeBytes
        // Ya descargado antes (por ejemplo, se cerró el instalador sin instalar).
        if (expected > 0 && target.length() == expected) {
            onProgress(1f)
            return
        }
        val tmp = File(target.parentFile, target.name + ".part")
        if (expected > 0 && tmp.length() > expected) tmp.delete()
        var attempt = 0
        while (true) {
            try {
                downloadOnce(update.assetUrl, tmp, expected, isActive, onProgress)
                break
            } catch (e: IOException) {
                if (attempt >= retries || !isActive()) throw e
                attempt++
                // Espera antes de reintentar, sin dejar de atender una cancelación.
                val until = System.currentTimeMillis() + retryDelayMs * attempt
                while (System.currentTimeMillis() < until) {
                    if (!isActive()) throw CancellationException("descarga cancelada")
                    Thread.sleep(100)
                }
            }
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) throw IOException("no se pudo guardar ${target.name}")
    }

    /** Un intento: continúa [tmp] desde su largo actual. Lanza IOException si queda incompleto. */
    private fun downloadOnce(
        url: String,
        tmp: File,
        expected: Long,
        isActive: () -> Boolean,
        onProgress: (Float) -> Unit,
    ) {
        var offset = tmp.length()
        if (expected > 0 && offset == expected) return
        val connection = openRaw(url, userAgent, rangeFrom = offset.takeIf { it > 0 })
        try {
            when (connection.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> Unit
                HttpURLConnection.HTTP_OK -> offset = 0 // El servidor ignoró Range: empieza de cero.
                416 -> { // Range fuera de rango: lo guardado no sirve.
                    tmp.delete()
                    throw IOException("HTTP 416")
                }
                else -> throw IOException("HTTP ${connection.responseCode}")
            }
            val total = expected.takeIf { it > 0 }
                ?: connection.contentLengthLong.takeIf { it > 0 }?.plus(offset)
                ?: -1L
            var done = offset
            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
            connection.inputStream.use { input ->
                java.io.FileOutputStream(tmp, offset > 0).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (!isActive()) throw CancellationException("descarga cancelada")
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            if (total > 0 && tmp.length() < total) throw IOException("descarga incompleta")
            if (expected > 0 && tmp.length() != expected) {
                tmp.delete()
                throw IOException("tamaño inesperado")
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val REPO = "EdoGris/ResuenApp-releases"

        /** Compara "1.2.57" con "1.2.9" número por número; ignora sufijos como "-exp". */
        fun compareVersions(a: String, b: String): Int {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val pa = parts(a)
            val pb = parts(b)
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }

        private fun openRaw(url: String, userAgent: String, rangeFrom: Long? = null): HttpURLConnection {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
            if (rangeFrom != null) connection.setRequestProperty("Range", "bytes=$rangeFrom-")
            return connection
        }

        private fun open(url: String, userAgent: String): HttpURLConnection {
            val connection = openRaw(url, userAgent)
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                throw IOException("HTTP $code")
            }
            return connection
        }

        private fun httpGetText(url: String, userAgent: String): String {
            val connection = open(url, userAgent)
            return try {
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
    }
}

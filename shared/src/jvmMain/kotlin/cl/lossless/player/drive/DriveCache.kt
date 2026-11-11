package cl.lossless.player.drive

import java.io.File

/**
 * Copias locales de los archivos de Drive. Cada uno se guarda con su id y versión en el nombre,
 * así un archivo que cambió en Drive se vuelve a descargar. Si la caché pasa de [maxBytes], se
 * borran primero los menos usados.
 */
class DriveCache(val dir: File, var maxBytes: Long = DEFAULT_MAX_BYTES) {

    fun fileFor(item: DriveItem): File {
        val version = item.version.filter { it.isLetterOrDigit() }.take(12)
        val ext = item.extension.filter { it.isLetterOrDigit() }.ifEmpty { "bin" }
        return File(dir, "${item.id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }}_$version.$ext")
    }

    /** La copia local de [item] si ya está completa, o null. */
    fun cached(item: DriveItem): File? =
        fileFor(item).takeIf { it.isFile && (item.sizeBytes <= 0 || it.length() == item.sizeBytes) }

    /** Devuelve la copia local de [item], descargándola si hace falta. Bloqueante. */
    fun fetch(item: DriveItem, client: DriveClient, onProgress: (Float) -> Unit = {}): File {
        cached(item)?.let {
            it.setLastModified(System.currentTimeMillis())
            onProgress(1f)
            return it
        }
        dir.mkdirs()
        val target = fileFor(item)
        client.download(item, target, onProgress)
        trim(keep = setOf(target))
        return target
    }

    fun sizeBytes(): Long = files().sumOf { it.length() }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /** Borra los archivos menos usados hasta quedar bajo [maxBytes], sin tocar [keep]. */
    fun trim(keep: Set<File> = emptySet()) {
        var total = sizeBytes()
        if (total <= maxBytes) return
        for (file in files().sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            if (file in keep) continue
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun files(): List<File> = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }.orEmpty()

    companion object {
        const val DEFAULT_MAX_BYTES = 4L * 1024 * 1024 * 1024
    }
}

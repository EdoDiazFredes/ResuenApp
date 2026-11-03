package cl.lossless.player.covers

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Una carátula asociada a un álbum. stamp cambia cada vez que se reemplaza la imagen. */
data class CoverRef(val file: File, val stamp: Long)

/**
 * Caché interna de carátulas de la app: un archivo por álbum (carpeta + nombre de álbum).
 * Es la asociación principal; cover.jpg en la carpeta es opcional.
 */
class CoverStore(context: Context) {

    private val dir = File(context.filesDir, DIR).apply { mkdirs() }

    private fun fileFor(albumKey: String) = File(dir, sha1(albumKey) + ".img")

    fun get(albumKey: String): CoverRef? =
        fileFor(albumKey).takeIf { it.isFile && it.length() > 0 }?.let { CoverRef(it, it.lastModified()) }

    fun save(albumKey: String, bytes: ByteArray): CoverRef {
        val target = fileFor(albumKey)
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
        return CoverRef(target, target.lastModified())
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        /** Carpeta dentro de filesDir; [CoverProvider] la comparte con Android Auto. */
        const val DIR = "covers"
    }
}

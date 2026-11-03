package cl.lossless.player.covers

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Entrega las carátulas de la caché ([CoverStore]) a otras apps, solo lectura. Android Auto
 * corre en otro proceso y no puede abrir rutas file:// de la app; con content:// sí.
 */
class CoverProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Solo lectura")
        val name = uri.lastPathSegment?.takeIf { NAME.matches(it) } ?: throw FileNotFoundException(uri.toString())
        val file = File(File(context!!.filesDir, CoverStore.DIR), name)
        if (!file.isFile) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/*"

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        /** Solo los archivos que crea [CoverStore]: sha1 + ".img". */
        private val NAME = Regex("[0-9a-f]{40}\\.img")

        private fun authority(context: Context) = "${context.packageName}.covers"

        /** URI pública de la carátula; "v" cambia al reemplazarla para no mostrar la vieja. */
        fun uriFor(context: Context, ref: CoverRef): Uri = Uri.Builder()
            .scheme("content")
            .authority(authority(context))
            .appendPath(ref.file.name)
            .appendQueryParameter("v", ref.stamp.toString())
            .build()

        /** Convierte una carátula file:// de la caché en su URI content://; otras URIs no se tocan. */
        fun publicUri(context: Context, uri: Uri?): Uri? {
            if (uri?.scheme != "file") return uri
            val file = File(uri.path ?: return uri)
            if (file.parentFile?.name != CoverStore.DIR || !NAME.matches(file.name)) return uri
            return uriFor(context, CoverRef(file, file.lastModified()))
        }
    }
}

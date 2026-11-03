package cl.lossless.player.library

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import cl.lossless.player.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Track(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val trackNumber: Int,
    val durationMs: Long,
    val mimeType: String,
    /** Identifica la carpeta que contiene el archivo (volumen + ruta). */
    val folderKey: String,
    val folderName: String,
    /** Id SAF de la carpeta; permite leer/escribir cover.jpg si está dentro de la carpeta concedida. */
    val folderDocId: String?,
    /** Bits por segundo (0 si se desconoce). */
    val bitrate: Int = 0,
    /** Profundidad de bits (0 si se desconoce o el formato es con pérdida). */
    val bitsPerSample: Int = 0,
    /** Frecuencia de muestreo en Hz (0 si se desconoce). */
    val sampleRate: Int = 0,
    /** Nombre del archivo (ej. "01 Bombtrack.flac"); sirve para encontrar su .lrc. */
    val fileName: String = "",
    /** Última modificación del archivo en milisegundos (0 si se desconoce). */
    val dateModified: Long = 0L,
) {
    val containerLabel: String
        get() = AudioFormats.containerLabel(mimeType)

    /** "FLAC 24-bit", "MP3 320kbps"… para las listas. */
    val qualityLabel: String
        get() = listOfNotNull(containerLabel, resolutionLabel).joinToString(" ")

    /** "FLAC • 24-bit • 96 kHz" para el reproductor. */
    val qualityDetails: String
        get() = listOfNotNull(
            containerLabel,
            resolutionLabel,
            sampleRate.takeIf { it > 0 }?.let { "%s kHz".format(formatKhz(it)) },
        ).joinToString(" • ")

    private val resolutionLabel: String?
        get() = when {
            bitsPerSample > 0 && containerLabel != "MP3" -> "$bitsPerSample-bit"
            bitrate > 0 -> "${bitrate / 1000}kbps"
            else -> null
        }

    private fun formatKhz(hz: Int): String =
        if (hz % 1000 == 0) "${hz / 1000}" else "%.1f".format(hz / 1000f)

    fun toMediaItem(artworkUri: Uri? = null): MediaItem = MediaItem.Builder()
        .setMediaId(uri.toString())   // el servicio reconstruye la URI desde aquí
        .setUri(uri)
        .setMimeType(mimeType)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setAlbumArtist(albumArtist)
                .setArtworkUri(artworkUri)
                // Calidad del archivo ("FLAC • 24-bit • 96 kHz"); Android Auto la muestra bajo el artista.
                .setDescription(qualityDetails.ifEmpty { null })
                .build()
        )
        .build()
}

class MusicRepository(private val context: Context) {

    /** Textos visibles para etiquetas que faltan (traducidos). */
    private val untitled get() = context.getString(R.string.library_untitled)
    private val unknownArtist get() = context.getString(R.string.library_unknown_artist)

    private val supportedMimes = AudioFormats.supportedMimes
    private val supportedExtensions = AudioFormats.extensions

    /**
     * Sin carpeta: toda la música indexada del teléfono.
     * Con carpeta: primero se consulta MediaStore filtrando por esa ruta (rápido). Si la carpeta
     * no está indexada (p. ej. algunos pendrives USB), se recorre con el selector de documentos.
     */
    suspend fun loadTracks(location: LibraryLocation?): List<Track> = withContext(Dispatchers.IO) {
        if (location == null) return@withContext queryMediaStore(null, "")
        val volume = location.mediaStoreVolume
        val indexed = if (volume != null) queryMediaStore(volume, location.relativePath) else emptyList()
        indexed.ifEmpty { scanDocumentTree(location) }
    }

    // ---------- MediaStore ----------

    private fun queryMediaStore(volume: String?, relativePath: String): List<Track> {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ARTIST,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.VOLUME_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.BITRATE,
            MediaStore.Audio.Media.BITS_PER_SAMPLE,
            MediaStore.Audio.Media.SAMPLERATE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATE_MODIFIED,
        )
        val conditions = mutableListOf(
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            "${MediaStore.Audio.Media.MIME_TYPE} IN (${supportedMimes.joinToString(",") { "?" }})",
        )
        val args = supportedMimes.toMutableList()
        if (volume != null) {
            conditions += "${MediaStore.Audio.Media.VOLUME_NAME} = ?"
            args += volume
        }
        if (relativePath.isNotEmpty()) {
            conditions += "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? ESCAPE '\\'"
            args += escapeLike(relativePath) + "%"
        }
        val sort = "${MediaStore.Audio.Media.RELATIVE_PATH} ASC, ${MediaStore.Audio.Media.TRACK} ASC"

        val result = mutableListOf<Track>()
        context.contentResolver.query(
            collection, projection, conditions.joinToString(" AND "), args.toTypedArray(), sort
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumArtistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ARTIST)
            val trackCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val volCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.VOLUME_NAME)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            val bitrateCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.BITRATE)
            val bitsCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.BITS_PER_SAMPLE)
            val rateCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SAMPLERATE)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val modifiedCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val vol = c.getString(volCol) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                val path = c.getString(pathCol) ?: ""
                result += Track(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    title = c.getString(titleCol) ?: untitled,
                    artist = cleanTag(c.getString(artistCol)),
                    album = c.getString(albumCol) ?: "",
                    albumArtist = cleanTag(c.getString(albumArtistCol), fallback = ""),
                    trackNumber = c.getInt(trackCol),
                    durationMs = c.getLong(durCol),
                    mimeType = c.getString(mimeCol) ?: "",
                    folderKey = "$vol:$path",
                    folderName = path.trimEnd('/').substringAfterLast('/').ifEmpty { vol },
                    folderDocId = LibraryLocation.docIdFor(vol, path),
                    bitrate = c.getInt(bitrateCol),
                    bitsPerSample = c.getInt(bitsCol),
                    sampleRate = c.getInt(rateCol),
                    fileName = c.getString(nameCol) ?: "",
                    // MediaStore lo guarda en segundos
                    dateModified = c.getLong(modifiedCol) * 1000L,
                )
            }
        }
        return result
    }

    // ---------- Recorrido con Storage Access Framework (carpetas no indexadas) ----------

    private fun scanDocumentTree(location: LibraryLocation): List<Track> {
        val result = mutableListOf<Track>()
        val pending = ArrayDeque<String>().apply { add(location.rootDocId) }
        val retriever = MediaMetadataRetriever()
        try {
            while (pending.isNotEmpty()) {
                val folderId = pending.removeFirst()
                context.contentResolver.query(
                    location.childrenUri(folderId),
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    ),
                    null, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val docId = c.getString(0) ?: continue
                        val name = c.getString(1) ?: ""
                        val mime = c.getString(2) ?: ""
                        val modified = if (c.isNull(3)) 0L else c.getLong(3)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            pending.add(docId)
                            continue
                        }
                        val ext = name.substringAfterLast('.', "").lowercase()
                        val normalizedMime = supportedExtensions[ext]
                            ?: mime.takeIf { it in supportedMimes } ?: continue
                        result += readDocumentTrack(
                            retriever, location, folderId, docId, name, normalizedMime, modified
                        )
                    }
                }
            }
        } finally {
            retriever.release()
        }
        return result.sortedWith(compareBy<Track>({ it.folderKey }, { it.trackNumber }, { it.title }))
    }

    private fun readDocumentTrack(
        retriever: MediaMetadataRetriever,
        location: LibraryLocation,
        folderId: String,
        docId: String,
        name: String,
        mime: String,
        modified: Long,
    ): Track {
        val uri = DocumentsContract.buildDocumentUriUsingTree(location.treeUri, docId)
        val meta = runCatching { retriever.setDataSource(context, uri); true }.getOrDefault(false)
        fun tag(key: Int) = if (meta) retriever.extractMetadata(key) else null
        return Track(
            id = docId.hashCode().toLong(),
            uri = uri,
            title = tag(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: name.substringBeforeLast('.'),
            artist = cleanTag(tag(MediaMetadataRetriever.METADATA_KEY_ARTIST)),
            album = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "",
            albumArtist = cleanTag(tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST), fallback = ""),
            trackNumber = tag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0,
            durationMs = tag(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
            mimeType = mime,
            folderKey = "doc:$folderId",
            folderName = folderId.substringAfter(':').trimEnd('/').substringAfterLast('/')
                .ifEmpty { folderId },
            folderDocId = folderId,
            bitrate = tag(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0,
            bitsPerSample = tag(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull() ?: 0,
            sampleRate = tag(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0,
            fileName = name,
            dateModified = modified,
        )
    }

    private fun cleanTag(value: String?, fallback: String = unknownArtist): String =
        value?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: fallback

    private fun escapeLike(s: String) =
        s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}

package cl.lossless.player.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Una pista de la cola, con lo necesario para volver a mostrarla y reproducirla. */
data class SavedEntry(
    val mediaId: String,
    val mimeType: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val artworkUri: String? = null,
    /** Calidad del archivo (ver [cl.lossless.player.library.Track.qualityDetails]). */
    val description: String? = null,
)

/** Cola y posición de la última reproducción. */
data class SavedPlayback(
    val entries: List<SavedEntry>,
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
)

/** Formato JSON de la cola guardada (aparte para poder probarlo sin Android). */
object LastPlaybackJson {

    fun encodeQueue(entries: List<SavedEntry>): String {
        val array = JSONArray()
        entries.forEach { e ->
            array.put(
                JSONObject()
                    .put("id", e.mediaId)
                    .putOpt("mime", e.mimeType)
                    .putOpt("title", e.title)
                    .putOpt("artist", e.artist)
                    .putOpt("album", e.album)
                    .putOpt("albumArtist", e.albumArtist)
                    .putOpt("art", e.artworkUri)
                    .putOpt("desc", e.description)
            )
        }
        return JSONObject().put("v", 1).put("queue", array).toString()
    }

    fun decodeQueue(text: String): List<SavedEntry> {
        val array = runCatching { JSONObject(text).optJSONArray("queue") }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            SavedEntry(
                mediaId = id,
                mimeType = o.str("mime"),
                title = o.str("title"),
                artist = o.str("artist"),
                album = o.str("album"),
                albumArtist = o.str("albumArtist"),
                artworkUri = o.str("art"),
                description = o.str("desc"),
            )
        }
    }

    private fun JSONObject.str(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null
}

/**
 * Guarda la última reproducción para retomarla al volver a abrir la app. La cola (que puede
 * ser larga) va a un archivo y solo se reescribe cuando cambia; la pista actual y la posición,
 * que cambian seguido, van a SharedPreferences.
 */
class LastPlaybackStore(context: Context) {
    private val queueFile = File(context.filesDir, "last_queue.json")
    private val prefs = context.getSharedPreferences("last_playback", Context.MODE_PRIVATE)

    fun saveQueue(entries: List<SavedEntry>) {
        if (entries.isEmpty()) {
            queueFile.delete()
            return
        }
        val tmp = File(queueFile.path + ".tmp")
        tmp.writeText(LastPlaybackJson.encodeQueue(entries))
        if (!tmp.renameTo(queueFile)) {
            queueFile.delete()
            tmp.renameTo(queueFile)
        }
    }

    fun savePosition(index: Int, positionMs: Long, shuffle: Boolean, repeatMode: Int, now: Boolean = false) {
        val edit = prefs.edit()
            .putInt(KEY_INDEX, index)
            .putLong(KEY_POSITION, positionMs)
            .putBoolean(KEY_SHUFFLE, shuffle)
            .putInt(KEY_REPEAT, repeatMode)
        if (now) edit.commit() else edit.apply()
    }

    fun load(): SavedPlayback? {
        val entries = runCatching { LastPlaybackJson.decodeQueue(queueFile.readText()) }.getOrNull()
        if (entries.isNullOrEmpty()) return null
        return SavedPlayback(
            entries = entries,
            index = prefs.getInt(KEY_INDEX, 0).coerceIn(0, entries.lastIndex),
            positionMs = prefs.getLong(KEY_POSITION, 0L).coerceAtLeast(0L),
            shuffle = prefs.getBoolean(KEY_SHUFFLE, false),
            repeatMode = prefs.getInt(KEY_REPEAT, Player.REPEAT_MODE_OFF),
        )
    }

    private companion object {
        const val KEY_INDEX = "index"
        const val KEY_POSITION = "position_ms"
        const val KEY_SHUFFLE = "shuffle"
        const val KEY_REPEAT = "repeat_mode"
    }
}

fun MediaItem.toSavedEntry(): SavedEntry = SavedEntry(
    mediaId = mediaId,
    mimeType = localConfiguration?.mimeType,
    title = mediaMetadata.title?.toString(),
    artist = mediaMetadata.artist?.toString(),
    album = mediaMetadata.albumTitle?.toString(),
    albumArtist = mediaMetadata.albumArtist?.toString(),
    artworkUri = mediaMetadata.artworkUri?.toString(),
    description = mediaMetadata.description?.toString(),
)

fun SavedEntry.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(mediaId)
    .setUri(mediaId)
    .setMimeType(mimeType)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setAlbumArtist(albumArtist)
            .setArtworkUri(artworkUri?.let(Uri::parse))
            .setDescription(description)
            .build()
    )
    .build()

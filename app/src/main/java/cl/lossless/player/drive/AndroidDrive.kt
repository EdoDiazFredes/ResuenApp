package cl.lossless.player.drive

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import cl.lossless.player.library.AudioFormats
import cl.lossless.player.library.Track
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/** Sesión de Drive recordada entre aperturas de la app (el token lo guarda Google Play Services). */
class DrivePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("drive", Context.MODE_PRIVATE)

    var account: DriveAccount?
        get() = prefs.getString("email", null)?.let { DriveAccount(prefs.getString("name", "").orEmpty(), it) }
        set(value) {
            prefs.edit().apply {
                if (value == null) { remove("name"); remove("email") }
                else { putString("name", value.name); putString("email", value.email) }
            }.apply()
        }
}

/**
 * Tokens de Drive con el inicio de sesión de Google Play Services (AuthorizationClient): no hace
 * falta guardar contraseñas ni refresh tokens en la app. El cliente OAuth de tipo Android se
 * reconoce por el paquete y la huella SHA-1 de la firma, sin identificador en el código.
 */
class AndroidDriveTokens(private val context: Context) : DriveTokenSource {

    override fun accessToken(): String {
        val result = try {
            Tasks.await(Identity.getAuthorizationClient(context).authorize(request()), 30, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw IOException(e.cause?.message ?: "Google Play Services no respondió", e)
        }
        // Pide mostrar la pantalla de Google: el permiso se quitó o venció.
        if (result.hasResolution()) throw DriveAuthException("hay que volver a conectar Drive")
        return result.accessToken ?: throw DriveAuthException("Google no entregó un token")
    }

    override fun invalidate(token: String) {
        runCatching { GoogleAuthUtil.clearToken(context, token) }
    }

    companion object {
        fun request(): AuthorizationRequest = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DriveClient.SCOPE)))
            .build()
    }
}

object AndroidDrive {
    /** Lo que ExoPlayer reproduce en Android (los mismos formatos que la biblioteca). */
    val audioExtensions: Set<String> = AudioFormats.extensions.keys

    fun cache(context: Context) = DriveCache(File(context.cacheDir, "drive"))

    /** Pista de la app para un archivo de Drive ya descargado, con sus etiquetas reales. */
    fun toTrack(file: File, item: DriveItem, folderName: String): Track {
        val retriever = MediaMetadataRetriever()
        val ok = runCatching { retriever.setDataSource(file.path); true }.getOrDefault(false)
        fun tag(key: Int) = if (ok) retriever.extractMetadata(key)?.takeIf { it.isNotBlank() } else null
        try {
            return Track(
                id = item.id.hashCode().toLong(),
                uri = Uri.fromFile(file),
                title = tag(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: item.name.substringBeforeLast('.'),
                artist = tag(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "",
                album = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: folderName,
                albumArtist = tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) ?: "",
                trackNumber = tag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    ?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0,
                durationMs = tag(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                mimeType = AudioFormats.extensions[item.extension] ?: "audio/flac",
                folderKey = "drive:$folderName",
                folderName = folderName,
                folderDocId = null,
                bitrate = tag(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0,
                bitsPerSample = tag(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull() ?: 0,
                sampleRate = tag(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0,
                fileName = item.name,
                dateModified = file.lastModified(),
            )
        } finally {
            retriever.release()
        }
    }
}

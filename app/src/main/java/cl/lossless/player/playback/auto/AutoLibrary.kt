package cl.lossless.player.playback.auto

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.annotation.OptIn
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import cl.lossless.player.R
import cl.lossless.player.covers.CoverProvider
import cl.lossless.player.covers.CoverStore
import cl.lossless.player.library.AlbumGroup
import cl.lossless.player.library.FolderExclusions
import cl.lossless.player.library.LibraryPrefs
import cl.lossless.player.library.LibrarySearch
import cl.lossless.player.library.MusicRepository
import cl.lossless.player.library.Playlist
import cl.lossless.player.library.PlaylistStore
import cl.lossless.player.library.Track
import cl.lossless.player.library.albumKey
import cl.lossless.player.library.folderNode
import cl.lossless.player.library.folderSegments
import cl.lossless.player.library.toAlbumGroups
import kotlinx.coroutines.runBlocking

/**
 * La biblioteca vista desde Android Auto: un árbol de Playlists, Álbumes, Artistas y Carpetas
 * (las cuatro pestañas que muestra el auto). Usa la misma carpeta, carpetas omitidas,
 * playlists y carátulas que la app. Todo se llama desde un hilo de fondo.
 */
@OptIn(UnstableApi::class)
class AutoLibrary(private val context: Context) {

    private class Snapshot(
        val tracks: List<Track>,
        val albums: List<AlbumGroup>,
        val albumsByKey: Map<String, AlbumGroup>,
        val tracksByUri: Map<String, Track>,
        val loadedAt: Long,
    )

    private val prefs = LibraryPrefs(context)
    private val repository = MusicRepository(context)
    private val playlists = PlaylistStore(context)
    private val covers = CoverStore(context)

    @Volatile private var snapshot: Snapshot? = null

    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    /** Vuelve a leer la biblioteca si la última lectura tiene más de [maxAgeMs]. */
    @Synchronized
    fun refresh(maxAgeMs: Long = 0L) {
        val current = snapshot
        if (current != null && SystemClock.elapsedRealtime() - current.loadedAt < maxAgeMs) return
        val excluded = prefs.excludedFolders
        val tracks = runBlocking { repository.loadTracks(prefs.location) }
            .filterNot { FolderExclusions.isExcluded(it.folderSegments, excluded) }
        val albums = tracks.toAlbumGroups(str(R.string.library_various_artists))
        snapshot = Snapshot(
            tracks = albums.flatMap { it.tracks },
            albums = albums,
            albumsByKey = albums.associateBy { it.key },
            tracksByUri = tracks.associateBy { it.uri.toString() },
            loadedAt = SystemClock.elapsedRealtime(),
        )
    }

    private fun library(): Snapshot = snapshot ?: run { refresh(); snapshot!! }

    // ---------- Árbol ----------

    fun root(): MediaItem = browsable(AutoMediaIds.ROOT, str(R.string.app_name))

    /** Opciones de la raíz: estilo de las listas y búsqueda habilitada. */
    fun rootExtras(): Bundle = Bundle().apply {
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        putBoolean(SEARCH_SUPPORTED, true)
    }

    fun children(parentId: String): List<MediaItem>? {
        val lib = library()
        return when {
            parentId == AutoMediaIds.ROOT -> listOf(
                browsable(AutoMediaIds.PLAYLISTS, str(R.string.library_playlists), type = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                browsable(AutoMediaIds.ALBUMS, str(R.string.library_tab_albums), type = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, grid = true),
                browsable(AutoMediaIds.ARTISTS, str(R.string.auto_artists), type = MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
                browsable(AutoMediaIds.FOLDERS, str(R.string.library_folders)),
            )
            parentId == AutoMediaIds.PLAYLISTS -> buildList {
                add(playable(AutoMediaIds.SHUFFLE_ALL, str(R.string.auto_shuffle_all)))
                add(browsable(AutoMediaIds.ALL_TRACKS, str(R.string.auto_all_tracks), subtitle = lib.tracks.size.toString()))
                playlists.load().forEach { p ->
                    add(
                        browsable(
                            AutoMediaIds.PLAYLIST + p.id, playlistName(p),
                            subtitle = p.trackUris.size.toString(), type = MediaMetadata.MEDIA_TYPE_PLAYLIST,
                        )
                    )
                }
            }
            parentId == AutoMediaIds.ALBUMS -> lib.albums.map(::albumItem)
            parentId == AutoMediaIds.ARTISTS -> lib.tracks.groupBy(::artistOf).keys
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
                .map { browsable(AutoMediaIds.ARTIST + it, it, type = MediaMetadata.MEDIA_TYPE_ARTIST, grid = true) }
            parentId.startsWith(AutoMediaIds.ARTIST) -> {
                val artist = parentId.removePrefix(AutoMediaIds.ARTIST)
                lib.albums.filter { album -> album.tracks.any { artistOf(it) == artist } }.map(::albumItem)
            }
            parentId == AutoMediaIds.FOLDERS || parentId.startsWith(AutoMediaIds.FOLDER) -> {
                val segments = if (parentId == AutoMediaIds.FOLDERS) emptyList() else AutoMediaIds.folderSegments(parentId)
                val node = lib.tracks.folderNode(segments)
                node.subfolders.map { sub ->
                    val title = if (segments.isEmpty()) volumeName(sub.name) else sub.name
                    browsable(AutoMediaIds.folder(sub.segments), title, subtitle = sub.trackCount.toString())
                } + node.tracks.map { trackItem(parentId, it) }
            }
            else -> tracksOf(parentId)?.map { trackItem(parentId, it) }
        }
    }

    /** Pistas de una lista que se puede reproducir entera (álbum, playlist, carpeta, búsqueda…). */
    fun tracksOf(parentId: String): List<Track>? {
        val lib = library()
        return when {
            parentId == AutoMediaIds.ALL_TRACKS || parentId == AutoMediaIds.SHUFFLE_ALL -> lib.tracks
            parentId.startsWith(AutoMediaIds.ALBUM) -> lib.albumsByKey[parentId.removePrefix(AutoMediaIds.ALBUM)]?.tracks
            parentId.startsWith(AutoMediaIds.PLAYLIST) -> {
                val id = parentId.removePrefix(AutoMediaIds.PLAYLIST)
                playlists.load().firstOrNull { it.id == id }?.trackUris?.mapNotNull { lib.tracksByUri[it] }
            }
            parentId.startsWith(AutoMediaIds.FOLDER) ->
                lib.tracks.folderNode(AutoMediaIds.folderSegments(parentId)).tracks
            parentId.startsWith(AutoMediaIds.SEARCH) -> search(parentId.removePrefix(AutoMediaIds.SEARCH))
            else -> null
        }
    }

    fun item(id: String): MediaItem? {
        AutoMediaIds.parseTrack(id)?.let { (parent, uri) ->
            return library().tracksByUri[uri]?.let { trackItem(parent, it) }
        }
        if (id == AutoMediaIds.ROOT) return root()
        // Basta con buscarlo entre los hijos de su carpeta.
        val parent = when {
            id.startsWith(AutoMediaIds.ALBUM) -> AutoMediaIds.ALBUMS
            id.startsWith(AutoMediaIds.ARTIST) -> AutoMediaIds.ARTISTS
            id.startsWith(AutoMediaIds.PLAYLIST) || id == AutoMediaIds.ALL_TRACKS || id == AutoMediaIds.SHUFFLE_ALL ->
                AutoMediaIds.PLAYLISTS
            id.startsWith(AutoMediaIds.FOLDER) -> AutoMediaIds.folderSegments(id).dropLast(1)
                .let { if (it.isEmpty()) AutoMediaIds.FOLDERS else AutoMediaIds.folder(it) }
            else -> AutoMediaIds.ROOT
        }
        return children(parent)?.firstOrNull { it.mediaId == id }
    }

    fun search(query: String): List<Track> =
        LibrarySearch.tracks(library().tracks, emptyList(), query).take(MAX_SEARCH_RESULTS)

    fun searchResults(query: String): List<MediaItem> =
        search(query).map { trackItem(AutoMediaIds.SEARCH + query, it) }

    // ---------- Para reproducir ----------

    /** Lo que se toca en el auto, convertido en la cola que entiende el reproductor. */
    fun resolveQueue(id: String): Pair<List<MediaItem>, Int>? {
        if (id == AutoMediaIds.SHUFFLE_ALL) {
            val all = library().tracks.ifEmpty { return null }
            return all.map(::playbackItem) to all.indices.random()
        }
        val (parent, uri) = AutoMediaIds.parseTrack(id) ?: return null
        val list = tracksOf(parent).orEmpty()
        val index = list.indexOfFirst { it.uri.toString() == uri }
        if (index >= 0) return list.map(::playbackItem) to index
        // La lista cambió desde que el auto la mostró: al menos suena esa pista.
        val track = library().tracksByUri[uri] ?: return null
        return listOf(playbackItem(track)) to 0
    }

    /** Una pista del árbol como elemento de cola (mediaId = URI, como la usa la app). */
    fun resolveSingle(id: String): MediaItem? {
        val (_, uri) = AutoMediaIds.parseTrack(id) ?: return null
        return library().tracksByUri[uri]?.let(::playbackItem)
    }

    fun playbackItem(track: Track): MediaItem = track.toMediaItem(coverUri(track))

    // ---------- Elementos ----------

    private fun coverUri(track: Track) = covers.get(track.albumKey)?.let { CoverProvider.uriFor(context, it) }

    private fun albumItem(album: AlbumGroup): MediaItem = browsable(
        AutoMediaIds.ALBUM + album.key, album.title,
        subtitle = album.artist,
        artwork = covers.get(album.key)?.let { CoverProvider.uriFor(context, it) },
        type = MediaMetadata.MEDIA_TYPE_ALBUM,
    )

    private fun trackItem(parentId: String, track: Track): MediaItem = MediaItem.Builder()
        .setMediaId(AutoMediaIds.track(parentId, track.uri.toString()))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setAlbumTitle(track.album)
                // En las listas del auto: "Artista · FLAC 24-bit".
                .setSubtitle(listOf(track.artist, track.qualityLabel).filter { it.isNotBlank() }.joinToString(" · "))
                .setDescription(track.qualityDetails.ifEmpty { null })
                .setArtworkUri(coverUri(track))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build()
        )
        .build()

    private fun playable(id: String, title: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                .build()
        )
        .build()

    private fun browsable(
        id: String,
        title: String,
        subtitle: String? = null,
        artwork: Uri? = null,
        type: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
        grid: Boolean = false,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtworkUri(artwork)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(type)
                .apply {
                    // Los hijos de esta carpeta se muestran como cuadrícula de carátulas.
                    if (grid) setExtras(Bundle().apply {
                        putInt(
                            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
                        )
                    })
                }
                .build()
        )
        .build()

    private fun artistOf(track: Track) = track.albumArtist.ifBlank { track.artist }

    private fun playlistName(p: Playlist) = if (p.isFavorites) str(R.string.library_favorites) else p.name

    private fun volumeName(volume: String) =
        if (volume == "primary") str(R.string.settings_volume_internal)
        else str(R.string.settings_volume_external, volume)

    private companion object {
        /** Clave que Android Auto lee en la raíz para mostrar la lupa. */
        const val SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
        const val MAX_SEARCH_RESULTS = 100
    }
}

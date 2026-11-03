package cl.lossless.player.playback.auto

/**
 * Identificadores de los elementos que Android Auto recorre. Las carpetas del árbol llevan un
 * prefijo con lo que son; una pista lleva además la carpeta desde la que se eligió, para que al
 * tocarla suene toda esa lista (el álbum, la playlist…) empezando por ella.
 */
object AutoMediaIds {
    const val ROOT = "root"
    const val PLAYLISTS = "playlists"
    const val ALBUMS = "albums"
    const val ARTISTS = "artists"
    const val FOLDERS = "folders"
    const val ALL_TRACKS = "tracks"
    const val SHUFFLE_ALL = "shuffle"

    const val PLAYLIST = "pl:"
    const val ALBUM = "album:"
    const val ARTIST = "artist:"
    const val FOLDER = "folder:"
    const val SEARCH = "search:"
    private const val TRACK = "track:"

    /** Separador entre la carpeta y la URI de la pista (no aparece en ninguna de las dos). */
    private const val SEP = '\u001F'

    fun track(parentId: String, trackUri: String) = "$TRACK$parentId$SEP$trackUri"

    /** (carpeta, URI de la pista) o null si [id] no es una pista del árbol. */
    fun parseTrack(id: String): Pair<String, String>? {
        if (!id.startsWith(TRACK)) return null
        val rest = id.removePrefix(TRACK)
        val cut = rest.lastIndexOf(SEP)
        if (cut < 0) return null
        return rest.substring(0, cut) to rest.substring(cut + 1)
    }

    fun folder(segments: List<String>) = FOLDER + segments.joinToString("/")

    fun folderSegments(id: String): List<String> =
        id.removePrefix(FOLDER).split('/').filter { it.isNotEmpty() }

    /** Pedazo de [items] para la página [page] (desde 0) de tamaño [pageSize]. */
    fun <T> page(items: List<T>, page: Int, pageSize: Int): List<T> {
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return items
        val from = page.toLong() * pageSize
        if (from >= items.size) return emptyList()
        return items.subList(from.toInt(), minOf(items.size, from.toInt() + pageSize))
    }
}

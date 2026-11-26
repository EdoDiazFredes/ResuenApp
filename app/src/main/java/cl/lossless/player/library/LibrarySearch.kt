package cl.lossless.player.library

import java.text.Normalizer

/** Orden de las carpetas en las vistas de carpetas. Se guarda por nombre en las preferencias. */
enum class FolderSort { NAME_ASC, NAME_DESC, NEWEST, OLDEST, MOST_TRACKS }

/**
 * Ordena carpetas según [sort]. Los desempates van siempre por nombre A-Z para que la lista
 * no "salte" entre recargas.
 */
fun <T> List<T>.sortedFolders(
    sort: FolderSort,
    name: (T) -> String,
    trackCount: (T) -> Int,
    lastModified: (T) -> Long,
): List<T> {
    val byName = compareBy<T, String>(String.CASE_INSENSITIVE_ORDER, name)
    val comparator: Comparator<T> = when (sort) {
        FolderSort.NAME_ASC -> byName
        FolderSort.NAME_DESC -> byName.reversed()
        FolderSort.NEWEST -> compareByDescending<T> { lastModified(it) }.then(byName)
        FolderSort.OLDEST -> compareBy<T> { lastModified(it) }.then(byName)
        FolderSort.MOST_TRACKS -> compareByDescending<T> { trackCount(it) }.then(byName)
    }
    return sortedWith(comparator)
}

/** Búsqueda sin distinguir mayúsculas ni tildes ("cancion" encuentra "Canción"). */
object LibrarySearch {

    private val combiningMarks = Regex("\\p{Mn}+")

    fun normalize(text: String): String {
        // Atajo: el texto sin caracteres especiales (lo más común) no necesita descomponerse.
        val plain = text.all { it.code < 0x80 }
        val stripped = if (plain) text else combiningMarks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "")
        return stripped.lowercase().trim()
    }

    /** Palabras de la búsqueda ya normalizadas; se calculan una vez por búsqueda, no por elemento. */
    private fun words(query: String): List<String> = normalize(query).split(' ').filter { it.isNotEmpty() }

    private fun matchesWords(words: List<String>, fields: Array<out String>): Boolean {
        if (words.isEmpty()) return true
        val haystack = fields.joinToString(" ") { normalize(it) }
        return words.all { it in haystack }
    }

    /** Todas las palabras de [query] deben aparecer en alguno de los [fields]. */
    fun matches(query: String, vararg fields: String): Boolean = matchesWords(words(query), fields)

    fun albums(albums: List<AlbumGroup>, query: String): List<AlbumGroup> =
        if (query.isBlank()) albums
        else words(query).let { w -> albums.filter { matchesWords(w, arrayOf(it.title, it.artist)) } }

    /**
     * Carpetas bajo [base] (a cualquier profundidad) cuyo nombre coincide con [query].
     * El volumen (primer segmento) no cuenta como carpeta buscable.
     */
    fun folders(tracks: List<Track>, base: List<String>, query: String): List<FolderEntry> =
        if (query.isBlank()) emptyList()
        else foldersIn(tracks.map { it.folderSegments to it.dateModified }, base, query)

    /** Igual que la anterior, a partir de (segmentos de la carpeta, fecha) de cada pista. */
    internal fun foldersIn(
        trackFolders: List<Pair<List<String>, Long>>,
        base: List<String>,
        query: String,
    ): List<FolderEntry> {
        if (query.isBlank()) return emptyList()
        val found = mutableMapOf<List<String>, Pair<Int, Long>>() // segmentos → (pistas, modificación)
        for ((s, dateModified) in trackFolders) {
            if (s.size <= base.size || s.subList(0, base.size) != base) continue
            for (depth in maxOf(base.size + 1, 2)..s.size) {
                val folder = s.subList(0, depth)
                val (count, modified) = found[folder] ?: (0 to 0L)
                found[folder] = (count + 1) to maxOf(modified, dateModified)
            }
        }
        val w = words(query)
        return found
            .filterKeys { matchesWords(w, arrayOf(it.last())) }
            .map { (segments, info) -> FolderEntry(segments.last(), segments, info.first, info.second) }
    }

    /** Pistas bajo [base] (a cualquier profundidad) que coinciden por título, artista o álbum. */
    fun tracks(tracks: List<Track>, base: List<String>, query: String): List<Track> {
        if (query.isBlank()) return emptyList()
        val w = words(query)
        return tracks.filter { track ->
            val s = track.folderSegments
            s.size >= base.size && s.subList(0, base.size) == base &&
                matchesWords(w, arrayOf(track.title, track.artist, track.album, track.fileName))
        }
    }
}

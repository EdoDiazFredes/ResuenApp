package cl.lossless.player.desktop

import cl.lossless.player.library.AlbumGrouping
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger
import java.util.prefs.Preferences
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/** Una pista de la biblioteca de escritorio (equivale a Track de Android, sin MediaStore). */
data class Track(
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val trackNumber: Int,
    val discNumber: Int,
    val durationMs: Long,
    val codec: String,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val channels: Int,
    val lastModified: Long,
) {
    val file: File get() = File(path)
    val folder: String get() = file.parent.orEmpty()
    val folderName: String get() = file.parentFile?.name.orEmpty()
    /** Carpeta + álbum: identifica la carátula. El álbum que se muestra lo decide [albumKeysByPath]. */
    val albumKey: String get() = "$folder|${album.trim().lowercase()}"

    val groupingFields: AlbumGrouping.Fields get() = AlbumGrouping.Fields(folder, album, albumArtist, artist)

    /** "FLAC 24/96" para mostrar junto a la pista. */
    val formatLabel: String
        get() = buildString {
            append(codec.ifBlank { file.extension.uppercase() })
            if (bitsPerSample > 0 && sampleRate > 0) append(" ${bitsPerSample}/${khz(sampleRate)}")
            else if (sampleRate > 0) append(" ${khz(sampleRate)} kHz")
        }

    fun toJson(): JSONObject = JSONObject()
        .put("path", path).put("title", title).put("artist", artist).put("album", album)
        .put("albumArtist", albumArtist).put("track", trackNumber).put("disc", discNumber)
        .put("duration", durationMs).put("codec", codec).put("rate", sampleRate)
        .put("bits", bitsPerSample).put("channels", channels).put("modified", lastModified)

    companion object {
        fun fromJson(o: JSONObject) = Track(
            path = o.getString("path"),
            title = o.optString("title"),
            artist = o.optString("artist"),
            album = o.optString("album"),
            albumArtist = o.optString("albumArtist"),
            trackNumber = o.optInt("track"),
            discNumber = o.optInt("disc"),
            durationMs = o.optLong("duration"),
            codec = o.optString("codec"),
            sampleRate = o.optInt("rate"),
            bitsPerSample = o.optInt("bits"),
            channels = o.optInt("channels"),
            lastModified = o.optLong("modified"),
        )
    }
}

fun khz(rate: Int): String {
    val k = rate / 1000.0
    return if (k % 1.0 == 0.0) k.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, k)
}

/** Un álbum dentro de una carpeta, como AlbumGroup en Android. */
data class Album(val key: String, val title: String, val artist: String, val tracks: List<Track>)

/** Clave del álbum de cada pista; une las de carpetas distintas con el mismo álbum y artista. */
fun List<Track>.albumKeysByPath(): Map<String, String> {
    val keys = AlbumGrouping.keys(this) { it.groupingFields }
    return indices.associate { this[it].path to keys[it] }
}

fun List<Track>.toAlbums(variousArtists: String): List<Album> =
    AlbumGrouping.group(this) { it.groupingFields }.map { (key, tracks) ->
        val first = tracks.first()
        val artists = tracks.map { it.albumArtist.ifBlank { it.artist }.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
        Album(
            key = key,
            title = first.album.ifBlank { first.folderName },
            artist = artists.singleOrNull() ?: if (artists.isEmpty()) "" else variousArtists,
            tracks = tracks.sortedForAlbum(),
        )
    }.sortedBy { "${it.artist} ${it.title}".lowercase() }

fun List<Track>.sortedForAlbum(): List<Track> =
    sortedWith(compareBy({ it.discNumber }, { it.trackNumber }, { it.file.name.lowercase() }))

fun List<Track>.sortedForLibrary(): List<Track> =
    sortedWith(compareBy({ it.albumArtist.ifBlank { it.artist }.lowercase() }, { it.album.lowercase() },
        { it.discNumber }, { it.trackNumber }, { it.title.lowercase() }))

/**
 * Carpeta donde la app guarda su índice y cachés: %APPDATA%\ResuenApp en Windows,
 * ~/Library/Application Support/ResuenApp en macOS.
 */
object AppDirs {
    val data: File by lazy {
        val home = System.getProperty("user.home")
        val base = when {
            IsMac -> File(home, "Library/Application Support")
            else -> System.getenv("APPDATA")?.let(::File) ?: File(home, ".config")
        }
        File(base, "ResuenApp").apply { mkdirs() }
    }
}

/** Ajustes guardados con java.util.prefs (registro en Windows, .plist en macOS). */
object AppSettings {
    private val prefs: Preferences = Preferences.userRoot().node("cl/lossless/resuenapp")

    var folders: List<String>
        get() = prefs.get("folders", "").split('\n').filter { it.isNotBlank() }
        set(value) = prefs.put("folders", value.joinToString("\n"))

    /** null = idioma del sistema. */
    var language: Lang?
        get() = Lang.fromCode(prefs.get("language", null))
        set(value) = if (value == null) prefs.remove("language") else prefs.put("language", value.code)

    var volume: Float
        get() = prefs.getFloat("volume", 1f)
        set(value) = prefs.putFloat("volume", value)

    var repeat: RepeatMode
        get() = RepeatMode.entries.firstOrNull { it.name == prefs.get("repeat", null) } ?: RepeatMode.OFF
        set(value) = prefs.put("repeat", value.name)

    var shuffle: Boolean
        get() = prefs.getBoolean("shuffle", false)
        set(value) = prefs.putBoolean("shuffle", value)

    // Apariencia: nombres de ThemeMode, Accent, AppFont y TextSize (ver ui/Theme.kt).
    var theme: String?
        get() = prefs.get("theme", null)
        set(value) = prefs.put("theme", value.orEmpty())
    var accent: String?
        get() = prefs.get("accent", null)
        set(value) = prefs.put("accent", value.orEmpty())
    var font: String?
        get() = prefs.get("font", null)
        set(value) = prefs.put("font", value.orEmpty())
    var textSize: String?
        get() = prefs.get("text_size", null)
        set(value) = prefs.put("text_size", value.orEmpty())

    var panelLeft: Boolean
        get() = prefs.getBoolean("panel_left", false)
        set(value) = prefs.putBoolean("panel_left", value)
    var sidebarCollapsed: Boolean
        get() = prefs.getBoolean("sidebar_collapsed", false)
        set(value) = prefs.putBoolean("sidebar_collapsed", value)
    var panelWidth: Float
        get() = prefs.getFloat("panel_width", 320f)
        set(value) = prefs.putFloat("panel_width", value)

    // Atajos de reproducción: una línea "ACCIÓN=tecla:ctrl:alt:shift:meta" por acción con tecla.
    fun loadShortcuts(): Map<PlaybackAction, KeyCombo>? {
        val raw = prefs.get("shortcuts", null) ?: return null
        return raw.split('\n').mapNotNull { line ->
            val action = PlaybackAction.entries.firstOrNull { it.name == line.substringBefore('=') }
            val combo = KeyCombo.decode(line.substringAfter('=', ""))
            if (action != null && combo != null) action to combo else null
        }.toMap()
    }
    fun saveShortcuts(bindings: Map<PlaybackAction, KeyCombo>) =
        prefs.put("shortcuts", bindings.entries.joinToString("\n") { "${it.key.name}=${it.value.encode()}" })

    /** Teclas multimedia con la app en segundo plano. En macOS pide permiso de accesibilidad, así que empieza apagado. */
    var mediaKeys: Boolean
        get() = prefs.getBoolean("media_keys", !IsMac)
        set(value) = prefs.putBoolean("media_keys", value)

    // Mini reproductor: si queda encima de las demás ventanas y dónde se dejó (null = esquina inferior derecha).
    var miniOnTop: Boolean
        get() = prefs.getBoolean("mini_on_top", true)
        set(value) = prefs.putBoolean("mini_on_top", value)
    var miniPosition: Pair<Float, Float>?
        get() {
            val x = prefs.getFloat("mini_x", Float.NaN)
            val y = prefs.getFloat("mini_y", Float.NaN)
            return if (x.isNaN() || y.isNaN()) null else x to y
        }
        set(value) {
            if (value == null) { prefs.remove("mini_x"); prefs.remove("mini_y") }
            else { prefs.putFloat("mini_x", value.first); prefs.putFloat("mini_y", value.second) }
        }
}

/** Lee las carpetas elegidas y sus etiquetas. Reutiliza lo ya leído si el archivo no cambió. */
object LibraryScanner {

    val EXTENSIONS = setOf("flac", "m4a", "alac", "mp4", "wav", "aif", "aiff", "mp3", "aac", "ogg", "oga", "opus", "wma", "dsf")

    private val cacheFile get() = File(AppDirs.data, "library.json")

    init {
        // jaudiotagger escribe mucho en el registro por cada archivo.
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    fun loadCache(): List<Track> = runCatching {
        if (!cacheFile.exists()) return emptyList()
        val array = JSONArray(cacheFile.readText())
        (0 until array.length()).map { Track.fromJson(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun saveCache(tracks: List<Track>) {
        runCatching {
            val array = JSONArray()
            tracks.forEach { array.put(it.toJson()) }
            val tmp = File(cacheFile.parentFile, "library.json.tmp")
            tmp.writeText(array.toString())
            Files.move(tmp.toPath(), cacheFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun scan(folders: List<String>, previous: List<Track>, onProgress: (Int) -> Unit): List<Track> {
        val known = previous.associateBy { it.path }
        val result = mutableListOf<Track>()
        var count = 0
        for (folder in folders) {
            val root = File(folder).toPath()
            if (!Files.isDirectory(root)) continue
            val files = runCatching {
                Files.walk(root).use { stream ->
                    stream.filter { it.isRegularFile() && it.extension.lowercase() in EXTENSIONS }.toList()
                }
            }.getOrDefault(emptyList())
            for (path in files) {
                val file = path.toFile()
                val modified = file.lastModified()
                val cached = known[file.path]
                result += if (cached != null && cached.lastModified == modified) cached else readTrack(path, modified)
                if (++count % 25 == 0) onProgress(count)
            }
        }
        onProgress(count)
        val tracks = result.distinctBy { it.path }
        saveCache(tracks)
        return tracks
    }

    fun readTrack(path: Path, modified: Long = path.toFile().lastModified()): Track {
        val file = path.toFile()
        val fallbackTitle = file.nameWithoutExtension
        return runCatching {
            val audio = AudioFileIO.read(file)
            val header = audio.audioHeader
            val tag = audio.tag
            Track(
                path = file.path,
                title = tag.field(FieldKey.TITLE).ifBlank { fallbackTitle },
                artist = tag.field(FieldKey.ARTIST),
                album = tag.field(FieldKey.ALBUM),
                albumArtist = tag.field(FieldKey.ALBUM_ARTIST),
                trackNumber = tag.field(FieldKey.TRACK).substringBefore('/').trim().toIntOrNull() ?: 0,
                discNumber = tag.field(FieldKey.DISC_NO).substringBefore('/').trim().toIntOrNull() ?: 0,
                durationMs = header.trackLength * 1000L,
                codec = codecName(header.encodingType, file),
                sampleRate = header.sampleRateAsNumber,
                bitsPerSample = header.bitsPerSample,
                channels = header.channels?.trim()?.toIntOrNull() ?: 2,
                lastModified = modified,
            )
        }.getOrElse {
            Track(file.path, fallbackTitle, "", "", "", 0, 0, 0, file.extension.uppercase(), 0, 0, 2, modified)
        }
    }

    private fun Tag?.field(key: FieldKey): String =
        this?.let { runCatching { it.getFirst(key) }.getOrNull() }?.trim().orEmpty()

    private fun codecName(encoding: String?, file: File): String {
        val e = encoding.orEmpty().lowercase()
        return when {
            "alac" in e || "apple lossless" in e -> "ALAC"
            "flac" in e -> "FLAC"
            "aac" in e -> "AAC"
            "mp3" in e || "mpeg" in e -> "MP3"
            "vorbis" in e -> "OGG"
            "opus" in e -> "OPUS"
            "wav" in e || "pcm" in e -> "WAV"
            else -> file.extension.uppercase()
        }
    }
}

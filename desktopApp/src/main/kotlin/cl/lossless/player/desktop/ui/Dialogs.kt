package cl.lossless.player.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.lossless.player.desktop.CoverCandidate
import cl.lossless.player.desktop.CoverSearch
import cl.lossless.player.desktop.Covers
import cl.lossless.player.desktop.DesktopLyrics
import cl.lossless.player.desktop.DesktopPlaylists
import cl.lossless.player.desktop.IsMac
import cl.lossless.player.desktop.Player
import cl.lossless.player.desktop.S
import cl.lossless.player.desktop.Str
import cl.lossless.player.desktop.TagEditor
import cl.lossless.player.desktop.Track
import cl.lossless.player.desktop.t
import cl.lossless.player.lyrics.LrcLibClient
import cl.lossless.player.lyrics.TrackMetadataCleaner
import cl.lossless.player.tags.TagField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/** Diálogo abierto sobre la biblioteca. */
sealed interface TrackDialog {
    data class EditTags(val tracks: List<Track>) : TrackDialog
    data class FindCover(val tracks: List<Track>) : TrackDialog
    data class FindLyrics(val track: Track) : TrackDialog
    data object Equalizer : TrackDialog
    data class AddToPlaylist(val tracks: List<Track>) : TrackDialog
    /** Crear (id null) o renombrar una lista. */
    data class PlaylistName(val id: String?, val current: String) : TrackDialog
    data class DeletePlaylist(val id: String, val name: String) : TrackDialog
}

/** Acciones sobre pistas que ofrecen el menú contextual, el detalle de álbum y el panel. */
class TrackActions(
    val editTags: (List<Track>) -> Unit,
    /** Abre el editor con todas las pistas marcadas, también las que la búsqueda oculta. */
    val editSelection: () -> Unit,
    val findCover: (Track) -> Unit,
    val findLyrics: (Track) -> Unit,
    val addToPlaylist: (List<Track>) -> Unit,
)

val LocalTrackActions = staticCompositionLocalOf<TrackActions?> { null }

/**
 * Pistas marcadas en las listas (por ruta) para editar sus etiquetas juntas, aunque estén en
 * carpetas distintas. Se mantiene al buscar, así se pueden juntar canciones de varias búsquedas.
 */
class TrackSelection {
    var paths by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Última pista marcada o desmarcada: Mayús + clic marca todo lo que hay entre ella y la nueva. */
    private var anchor: String? = null

    val active: Boolean get() = paths.isNotEmpty()

    fun toggle(path: String) {
        paths = if (path in paths) paths - path else paths + path
        anchor = path
    }

    fun extendTo(path: String, list: List<Track>) {
        val from = list.indexOfFirst { it.path == anchor }
        val to = list.indexOfFirst { it.path == path }
        if (from < 0 || to < 0) return toggle(path)
        paths = paths + list.subList(minOf(from, to), maxOf(from, to) + 1).map { it.path }
        anchor = path
    }

    fun selectAll(list: List<Track>) {
        paths = paths + list.map { it.path }
    }

    fun clear() {
        paths = emptySet()
        anchor = null
    }
}

val LocalTrackSelection = staticCompositionLocalOf<TrackSelection?> { null }

/**
 * Muestra [dialog] sobre la ventana. [onUpdated] recibe las pistas releídas tras guardar
 * etiquetas o carátula, para actualizar la biblioteca y la cola.
 */
@Composable
fun TrackDialogHost(dialog: TrackDialog, player: Player, onDismiss: () -> Unit, onUpdated: (List<Track>) -> Unit) {
    // Fondo oscurecido: un clic fuera del cuadro lo cierra.
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(
                when (dialog) {
                    is TrackDialog.EditTags -> 520.dp
                    is TrackDialog.AddToPlaylist, is TrackDialog.PlaylistName, is TrackDialog.DeletePlaylist -> 440.dp
                    else -> 680.dp
                },
            ).heightIn(max = 640.dp)
                .glow(Neon.Accent.copy(alpha = 0.35f), 24.dp, RoundedCornerShape(16.dp))
                .glass(RoundedCornerShape(16.dp), Neon.Surface)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(20.dp),
        ) {
            when (dialog) {
                is TrackDialog.EditTags -> TagEditorDialog(dialog.tracks, player, onDismiss, onUpdated)
                is TrackDialog.FindCover -> CoverSearchDialog(dialog.tracks, player, onDismiss, onUpdated)
                is TrackDialog.FindLyrics -> LyricsSearchDialog(dialog.track, onDismiss)
                TrackDialog.Equalizer -> {
                    DialogHeader(t(S.equalizer), null, onDismiss)
                    EqualizerPanel(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()))
                }
                is TrackDialog.AddToPlaylist -> AddToPlaylistDialog(dialog.tracks, onDismiss)
                is TrackDialog.PlaylistName -> PlaylistNameDialog(dialog.id, dialog.current, onDismiss)
                is TrackDialog.DeletePlaylist -> {
                    DialogHeader(t(S.deletePlaylist), null, onDismiss)
                    Text(t(S.deletePlaylistConfirm, dialog.name), color = Neon.Text, fontSize = 13.sp)
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                        GhostButton(null, t(S.cancel), onDismiss)
                        PrimaryButton(Icons.Rounded.Delete, t(S.deletePlaylist)) { DesktopPlaylists.delete(dialog.id); onDismiss() }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.DialogHeader(title: String, subtitle: String?, onDismiss: () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Neon.TextStrong, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Text(subtitle, color = Neon.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        SmallIconButton(Icons.Rounded.Close, t(S.cancel), onClick = onDismiss)
    }
    Spacer(Modifier.height(16.dp))
}

private val TagField.label: Str
    get() = when (this) {
        TagField.TITLE -> S.fieldTitle
        TagField.ARTIST -> S.fieldArtist
        TagField.ALBUM -> S.fieldAlbum
        TagField.ALBUM_ARTIST -> S.fieldAlbumArtist
        TagField.YEAR -> S.fieldYear
        TagField.GENRE -> S.fieldGenre
        TagField.TRACK -> S.fieldTrack
        TagField.DISC -> S.fieldDisc
    }

/**
 * Editor de etiquetas de una o varias pistas. Con varias, el título y el número de pista no se
 * muestran, y un campo con valores distintos queda vacío y no se toca a menos que se escriba.
 */
@Composable
private fun ColumnScope.TagEditorDialog(tracks: List<Track>, player: Player, onDismiss: () -> Unit, onUpdated: (List<Track>) -> Unit) {
    val scope = rememberCoroutineScope()
    val fields = remember(tracks) { TagField.entries.filter { tracks.size == 1 || !it.perTrack } }
    val values = remember(tracks) { mutableStateMapOf<TagField, String>() }
    val mixed = remember(tracks) { mutableStateMapOf<TagField, Boolean>() }
    val edited = remember(tracks) { mutableStateMapOf<TagField, Boolean>() }
    var loaded by remember(tracks) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tracks) {
        val read = withContext(Dispatchers.IO) { tracks.map { TagEditor.read(it) } }
        for (f in fields) {
            val distinct = read.map { it[f].orEmpty() }.distinct()
            values[f] = distinct.singleOrNull().orEmpty()
            mixed[f] = distinct.size > 1
        }
        loaded = true
    }

    val first = tracks.first()
    DialogHeader(
        t(S.editTags).removeSuffix("…"),
        if (tracks.size == 1) first.file.name else t(S.trackCount, tracks.size),
        onDismiss,
    )
    if (!loaded) {
        Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { Spinner() }
        return
    }
    val folderCount = remember(tracks) { tracks.map { it.folder }.distinct().size }
    if (folderCount > 1) {
        Text(t(S.mergeHint, folderCount), color = Neon.Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 12.dp))
    }
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        fields.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { f ->
                    Column(Modifier.weight(if (f == TagField.TITLE || pair.size == 1) 2f else 1f)) {
                        SectionLabel(t(f.label), Modifier.padding(bottom = 4.dp))
                        NeonTextField(
                            value = values[f].orEmpty(),
                            onValueChange = { v ->
                                values[f] = if (f.numeric) v.filter { it.isDigit() || it == '/' } else v
                                edited[f] = true
                            },
                            placeholder = if (mixed[f] == true && edited[f] != true) t(S.mixedValues) else "",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (pair.size == 1 && pair.first() != TagField.TITLE) Spacer(Modifier.weight(1f))
            }
        }
    }
    error?.let { Text(t(S.saveError, it), color = Color(0xFFFF6B81), fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp)) }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        if (saving) { Spinner(18.dp); Text(t(S.saving), color = Neon.Muted, fontSize = 12.sp) }
        GhostButton(null, t(S.cancel), onDismiss)
        PrimaryButton(Icons.Rounded.Save, t(S.save)) {
            if (saving) return@PrimaryButton
            val changes = fields.filter { edited[it] == true }.associateWith { values[it].orEmpty() }
            if (changes.isEmpty()) { onDismiss(); return@PrimaryButton }
            saving = true
            error = null
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { player.withFilesReleased(tracks.map { it.path }.toSet()) { TagEditor.write(tracks, changes) } }
                }
                saving = false
                result.onSuccess { onUpdated(it); onDismiss() }
                    .onFailure { error = it.message ?: it.javaClass.simpleName }
            }
        }
    }
}

/** Busca la carátula del álbum en iTunes y Deezer (o la toma de un archivo) y la guarda en los archivos. */
@Composable
private fun ColumnScope.CoverSearchDialog(tracks: List<Track>, player: Player, onDismiss: () -> Unit, onUpdated: (List<Track>) -> Unit) {
    val scope = rememberCoroutineScope()
    val first = tracks.first()
    var artist by remember { mutableStateOf(first.albumArtist.ifBlank { first.artist }) }
    var album by remember { mutableStateOf(first.album.ifBlank { first.folderName }) }
    var results by remember { mutableStateOf<List<CoverCandidate>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var offline by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun search() {
        if (searching) return
        searching = true
        offline = false
        scope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { CoverSearch.search(artist, album) } }
            results = found.getOrNull() ?: emptyList()
            offline = found.isFailure
            searching = false
        }
    }

    fun save(bytes: suspend () -> ByteArray) {
        if (saving) return
        saving = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val data = bytes()
                    player.withFilesReleased(tracks.map { it.path }.toSet()) { TagEditor.writeArtwork(tracks, data) }
                }
            }
            saving = false
            result.onSuccess { Covers.invalidate(); onUpdated(it); onDismiss() }
                .onFailure { error = it.message ?: it.javaClass.simpleName }
        }
    }

    LaunchedEffect(Unit) { search() }

    val chooseTitle = t(S.chooseImage)
    DialogHeader(t(S.searchCover).removeSuffix("…"), t(S.coverTarget, tracks.size), onDismiss)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        NeonTextField(artist, { artist = it }, t(S.fieldArtist), Modifier.weight(1f), onSubmit = ::search)
        NeonTextField(album, { album = it }, t(S.fieldAlbum), Modifier.weight(1f), onSubmit = ::search)
        GhostButton(Icons.Rounded.Search, t(S.search), ::search)
    }
    Spacer(Modifier.height(14.dp))
    Box(Modifier.fillMaxWidth().height(380.dp)) {
        val list = results
        when {
            searching || list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
            offline -> Note(t(S.offline))
            list.isEmpty() -> Note(t(S.noResults))
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(140.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(list, key = { it.fullUrl }) { c ->
                    CoverCell(c) { save { CoverSearch.download(c.fullUrl) } }
                }
            }
        }
    }
    error?.let { Text(t(S.saveError, it), color = Color(0xFFFF6B81), fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp)) }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        GhostButton(Icons.Rounded.AddPhotoAlternate, t(S.coverFromFile)) {
            pickImage(chooseTitle)?.let { file -> save { file.readBytes() } }
        }
        Spacer(Modifier.weight(1f))
        if (saving) { Spinner(18.dp); Text(t(S.saving), color = Neon.Muted, fontSize = 12.sp) }
        GhostButton(null, t(S.cancel), onDismiss)
    }
}

@Composable
private fun CoverCell(c: CoverCandidate, onPick: () -> Unit) {
    var image by remember(c.thumbUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(c.thumbUrl) {
        image = withContext(Dispatchers.IO) {
            runCatching { ImageIO.read(ByteArrayInputStream(CoverSearch.download(c.thumbUrl)))?.toComposeImageBitmap() }.getOrNull()
        }
    }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.clip(shape).background(Neon.Card.copy(alpha = 0.6f))
            .border(1.dp, if (hovered) Neon.Accent.copy(alpha = 0.6f) else Neon.Border, shape)
            .hoverable(interaction).clickable(onClick = onPick).padding(8.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(Neon.Night), contentAlignment = Alignment.Center) {
            val img = image
            if (img != null) Image(img, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) else Spinner(18.dp)
        }
        Spacer(Modifier.height(6.dp))
        Text(c.album, color = Neon.Text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${c.artist} · ${c.source}", color = Neon.Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Busca la letra en LRCLIB con lo que escriba el usuario y guarda la elegida para esa pista. */
@Composable
private fun ColumnScope.LyricsSearchDialog(track: Track, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val cleaned = remember(track) { TrackMetadataCleaner.clean(track.title, track.artist) }
    var title by remember { mutableStateOf(cleaned.title) }
    var artist by remember { mutableStateOf(cleaned.artist) }
    var results by remember { mutableStateOf<List<LrcLibClient.Record>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var offline by remember { mutableStateOf(false) }
    var serverError by remember { mutableStateOf<Int?>(null) }

    fun search() {
        if (searching || title.isBlank()) return
        searching = true
        offline = false
        serverError = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { DesktopLyrics.search(title, artist) }
            }
            results = outcome.getOrNull() ?: emptyList()
            val error = outcome.exceptionOrNull()
            serverError = (error as? LrcLibClient.ServerError)?.code
            offline = error != null && serverError == null
            searching = false
        }
    }

    LaunchedEffect(Unit) { search() }

    DialogHeader(t(S.searchLyrics).removeSuffix("…"), track.file.name, onDismiss)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        NeonTextField(title, { title = it }, t(S.fieldTitle), Modifier.weight(1f), onSubmit = ::search)
        NeonTextField(artist, { artist = it }, t(S.fieldArtist), Modifier.weight(1f), onSubmit = ::search)
        GhostButton(Icons.Rounded.Search, t(S.search), ::search)
    }
    Spacer(Modifier.height(14.dp))
    Box(Modifier.fillMaxWidth().height(400.dp)) {
        val list = results
        when {
            searching || list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
            serverError != null -> Note(t(S.lyricsServerError, serverError!!))
            offline -> Note(t(S.lyricsOffline))
            list.isEmpty() -> Note(t(S.lyricsNone))
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list) { r -> LyricsResultRow(r) { DesktopLyrics.setManual(track, r); onDismiss() } }
            }
        }
    }
}

@Composable
private fun LyricsResultRow(r: LrcLibClient.Record, onUse: () -> Unit) {
    val synced = !r.syncedLyrics.isNullOrBlank()
    val preview = (r.plainLyrics ?: r.syncedLyrics?.replace(Regex("\\[[^\\]]*\\]"), "")).orEmpty()
        .lines().map { it.trim() }.filter { it.isNotEmpty() }.take(2).joinToString(" / ")
    Row(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(10.dp), Neon.Card.copy(alpha = 0.6f)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.trackName, color = Neon.TextStrong, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                NeonBadge(if (synced) t(S.lyricsSynced) else t(S.lyricsPlain), color = if (synced) Neon.Accent else Neon.Muted, emphasized = synced)
            }
            Text(
                listOf(r.artistName, r.albumName, formatTime((r.durationSec * 1000).toLong())).filter { it.isNotBlank() }.joinToString(" · "),
                color = Neon.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (preview.isNotEmpty()) Text(preview, color = Neon.Faint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        PrimaryButton(Icons.Rounded.Save, t(S.useThis), onUse)
    }
}

/** Campo de texto con el estilo del buscador de la barra superior. */
@Composable
fun NeonTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSubmit: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(8.dp)
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.height(34.dp).clip(shape).background(Neon.Card)
            .border(1.dp, if (focused) Neon.Accent.copy(alpha = 0.6f) else Neon.Border, shape)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(placeholder, color = Neon.Faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = Neon.Text, fontSize = 13.sp, fontFamily = Appearance.font.family),
            cursorBrush = SolidColor(Neon.Accent),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { e ->
                    if (onSubmit != null && e.type == KeyEventType.KeyDown && e.key == Key.Enter) { onSubmit(); true } else false
                },
        )
    }
}

@Composable
private fun Spinner(size: Dp = 28.dp) {
    CircularProgressIndicator(Modifier.size(size), color = Neon.Accent, strokeWidth = 2.dp)
}

@Composable
private fun Note(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Neon.Muted, fontSize = 12.sp)
    }
}

private fun pickImage(title: String): File? {
    val dialog = FileDialog(null as java.awt.Frame?, title, FileDialog.LOAD)
    if (!IsMac) dialog.file = "*.jpg;*.jpeg;*.png"
    dialog.setFilenameFilter { _, name -> name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") } }
    dialog.isVisible = true
    return dialog.file?.let { File(dialog.directory, it) }?.takeIf { it.isFile }
}

/** Elegir una lista existente o crear una nueva con las pistas (una canción, un álbum o la cola). */
@Composable
private fun ColumnScope.AddToPlaylistDialog(tracks: List<Track>, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    val paths = remember(tracks) { tracks.map { it.path } }
    fun create() {
        if (name.isBlank()) return
        DesktopPlaylists.create(name, paths)
        onDismiss()
    }
    DialogHeader(
        t(S.addToPlaylist).removeSuffix("…"),
        if (tracks.size == 1) tracks.first().title else t(S.trackCount, tracks.size),
        onDismiss,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        NeonTextField(name, { name = it }, t(S.newPlaylist), Modifier.weight(1f), onSubmit = ::create)
        Spacer(Modifier.width(10.dp))
        PrimaryButton(Icons.Rounded.Add, t(S.create), ::create)
    }
    val lists = DesktopPlaylists.all
    if (lists.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        LazyColumn(Modifier.heightIn(max = 320.dp)) {
            items(lists, key = { it.id }) { list ->
                val interaction = remember { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()
                val already = paths.all { it in list.paths }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(if (hovered) Neon.Hover.copy(alpha = 0.6f) else Color.Transparent)
                        .hoverable(interaction)
                        .clickable { DesktopPlaylists.add(list.id, paths); onDismiss() }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, tint = Neon.Accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        list.name, Modifier.weight(1f), color = if (hovered) Neon.TextStrong else Neon.Text, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (already) Icon(Icons.Rounded.Check, null, tint = Neon.Accent, modifier = Modifier.size(16.dp).padding(end = 4.dp))
                    Text(t(S.trackCount, list.paths.size), color = Neon.Muted, fontFamily = Mono, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.PlaylistNameDialog(id: String?, current: String, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    fun save() {
        if (name.isBlank()) return
        if (id == null) DesktopPlaylists.create(name) else DesktopPlaylists.rename(id, name)
        onDismiss()
    }
    DialogHeader(if (id == null) t(S.newPlaylist) else t(S.renamePlaylist), null, onDismiss)
    SectionLabel(t(S.playlistName), Modifier.padding(bottom = 4.dp))
    NeonTextField(name, { name = it }, t(S.playlistName), Modifier.fillMaxWidth(), onSubmit = ::save)
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
        GhostButton(null, t(S.cancel), onDismiss)
        PrimaryButton(Icons.Rounded.Save, if (id == null) t(S.create) else t(S.save), ::save)
    }
}

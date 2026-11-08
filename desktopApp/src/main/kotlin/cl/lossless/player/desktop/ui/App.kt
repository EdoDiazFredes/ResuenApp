package cl.lossless.player.desktop.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.ui.unit.DpOffset
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.automirrored.rounded.MenuOpen
import androidx.compose.material.icons.rounded.Add
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.lossless.player.desktop.APP_VERSION
import cl.lossless.player.desktop.DesktopLyrics
import cl.lossless.player.desktop.DesktopPlaylists
import cl.lossless.player.desktop.DesktopUpdater
import cl.lossless.player.desktop.AppSettings
import cl.lossless.player.desktop.IsMac
import cl.lossless.player.desktop.Lang
import cl.lossless.player.desktop.LibraryScanner
import cl.lossless.player.desktop.LocalLang
import cl.lossless.player.desktop.Player
import cl.lossless.player.desktop.S
import cl.lossless.player.desktop.Track
import cl.lossless.player.desktop.albumKeysByPath
import cl.lossless.player.desktop.sortedForLibrary
import cl.lossless.player.desktop.t
import cl.lossless.player.desktop.toAlbums
import cl.lossless.player.update.AvailableUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Cursor
import java.awt.FileDialog
import java.io.File
import java.text.Normalizer
import javax.swing.JFileChooser

private enum class Tab { SONGS, ALBUMS, ARTISTS, FOLDERS, PLAYLISTS, SETTINGS }

private fun String.searchKey(): String =
    Normalizer.normalize(lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

@Composable
fun App(player: Player, searchFocus: FocusRequester, onMiniPlayer: () -> Unit) {
    var language by remember { mutableStateOf(AppSettings.language) }
    CompositionLocalProvider(LocalLang provides (language ?: Lang.system())) {
        NeonTheme {
            Box(Modifier.fillMaxSize().background(Neon.Night)) {
                Library(player, searchFocus, language, onMiniPlayer) { language = it; AppSettings.language = it }
            }
        }
    }
}

@Composable
private fun Library(player: Player, searchFocus: FocusRequester, language: Lang?, onMiniPlayer: () -> Unit, onLanguage: (Lang?) -> Unit) {
    val scope = rememberCoroutineScope()
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var scanning by remember { mutableStateOf<Int?>(null) }
    val folders = remember { mutableStateListOf<String>().apply { addAll(AppSettings.folders) } }
    var tab by remember { mutableStateOf(Tab.SONGS) }
    var query by remember { mutableStateOf("") }
    var openAlbum by remember { mutableStateOf<String?>(null) }
    var openArtist by remember { mutableStateOf<String?>(null) }
    var openFolder by remember { mutableStateOf<String?>(null) }
    var openPlaylist by remember { mutableStateOf<String?>(null) }
    var panelOpen by remember { mutableStateOf(true) }
    var dialog by remember { mutableStateOf<TrackDialog?>(null) }
    val selection = remember { TrackSelection() }

    fun rescan() {
        if (scanning != null) return
        scanning = 0
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                LibraryScanner.scan(folders.toList(), tracks) { n -> scanning = n }
            }
            tracks = result.sortedForLibrary()
            selection.clear()
            scanning = null
        }
    }

    LaunchedEffect(Unit) {
        tracks = withContext(Dispatchers.IO) { LibraryScanner.loadCache() }.sortedForLibrary()
        if (folders.isNotEmpty()) rescan()
    }

    // Busca una versión nueva al abrir; si la hay, aparece el aviso en la barra superior.
    LaunchedEffect(Unit) { DesktopUpdater.check(quiet = true) }
    fun installUpdate(update: AvailableUpdate) {
        scope.launch { DesktopUpdater.install(update, player) }
    }

    val chooseTitle = t(S.chooseFolder)
    fun pickFolder(): String? {
        if (IsMac) {
            // En macOS el selector nativo (Finder) solo elige carpetas con esta propiedad.
            System.setProperty("apple.awt.fileDialogForDirectories", "true")
            val dialog = FileDialog(null as java.awt.Frame?, chooseTitle, FileDialog.LOAD)
            dialog.isVisible = true
            System.setProperty("apple.awt.fileDialogForDirectories", "false")
            return dialog.file?.let { File(dialog.directory, it).path }
        }
        val chooser = JFileChooser().apply {
            dialogTitle = chooseTitle
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.path else null
    }

    fun addFolder() {
        val path = pickFolder()
        if (path != null) {
            if (path !in folders) {
                folders += path
                AppSettings.folders = folders.toList()
                rescan()
            }
        }
    }

    fun removeFolder(path: String) {
        folders -= path
        AppSettings.folders = folders.toList()
        tracks = tracks.filterNot { it.path.startsWith(path) }
        rescan()
    }

    val state by player.state.collectAsState()
    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            position = player.positionMs()
            delay(150)
        }
    }

    // La búsqueda también encuentra formatos ("flac", "24/96") como en la maqueta.
    val searchIndex = remember(tracks) {
        tracks.map { "${it.title} ${it.artist} ${it.album} ${it.albumArtist} ${it.formatLabel}".searchKey() }
    }
    val filtered = remember(tracks, query) {
        val q = query.trim().searchKey()
        if (q.isEmpty()) tracks else tracks.filterIndexed { i, _ -> q in searchIndex[i] }
    }
    val various = t(S.variousArtists)
    val unknownArtist = t(S.unknownArtist)
    val albums = remember(filtered, various) { filtered.toAlbums(various) }
    val artists = remember(filtered, unknownArtist) { filtered.toArtists(unknownArtist) }
    val folderGroups = remember(filtered) {
        filtered.groupBy { it.folder }.toSortedMap(String.CASE_INSENSITIVE_ORDER).map { (path, list) -> path to list }
    }

    fun select(target: Tab) {
        tab = target
        openAlbum = null; openArtist = null; openFolder = null; openPlaylist = null
        selection.clear()
    }

    val actions = remember {
        TrackActions(
            editTags = { dialog = TrackDialog.EditTags(it) },
            editSelection = {
                val chosen = tracks.filter { it.path in selection.paths }
                if (chosen.isNotEmpty()) dialog = TrackDialog.EditTags(chosen)
            },
            // La carátula es del álbum (aunque esté repartido en varias carpetas): se guarda en todas sus pistas.
            findCover = { t ->
                val keys = tracks.albumKeysByPath()
                dialog = TrackDialog.FindCover(tracks.filter { keys[it.path] == keys[t.path] }.ifEmpty { listOf(t) })
            },
            findLyrics = { dialog = TrackDialog.FindLyrics(it) },
            addToPlaylist = { if (it.isNotEmpty()) dialog = TrackDialog.AddToPlaylist(it) },
        )
    }

    fun onTagsSaved(updated: List<Track>) {
        val byPath = updated.associateBy { it.path }
        tracks = tracks.map { byPath[it.path] ?: it }.sortedForLibrary()
        player.replaceTracks(byPath)
        DesktopLyrics.forget(byPath.keys)
        selection.clear()
        // Si el álbum abierto cambió de nombre, se sigue mostrando con el nombre nuevo.
        if (openAlbum != null) {
            val keys = tracks.albumKeysByPath()
            if (openAlbum !in keys.values) openAlbum = updated.firstOrNull()?.let { keys[it.path] }
        }
        val snapshot = tracks
        scope.launch(Dispatchers.IO) { LibraryScanner.saveCache(snapshot) }
    }

    // Canciones de la lista abierta: las de la biblioteca, o leídas del disco si están fuera de ella.
    val playlist = openPlaylist?.let { DesktopPlaylists[it] }
    val libraryByPath = remember(tracks) { tracks.associateBy { it.path } }
    var outside by remember { mutableStateOf<Map<String, Track?>>(emptyMap()) }
    LaunchedEffect(playlist?.paths, libraryByPath) {
        val unknown = playlist?.paths.orEmpty().filter { it !in libraryByPath && it !in outside }.distinct()
        if (unknown.isNotEmpty()) {
            val read = withContext(Dispatchers.IO) {
                unknown.associateWith { path -> File(path).takeIf { it.isFile }?.let { LibraryScanner.readTrack(it.toPath()) } }
            }
            outside = outside + read
        }
    }
    val playlistTracks = remember(playlist, libraryByPath, outside) {
        playlist?.paths.orEmpty().map { path ->
            libraryByPath[path] ?: outside[path] ?: Track(path, File(path).nameWithoutExtension, "", "", "", 0, 0, 0, "", 0, 0, 2, 0)
        }
    }
    val playlistMissing = remember(playlist, libraryByPath, outside) {
        playlist?.paths.orEmpty().filter { libraryByPath[it] == null && outside[it] == null }.toSet()
    }

    val track = state.current
    val showPanel = track != null && panelOpen
    val density = LocalDensity.current

    @Composable
    fun Panel() {
        if (track == null) return
        val left = Appearance.panelLeft
        if (!left) PanelResizeHandle(density) { dx -> Appearance.resizePanel(Appearance.panelWidth - dx) }
        RightPanel(
            track = track,
            state = state,
            positionMs = position,
            onSeek = player::seek,
            onPlayQueueItem = { player.playQueue(state.queue, it) },
            onClose = { panelOpen = false },
            modifier = Modifier.width(Appearance.panelWidth.dp).fillMaxHeight(),
        )
        if (left) PanelResizeHandle(density) { dx -> Appearance.resizePanel(Appearance.panelWidth + dx) }
    }

    CompositionLocalProvider(LocalTrackActions provides actions, LocalTrackSelection provides selection) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                TitleBar(
                    query = query,
                    onQuery = {
                        query = it
                        if (tab == Tab.SETTINGS) tab = Tab.SONGS
                    },
                    searchFocus = searchFocus,
                    outputFormat = state.outputFormat,
                    onUpdate = ::installUpdate,
                )
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Sidebar(
                        selected = tab,
                        trackCount = filtered.size,
                        playlistCount = DesktopPlaylists.all.size,
                        onSelect = ::select,
                        onAddFolder = ::addFolder,
                    )
                    if (showPanel && Appearance.panelLeft) Panel()

                    Column(Modifier.weight(1f).fillMaxHeight().background(Neon.Night)) {
                        scanning?.let { n ->
                            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                                Text(t(S.scanning, n), fontFamily = Mono, fontSize = 11.sp, color = Neon.Muted)
                                LinearProgressIndicator(
                                    Modifier.fillMaxWidth().padding(top = 6.dp).height(2.dp),
                                    color = Neon.Accent,
                                    trackColor = Neon.Border,
                                )
                            }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            val current = state.current?.path
                            when {
                                tab == Tab.SETTINGS -> SettingsScreen(
                                    folders = folders,
                                    language = language,
                                    outputFormat = state.outputFormat,
                                    onAdd = ::addFolder,
                                    onRemove = ::removeFolder,
                                    onRescan = ::rescan,
                                    onLanguage = onLanguage,
                                    onCheckUpdate = { scope.launch { DesktopUpdater.check() } },
                                    onInstallUpdate = ::installUpdate,
                                )
                                folders.isEmpty() && tracks.isEmpty() -> EmptyLibrary(onAdd = ::addFolder)
                                tab == Tab.SONGS -> TrackTable(
                                    filtered,
                                    currentPath = current,
                                    playing = state.playing,
                                    header = { SongsHeader(folders, filtered) { player.playQueue(filtered, 0) } },
                                ) { player.playQueue(filtered, it) }
                                tab == Tab.ALBUMS -> {
                                    val album = openAlbum?.let { key -> albums.firstOrNull { it.key == key } }
                                    if (album != null) {
                                        AlbumDetail(album, current, state.playing, onBack = { openAlbum = null }) { player.playQueue(album.tracks, it) }
                                    } else {
                                        AlbumGrid(albums) { openAlbum = it.key }
                                    }
                                }
                                tab == Tab.ARTISTS -> {
                                    val artist = openArtist?.let { name -> artists.firstOrNull { it.name == name } }
                                    if (artist != null) {
                                        ArtistDetail(artist, current, state.playing, onBack = { openArtist = null }) { player.playQueue(artist.tracks, it) }
                                    } else {
                                        ArtistGrid(artists) { openArtist = it.name }
                                    }
                                }
                                tab == Tab.PLAYLISTS -> {
                                    if (playlist != null) {
                                        PlaylistDetail(
                                            playlist, playlistTracks, playlistMissing, current, state.playing,
                                            onBack = { openPlaylist = null },
                                            onRename = { dialog = TrackDialog.PlaylistName(playlist.id, playlist.name) },
                                            onDelete = { dialog = TrackDialog.DeletePlaylist(playlist.id, playlist.name) },
                                        ) { i ->
                                            // Solo se encolan las que existen; -1 es "Reproducir todo".
                                            val playable = playlistTracks.filter { it.path !in playlistMissing }
                                            val start = if (i < 0) 0 else playlistTracks.take(i).count { it.path !in playlistMissing }
                                            if (playable.isNotEmpty()) player.playQueue(playable, start)
                                        }
                                    } else {
                                        PlaylistList(
                                            onNew = { dialog = TrackDialog.PlaylistName(null, "") },
                                            onOpen = { openPlaylist = it.id },
                                            onRename = { dialog = TrackDialog.PlaylistName(it.id, it.name) },
                                            onDelete = { dialog = TrackDialog.DeletePlaylist(it.id, it.name) },
                                        )
                                    }
                                }
                                else -> {
                                    val folder = openFolder?.let { path -> folderGroups.firstOrNull { it.first == path } }
                                    if (folder != null) {
                                        val list = remember(folder) { folder.second.sortedWith(compareBy({ it.discNumber }, { it.trackNumber }, { it.file.name.lowercase() })) }
                                        FolderDetail(folder.first, list, current, state.playing, onBack = { openFolder = null }) { player.playQueue(list, it) }
                                    } else {
                                        FolderList(folderGroups) { openFolder = it }
                                    }
                                }
                            }
                        }
                    }

                    if (showPanel && !Appearance.panelLeft) Panel()
                }
                PlayerBar(
                    player, state, position,
                    panelOpen = panelOpen && state.current != null,
                    onMiniPlayer = onMiniPlayer,
                    onEqualizer = { dialog = TrackDialog.Equalizer },
                ) { panelOpen = !panelOpen }
            }
            dialog?.let { d -> TrackDialogHost(d, player, onDismiss = { dialog = null }, onUpdated = ::onTagsSaved) }
        }
    }
}

/**
 * Borde del panel de reproducción: una línea de 1 dp con una zona de 7 dp que se arrastra para
 * cambiar el ancho del panel ([onDrag] recibe el desplazamiento horizontal en dp).
 */
@Composable
private fun PanelResizeHandle(density: Density, onDrag: (Float) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    Box(
        Modifier.width(7.dp).fillMaxHeight()
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { px -> onDrag(with(density) { px.toDp().value }) },
                onDragStarted = { dragging = true },
                onDragStopped = { dragging = false; Appearance.savePanelWidth() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.width(if (hovered || dragging) 2.dp else 1.dp).fillMaxHeight()
                .background(if (hovered || dragging) Neon.Accent.copy(alpha = 0.7f) else Neon.Border),
        )
    }
}

/** Barra superior: logo, versión, búsqueda global (Ctrl+F / ⌘F) y estado de la salida de audio. */
@Composable
private fun TitleBar(
    query: String,
    onQuery: (String) -> Unit,
    searchFocus: FocusRequester,
    outputFormat: String?,
    onUpdate: (AvailableUpdate) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).background(Neon.Night).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.width(228.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(AppIcon, contentDescription = null, modifier = Modifier.size(26.dp).glow(Neon.Accent, 8.dp, RoundedCornerShape(6.dp)))
            Spacer(Modifier.width(8.dp))
            Text("ResuenApp", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Neon.Text, letterSpacing = 0.6.sp)
            Spacer(Modifier.width(8.dp))
            NeonBadge("v$APP_VERSION")
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            SearchField(query, onQuery, searchFocus, Modifier.widthIn(max = 460.dp).fillMaxWidth())
        }
        Row(Modifier.width(228.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            UpdatePill(onUpdate)
            if (outputFormat != null) {
                val shape = RoundedCornerShape(50)
                Row(
                    Modifier.clip(shape).background(Neon.Accent.copy(alpha = 0.10f)).border(1.dp, Neon.Accent.copy(alpha = 0.30f), shape)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Bolt, null, tint = Neon.Accent, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(outputFormat.uppercase(), color = Neon.Accent, fontFamily = Mono, fontSize = 10.sp, maxLines = 1)
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Neon.Border))
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, focus: FocusRequester, modifier: Modifier) {
    val shape = RoundedCornerShape(50)
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier.height(30.dp).clip(shape).background(Neon.Card.copy(alpha = 0.8f))
            .border(1.dp, if (focused) Neon.Accent.copy(alpha = 0.6f) else Neon.Border, shape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = Neon.Muted, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text(t(S.searchHint), color = Neon.Faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(color = Neon.Text, fontSize = 12.sp, fontFamily = Appearance.font.family),
                cursorBrush = SolidColor(Neon.Accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
                    .onFocusChanged { focused = it.isFocused },
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                Icons.Rounded.Close, t(S.back), tint = Neon.Muted,
                modifier = Modifier.size(15.dp).clip(RoundedCornerShape(50)).clickable { onQuery("") },
            )
        } else {
            NeonBadge(if (IsMac) "⌘F" else "Ctrl+F", color = Neon.Faint)
        }
    }
}

private val SIDEBAR_RAIL = 60.dp
private val SIDEBAR_OPEN = 212.dp

/**
 * Menú lateral al estilo de Magnific: una tarjeta redondeada separada del contenido que alterna,
 * con el botón de arriba, entre un riel de solo íconos y el menú con los nombres. Arriba va la
 * acción principal (agregar carpeta) en color de acento; abajo, Ajustes y el cambio de tema, que
 * en el riel se apilan y abierto quedan en fila.
 */
@Composable
private fun Sidebar(selected: Tab, trackCount: Int, playlistCount: Int, onSelect: (Tab) -> Unit, onAddFolder: () -> Unit) {
    val open = !Appearance.sidebarCollapsed
    val width by animateDpAsState(if (open) SIDEBAR_OPEN else SIDEBAR_RAIL)
    val card = RoundedCornerShape(14.dp)
    Column(
        // Margen parejo y sombra para que la tarjeta se vea flotando sobre el fondo de la ventana.
        Modifier.padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 10.dp).width(width).fillMaxHeight()
            .shadow(if (Appearance.palette.dark) 18.dp else 10.dp, card, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(card).background(Neon.Surface).border(1.dp, Neon.Border, card)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        // Botón para abrir o recoger el menú, siempre en el mismo lugar.
        Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
            if (open) {
                SectionLabel(t(S.library), Modifier.padding(start = 10.dp).weight(1f), color = Neon.Muted)
            }
            NavIconButton(
                if (open) Icons.AutoMirrored.Rounded.MenuOpen else Icons.Rounded.Menu,
                if (open) t(S.collapseSidebar) else t(S.expandSidebar),
                tooltip = !open,
                modifier = if (open) Modifier else Modifier.fillMaxWidth(),
                onClick = Appearance::toggleSidebar,
            )
        }
        Spacer(Modifier.height(6.dp))
        PrimaryNavButton(t(S.addFolder), open, onAddFolder)
        Spacer(Modifier.height(10.dp))
        NavItem(Icons.Rounded.MusicNote, t(S.songs), selected == Tab.SONGS, open, badge = "$trackCount") { onSelect(Tab.SONGS) }
        NavItem(Icons.Rounded.Album, t(S.albums), selected == Tab.ALBUMS, open) { onSelect(Tab.ALBUMS) }
        NavItem(Icons.Rounded.Mic, t(S.artists), selected == Tab.ARTISTS, open) { onSelect(Tab.ARTISTS) }
        NavItem(Icons.Rounded.Folder, t(S.folders), selected == Tab.FOLDERS, open) { onSelect(Tab.FOLDERS) }
        Box(Modifier.padding(horizontal = 10.dp, vertical = 10.dp).fillMaxWidth().height(1.dp).background(Neon.Border))
        NavItem(Icons.AutoMirrored.Rounded.QueueMusic, t(S.playlists), selected == Tab.PLAYLISTS, open, badge = "$playlistCount") { onSelect(Tab.PLAYLISTS) }
        Spacer(Modifier.weight(1f))

        val dark = Appearance.palette.dark
        val themeIcon = if (dark) Icons.Rounded.DarkMode else Icons.Rounded.LightMode
        val toggleTheme = { Appearance.set(if (dark) ThemeMode.LIGHT else ThemeMode.NIGHT) }
        val settingsTint = if (selected == Tab.SETTINGS) Neon.Accent else Neon.Muted
        if (open) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                NavIconButton(Icons.Rounded.Settings, t(S.settings), tint = settingsTint, onClick = { onSelect(Tab.SETTINGS) })
                NavIconButton(themeIcon, t(S.toggleTheme), onClick = toggleTheme)
            }
        } else {
            NavIconButton(Icons.Rounded.Settings, t(S.settings), tint = settingsTint, tooltip = true, modifier = Modifier.fillMaxWidth(), onClick = { onSelect(Tab.SETTINGS) })
            NavIconButton(themeIcon, t(S.toggleTheme), tooltip = true, modifier = Modifier.fillMaxWidth(), onClick = toggleTheme)
        }
    }
}

/** Globo con el nombre a la derecha del riel, para cuando solo se ven los íconos. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RailTooltip(label: String, enabled: Boolean, content: @Composable () -> Unit) {
    if (!enabled) { content(); return }
    TooltipArea(
        tooltip = {
            Text(
                label, color = Neon.Text, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Neon.Card)
                    .border(1.dp, Neon.Border, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
            )
        },
        tooltipPlacement = TooltipPlacement.ComponentRect(anchor = Alignment.CenterEnd, alignment = Alignment.CenterEnd, offset = DpOffset(10.dp, 0.dp)),
    ) { content() }
}

@Composable
private fun NavIconButton(
    icon: ImageVector, label: String, tint: Color = Neon.Muted, tooltip: Boolean = false,
    modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    RailTooltip(label, tooltip) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(if (hovered) Neon.Hover else Color.Transparent)
                    .hoverable(interaction).clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, label, tint = if (hovered) Neon.TextStrong else tint, modifier = Modifier.size(17.dp)) }
        }
    }
}

/** Acción principal en color de acento, como el botón "Crear" de Magnific. */
@Composable
private fun PrimaryNavButton(label: String, open: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    RailTooltip(label, !open) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (hovered) Neon.Hover else Color.Transparent)
                .hoverable(interaction).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (open) Arrangement.Start else Arrangement.Center,
        ) {
            Box(
                Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(Neon.Accent),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Add, label, tint = Neon.Night, modifier = Modifier.size(18.dp)) }
            if (open) {
                Spacer(Modifier.width(10.dp))
                Text(label, color = Neon.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
            }
        }
    }
}

/** Abierto: ícono, nombre y contador. Riel: solo el ícono, con el nombre en un globo. */
@Composable
private fun NavItem(icon: ImageVector, label: String, selected: Boolean, open: Boolean, badge: String? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val color = when {
        selected -> Neon.TextStrong
        hovered -> Neon.Text
        else -> Neon.Muted
    }
    RailTooltip(label, !open) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 1.dp).clip(shape)
                .background(
                    when {
                        selected -> Neon.Accent.copy(alpha = 0.12f)
                        hovered -> Neon.Hover
                        else -> Color.Transparent
                    },
                )
                .hoverable(interaction)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (open) Arrangement.Start else Arrangement.Center,
        ) {
            // El ícono lleva el acento al estar elegido; el resto, el gris del menú.
            Icon(icon, label, tint = if (selected) Neon.Accent else color, modifier = Modifier.size(17.dp))
            if (open) {
                Spacer(Modifier.width(12.dp))
                Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
                if (badge != null) Text(badge, color = color.copy(alpha = 0.6f), fontFamily = Mono, fontSize = 10.sp, maxLines = 1, softWrap = false)
            }
        }
    }
}

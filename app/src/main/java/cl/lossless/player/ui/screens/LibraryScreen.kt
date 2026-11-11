package cl.lossless.player.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cl.lossless.player.R
import cl.lossless.player.covers.CoverRef
import cl.lossless.player.library.AlbumGroup
import cl.lossless.player.library.FolderSort
import cl.lossless.player.library.LibrarySearch
import cl.lossless.player.library.Track
import cl.lossless.player.library.folderDisplayPath
import cl.lossless.player.library.folderSegments
import cl.lossless.player.library.sortedFolders
import cl.lossless.player.ui.LibraryUiState
import cl.lossless.player.ui.components.CoverImage
import cl.lossless.player.ui.components.EmptyState
import cl.lossless.player.ui.components.FolderSortButton
import cl.lossless.player.ui.components.SearchField
import cl.lossless.player.ui.components.ScreenHeader
import cl.lossless.player.ui.components.SegmentedTabs
import cl.lossless.player.ui.components.SelectionBar
import cl.lossless.player.ui.components.TrackActions
import cl.lossless.player.ui.components.TrackList
import cl.lossless.player.ui.components.TrackSelection
import cl.lossless.player.ui.components.albumTrackNumber
import cl.lossless.player.ui.components.formatDuration

private enum class LibraryTab { TRACKS, ALBUMS, FOLDERS }

/** Carpeta plana de la biblioteca (pestaña "Carpetas"). */
private data class FolderGroup(
    val key: String,
    val name: String,
    val path: String,
    val segments: List<String>,
    val tracks: List<Track>,
) {
    val lastModified: Long = tracks.maxOf { it.dateModified }
}

@Composable
fun LibraryScreen(
    state: LibraryUiState,
    actions: TrackActions,
    onPickFolder: () -> Unit,
    onRescan: () -> Unit,
    onEditCover: (AlbumGroup) -> Unit,
    onExcludeFolder: (List<String>) -> Unit,
    onSyncLyrics: (List<Track>) -> Unit,
    onFolderSort: (FolderSort) -> Unit,
    onEditTags: (List<Track>) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(LibraryTab.TRACKS.ordinal) }
    // Canciones marcadas para asignarles álbum y artista; se vacía al recargar la biblioteca.
    val selection = remember { TrackSelection() }
    LaunchedEffect(state.tracks) { selection.clear() }
    val editSelected = { onEditTags(state.tracks.filter { it in selection }) }
    val addSelectedToPlaylist = { actions.addAllToPlaylist(state.tracks.filter { it in selection }) { selection.clear() } }
    var query by rememberSaveable { mutableStateOf("") }
    BackHandler(enabled = query.isNotEmpty()) { query = "" }
    var openAlbumKey by rememberSaveable { mutableStateOf<String?>(null) }
    var openFolderKey by rememberSaveable { mutableStateOf<String?>(null) }

    val folders = remember(state.tracks) {
        state.tracks.groupBy { it.folderKey }.map { (key, tracks) ->
            val segments = tracks.first().folderSegments
            FolderGroup(key, tracks.first().folderName, folderDisplayPath(segments), segments, tracks)
        }
    }
    val visibleFolders = remember(folders, query, state.folderSort) {
        folders
            .filter { LibrarySearch.matches(query, it.name, it.path) }
            .sortedFolders(state.folderSort, { it.name }, { it.tracks.size }, { it.lastModified })
    }
    val visibleAlbums = remember(state.albums, query) { LibrarySearch.albums(state.albums, query) }

    // Detalles (álbum o carpeta) sobre la pestaña actual
    state.albums.firstOrNull { it.key == openAlbumKey }?.let { album ->
        BackHandler { openAlbumKey = null }
        CollectionDetail(
            title = album.title,
            subtitle = album.artist,
            tracks = album.tracks,
            cover = state.covers[album.key],
            actions = actions,
            onBack = { openAlbumKey = null },
            onEditCover = { onEditCover(album) },
            onSyncLyrics = { onSyncLyrics(album.tracks) },
            selection = selection,
            onEditSelected = editSelected,
            onAddSelectedToPlaylist = addSelectedToPlaylist,
        )
        BackHandler(enabled = selection.active) { selection.clear() }
        return
    }
    folders.firstOrNull { it.key == openFolderKey }?.let { folder ->
        BackHandler { openFolderKey = null }
        CollectionDetail(
            title = folder.name,
            subtitle = folder.path,
            tracks = folder.tracks,
            cover = null,
            actions = actions,
            onBack = { openFolderKey = null },
            onEditCover = null,
            onSyncLyrics = { onSyncLyrics(folder.tracks) },
            selection = selection,
            onEditSelected = editSelected,
            onAddSelectedToPlaylist = addSelectedToPlaylist,
        )
        BackHandler(enabled = selection.active) { selection.clear() }
        return
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.library_title),
            trailingText = state.location?.displayPath ?: stringResource(R.string.library_whole_device),
        )
        SegmentedTabs(
            options = listOf(
                if (state.tracks.isEmpty()) stringResource(R.string.library_tab_tracks)
                else stringResource(R.string.library_tab_tracks_count, state.tracks.size),
                stringResource(R.string.library_tab_albums),
                stringResource(R.string.library_folders),
            ),
            selected = tab,
            onSelect = { if (it != tab) { query = ""; selection.clear() }; tab = it },
        )
        when {
            state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            state.tracks.isEmpty() -> EmptyState(
                icon = Icons.Rounded.LibraryMusic,
                title = stringResource(R.string.library_empty_title),
                message = stringResource(R.string.library_empty_message),
                actionLabel = stringResource(R.string.library_pick_folder),
                onAction = onPickFolder,
                secondaryLabel = stringResource(R.string.library_rescan),
                onSecondary = onRescan,
            )
            else -> when (LibraryTab.entries[tab]) {
                LibraryTab.TRACKS -> Column(Modifier.fillMaxSize()) {
                    BackHandler(enabled = selection.active) { selection.clear() }
                    if (selection.active) SelectionBar(selection, state.tracks, onEdit = editSelected, onAddToPlaylist = addSelectedToPlaylist)
                    TrackList(
                        state.tracks, actions, Modifier.fillMaxSize(),
                        selection = selection,
                        header = {
                            if (!selection.active) item(key = "select-hint") {
                                Text(
                                    stringResource(R.string.library_select_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                }
                LibraryTab.ALBUMS -> Column(Modifier.fillMaxSize()) {
                    SearchField(
                        query = query,
                        onQueryChange = { query = it },
                        placeholder = stringResource(R.string.library_search_albums),
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    )
                    if (visibleAlbums.isEmpty()) NoResults(query)
                    else AlbumGrid(visibleAlbums, state.covers) { openAlbumKey = it.key }
                }
                LibraryTab.FOLDERS -> Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SearchField(
                            query = query,
                            onQueryChange = { query = it },
                            placeholder = stringResource(R.string.library_search_folders),
                            modifier = Modifier.weight(1f),
                        )
                        FolderSortButton(state.folderSort, onFolderSort)
                    }
                    if (visibleFolders.isEmpty()) NoResults(query)
                    else FolderList(visibleFolders, onExclude = { onExcludeFolder(it.segments) }) { openFolderKey = it.key }
                }
            }
        }
    }
}

/** Aviso de búsqueda sin coincidencias. */
@Composable
fun NoResults(query: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), Alignment.TopCenter) {
        Text(
            stringResource(R.string.library_search_no_results, query),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AlbumGrid(albums: List<AlbumGroup>, covers: Map<String, CoverRef>, onOpen: (AlbumGroup) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(albums, key = { it.key }) { album ->
            Surface(
                onClick = { onOpen(album) },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(10.dp)) {
                    CoverImage(
                        Modifier.fillMaxWidth().aspectRatio(1f),
                        cover = covers[album.key],
                        shape = RoundedCornerShape(12.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(album.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        album.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        pluralStringResource(R.plurals.library_track_count, album.tracks.size, album.tracks.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderList(
    folders: List<FolderGroup>,
    onExclude: (FolderGroup) -> Unit,
    onOpen: (FolderGroup) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(folders, key = { it.key }) { folder ->
            FolderCard(
                name = folder.name,
                path = folder.path,
                detail = pluralStringResource(R.plurals.library_track_count, folder.tracks.size, folder.tracks.size),
                onExclude = { onExclude(folder) },
            ) { onOpen(folder) }
        }
    }
}

/** Fila de carpeta reutilizada por la biblioteca y el explorador. */
@Composable
fun FolderCard(
    name: String,
    path: String?,
    detail: String,
    onExclude: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(46.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (path != null) {
                    Text(
                        path,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.StartEllipsis,
                    )
                }
            }
            Text(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onExclude != null) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.library_more_options))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_exclude_folder)) },
                            leadingIcon = { Icon(Icons.Rounded.FolderOff, null) },
                            onClick = { menu = false; onExclude() },
                        )
                    }
                }
            } else {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Detalle de un álbum o carpeta: cabecera con carátula, botones y lista de pistas. */
@Composable
private fun CollectionDetail(
    title: String,
    subtitle: String,
    tracks: List<Track>,
    cover: CoverRef?,
    actions: TrackActions,
    onBack: () -> Unit,
    onEditCover: (() -> Unit)?,
    onSyncLyrics: (() -> Unit)? = null,
    selection: TrackSelection? = null,
    onEditSelected: () -> Unit = {},
    onAddSelectedToPlaylist: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = "", onBack = onBack)
        if (selection != null && selection.active) SelectionBar(selection, tracks, onEdit = onEditSelected, onAddToPlaylist = onAddSelectedToPlaylist)
        TrackList(
            tracks = tracks,
            actions = actions,
            number = ::albumTrackNumber,
            modifier = Modifier.fillMaxSize(),
            selection = selection,
            header = {
                item(key = "header") {
                    CollectionHeader(title, subtitle, tracks, cover, onEditCover,
                        onPlay = { actions.play(tracks, 0) },
                        onShuffle = { actions.shuffle(tracks) },
                        onSyncLyrics = onSyncLyrics)
                }
            },
        )
    }
}

@Composable
fun CollectionHeader(
    title: String,
    subtitle: String,
    tracks: List<Track>,
    cover: CoverRef?,
    onEditCover: (() -> Unit)?,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onSyncLyrics: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoverImage(Modifier.size(112.dp), cover = cover, shape = RoundedCornerShape(16.dp))
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    pluralStringResource(R.plurals.library_track_count, tracks.size, tracks.size) +
                        " · " + formatDuration(tracks.sumOf { it.durationMs }),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onPlay, enabled = tracks.isNotEmpty()) {
                Icon(Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.library_play))
            }
            FilledTonalButton(onClick = onShuffle, enabled = tracks.isNotEmpty()) {
                Icon(Icons.Rounded.Shuffle, null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.library_shuffle))
            }
            if (onEditCover != null) {
                OutlinedIconButton(onClick = onEditCover) {
                    Icon(Icons.Rounded.Image, contentDescription = stringResource(R.string.library_change_cover))
                }
            }
            if (onSyncLyrics != null) {
                OutlinedIconButton(onClick = onSyncLyrics, enabled = tracks.isNotEmpty()) {
                    Icon(Icons.Rounded.Lyrics, contentDescription = stringResource(R.string.library_sync_lyrics))
                }
            }
        }
    }
}

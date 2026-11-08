package cl.lossless.player.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import cl.lossless.player.R
import cl.lossless.player.library.Track
import cl.lossless.player.playback.PlaybackStatus
import cl.lossless.player.library.FolderExclusions
import cl.lossless.player.playback.eq.EqPresets
import cl.lossless.player.playback.eq.nameRes
import cl.lossless.player.ui.screens.ExcludedFoldersScreen
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import cl.lossless.player.ui.components.AddToPlaylistDialog
import cl.lossless.player.ui.components.CoverDialog
import cl.lossless.player.ui.components.EmptyState
import cl.lossless.player.ui.components.MiniPlayer
import cl.lossless.player.ui.components.PlaylistNameDialog
import cl.lossless.player.ui.components.TagEditDialog
import cl.lossless.player.ui.components.TrackActions
import cl.lossless.player.ui.components.displayName
import cl.lossless.player.ui.equalizer.EqualizerScreen
import cl.lossless.player.ui.equalizer.EqualizerViewModel
import cl.lossless.player.ui.lyrics.AlignServerDialog
import cl.lossless.player.ui.lyrics.LyricsViewModel
import cl.lossless.player.ui.player.PlayerActions
import cl.lossless.player.ui.player.rememberPlayerUiState
import cl.lossless.player.ui.screens.FoldersScreen
import cl.lossless.player.ui.screens.LibraryScreen
import cl.lossless.player.ui.screens.NowPlayingScreen
import cl.lossless.player.ui.screens.PlaylistsScreen
import cl.lossless.player.ui.screens.SettingsScreen

private enum class Destination(@StringRes val labelRes: Int, val icon: ImageVector, val selectedIcon: ImageVector) {
    LIBRARY(R.string.library_library, Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic),
    FOLDERS(R.string.library_folders, Icons.Outlined.Folder, Icons.Rounded.Folder),
    PLAYLISTS(R.string.library_playlists, Icons.AutoMirrored.Outlined.QueueMusic, Icons.AutoMirrored.Rounded.QueueMusic),
    SETTINGS(R.string.library_settings, Icons.Outlined.Settings, Icons.Rounded.Settings),
}

private const val AUDIO_PERMISSION = Manifest.permission.READ_MEDIA_AUDIO

@Composable
fun LosslessApp(
    player: Player?,
    vm: LibraryViewModel = viewModel(),
    playlistsVm: PlaylistsViewModel = viewModel(),
    equalizerVm: EqualizerViewModel = viewModel(),
    lyricsVm: LyricsViewModel = viewModel(),
    /** Cambia cada vez que se pide abrir "Reproduciendo" desde fuera (notificación). */
    openNowPlayingSignal: Int = 0,
) {
    val context = LocalContext.current
    val state by vm.ui.collectAsStateWithLifecycle()
    val playlists by playlistsVm.playlists.collectAsStateWithLifecycle()
    val playerState by rememberPlayerUiState(player)
    val playerActions = remember(player) { PlayerActions(player) }
    val audioSource by PlaybackStatus.source.collectAsStateWithLifecycle()
    val audioOutput by PlaybackStatus.output.collectAsStateWithLifecycle()

    // ---------- Permisos ----------
    fun granted() = context.checkSelfPermission(AUDIO_PERMISSION) == PackageManager.PERMISSION_GRANTED
    var hasPermission by remember { mutableStateOf(granted()) }
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        askedOnce = true
        hasPermission = result[AUDIO_PERMISSION] == true
    }
    fun requestPermission() = permissionLauncher.launch(
        arrayOf(AUDIO_PERMISSION, Manifest.permission.POST_NOTIFICATIONS)
    )
    LaunchedEffect(Unit) { if (!hasPermission) requestPermission() }
    // Al volver de los ajustes del sistema
    LifecycleResumeEffect(Unit) {
        hasPermission = granted()
        onPauseOrDispose { }
    }
    // Sin permiso igual se puede leer una carpeta elegida con el selector del sistema.
    LaunchedEffect(hasPermission) { vm.reload() }

    // Tras rechazar "no volver a preguntar", el diálogo ya no aparece: hay que ir a ajustes.
    val permanentlyDenied = askedOnce && !hasPermission &&
        (context as? Activity)?.shouldShowRequestPermissionRationale(AUDIO_PERMISSION) == false

    // ---------- Selector de carpeta (SAF: interna, SD, USB) ----------
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -> uri?.let(vm::selectFolder) }
    val pickFolder = { folderPicker.launch(null) }

    // ---------- Etiquetas: autorización del sistema para modificar canciones fuera de la carpeta ----------
    val tagConsent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> vm.onTagWriteConsent(result.resultCode == Activity.RESULT_OK) }
    LaunchedEffect(state.tagWriteConsent) {
        state.tagWriteConsent?.let { tagConsent.launch(IntentSenderRequest.Builder(it).build()) }
    }

    // ---------- Ecualizador (propio, dentro del pipeline de audio) ----------
    var equalizerOpen by rememberSaveable { mutableStateOf(false) }
    val equalizer by equalizerVm.settings.collectAsStateWithLifecycle()
    val openEqualizer = { equalizerOpen = true }

    // ---------- Carpetas omitidas ----------
    var exclusionsOpen by rememberSaveable { mutableStateOf(false) }
    var alignServerOpen by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val excludeFolder: (List<String>) -> Unit = { segments ->
        val key = FolderExclusions.key(segments)
        vm.setFolderExcluded(key, true)
        scope.launch {
            val result = snackbar.showSnackbar(
                message = context.getString(R.string.library_folder_excluded, segments.lastOrNull().orEmpty()),
                actionLabel = context.getString(R.string.library_undo),
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) vm.setFolderExcluded(key, false)
        }
    }

    // ---------- Sincronizar letras de un álbum o carpeta ----------
    val syncLyrics: (List<Track>) -> Unit = { tracks ->
        lyricsVm.syncAll(tracks)
        Toast.makeText(
            context,
            context.resources.getQuantityString(R.plurals.library_lyrics_sync_queued, tracks.size, tracks.size),
            Toast.LENGTH_SHORT,
        ).show()
    }

    // ---------- Acciones de pista ----------
    var addToPlaylistFor by remember { mutableStateOf<PlaylistRequest?>(null) }
    var createPlaylistWith by remember { mutableStateOf<PlaylistRequest?>(null) }
    val favorites = remember(playlists) { playlists.firstOrNull { it.isFavorites }?.trackUris?.toSet().orEmpty() }
    fun artworkFor(track: Track) = state.coverFor(track)?.let { Uri.fromFile(it.file) }
    val trackActions = TrackActions(
        currentMediaId = playerState.current?.mediaId,
        favorites = favorites,
        coverFor = state::coverFor,
        play = { tracks, index -> playerActions.playTracks(tracks, index, ::artworkFor) },
        shuffle = { tracks -> playerActions.shuffleTracks(tracks, ::artworkFor) },
        playNext = { playerActions.playNext(it, artworkFor(it)) },
        enqueue = { playerActions.enqueue(it, artworkFor(it)) },
        toggleFavorite = { playlistsVm.toggleFavorite(it.uri.toString()) },
        addToPlaylist = { addToPlaylistFor = PlaylistRequest(listOf(it)) },
        addAllToPlaylist = { tracks, onAdded -> if (tracks.isNotEmpty()) addToPlaylistFor = PlaylistRequest(tracks, onAdded) },
    )

    var destination by rememberSaveable { mutableStateOf(Destination.LIBRARY) }
    var nowPlayingOpen by rememberSaveable { mutableStateOf(false) }
    val currentTrack = playerState.current?.let { state.tracksById[it.mediaId] }
    val lyricsState by lyricsVm.state.collectAsStateWithLifecycle()

    LaunchedEffect(openNowPlayingSignal) {
        if (openNowPlayingSignal > 0) {
            equalizerOpen = false
            exclusionsOpen = false
            nowPlayingOpen = true
        }
    }

    BackHandler(
        enabled = destination != Destination.LIBRARY && !nowPlayingOpen && !equalizerOpen && !exclusionsOpen
    ) {
        destination = Destination.LIBRARY
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                Column {
                    MiniPlayer(
                        state = playerState,
                        number = "%02d".format(playerState.currentIndex + 1),
                        cover = currentTrack?.let(state::coverFor),
                        onPlayPause = playerActions::togglePlayPause,
                        onNext = playerActions::next,
                        onExpand = { nowPlayingOpen = true },
                        onPrevious = playerActions::previousTrack,
                        swipeToSkip = state.swipeToSkip,
                    )
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Destination.entries.forEach { d ->
                            val selected = d == destination
                            NavigationBarItem(
                                selected = selected,
                                onClick = { destination = d },
                                icon = { Icon(if (selected) d.selectedIcon else d.icon, contentDescription = null) },
                                label = { Text(stringResource(d.labelRes)) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                if (!hasPermission && state.location == null && destination != Destination.SETTINGS) {
                    EmptyState(
                        icon = Icons.Rounded.Lock,
                        title = stringResource(R.string.library_permission_title),
                        message = stringResource(R.string.library_permission_message, stringResource(R.string.app_name)),
                        actionLabel = if (permanentlyDenied) stringResource(R.string.library_permission_open_settings)
                        else stringResource(R.string.library_permission_grant),
                        onAction = {
                            if (permanentlyDenied) {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                        .setData(Uri.fromParts("package", context.packageName, null))
                                )
                            } else {
                                requestPermission()
                            }
                        },
                        secondaryLabel = stringResource(R.string.library_permission_pick_folder),
                        onSecondary = pickFolder,
                    )
                } else when (destination) {
                    Destination.LIBRARY -> LibraryScreen(
                        state = state,
                        actions = trackActions,
                        onPickFolder = pickFolder,
                        onRescan = vm::reload,
                        onEditCover = vm::openCoverDialog,
                        onExcludeFolder = excludeFolder,
                        onSyncLyrics = syncLyrics,
                        onFolderSort = vm::setFolderSort,
                        onEditTags = vm::openTagEditor,
                    )
                    Destination.FOLDERS -> FoldersScreen(
                        tracks = state.tracks,
                        actions = trackActions,
                        onPickFolder = pickFolder,
                        onExcludeFolder = excludeFolder,
                        folderSort = state.folderSort,
                        onFolderSort = vm::setFolderSort,
                    )
                    Destination.PLAYLISTS -> PlaylistsScreen(
                        playlists = playlists,
                        tracksById = state.tracksById,
                        actions = trackActions,
                        onCreate = { playlistsVm.create(it) },
                        onRename = { p, name -> playlistsVm.rename(p.id, name) },
                        onDelete = { playlistsVm.delete(it.id) },
                        onRemoveTrack = { p, t -> playlistsVm.remove(p.id, t.uri.toString()) },
                    )
                    Destination.SETTINGS -> SettingsScreen(
                        state = state,
                        audioSource = audioSource,
                        audioOutput = audioOutput,
                        onPickFolder = pickFolder,
                        onShowWholeLibrary = vm::showWholeLibrary,
                        onRescan = vm::reload,
                        onFetchCovers = vm::fetchMissingCovers,
                        onCancelFetch = vm::cancelFetch,
                        onSaveCoversInFolder = vm::setSaveCoversInFolder,
                        onThemeMode = vm::setThemeMode,
                        onDynamicColor = vm::setDynamicColor,
                        onAccent = vm::setAccent,
                        onTintedText = vm::setTintedText,
                        equalizerSummary = when {
                            !equalizer.enabled -> stringResource(R.string.library_equalizer_off)
                            else -> stringResource(R.string.library_equalizer_on, stringResource(EqPresets.byId(equalizer.presetId).nameRes))
                        },
                        onOpenEqualizer = openEqualizer,
                        onOpenExclusions = { exclusionsOpen = true },
                        lyricsOnline = lyricsState.searchOnline,
                        lyricsAutoSync = lyricsState.autoSync,
                        onLyricsAutoSync = lyricsVm::setAutoSync,
                        alignServerUrl = lyricsState.alignServerUrl,
                        onOpenAlignServer = { alignServerOpen = true },
                        onLyricsOnline = lyricsVm::setSearchOnline,
                        onClearLyricsCache = {
                            lyricsVm.clearCache()
                            Toast.makeText(context, context.getString(R.string.library_lyrics_cache_cleared), Toast.LENGTH_SHORT).show()
                        },
                        onFont = vm::setFont,
                        onTextSize = vm::setTextSize,
                        onNetworkMode = vm::setNetworkMode,
                        onClearSyncQueue = vm::clearSyncQueue,
                        onSwipeToSkip = vm::setSwipeToSkip,
                        onMediaKeysSkip = vm::setMediaKeysSkip,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = nowPlayingOpen && playerState.current != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            BackHandler { nowPlayingOpen = false }
            NowPlayingScreen(
                state = playerState,
                track = currentTrack,
                cover = currentTrack?.let(state::coverFor),
                isFavorite = playerState.current?.mediaId in favorites,
                audioOutput = audioOutput,
                equalizerActive = equalizer.isProcessing,
                player = playerActions,
                onCollapse = { nowPlayingOpen = false },
                onToggleFavorite = { playerState.current?.let { playlistsVm.toggleFavorite(it.mediaId) } },
                onOpenEqualizer = openEqualizer,
                lyrics = lyricsState,
                onLoadLyrics = { force -> lyricsVm.loadFor(currentTrack, force) },
                onAdjustLyricsOffset = lyricsVm::adjustOffset,
                onResetLyricsOffset = lyricsVm::resetOffset,
                onSaveLyricsSync = lyricsVm::saveUserSync,
                onAlignLyricsNow = lyricsVm::alignNow,
                onSyncLyricsNow = lyricsVm::syncNow,
                swipeToSkip = state.swipeToSkip,
            )
        }

        AnimatedVisibility(
            visible = equalizerOpen,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            BackHandler { equalizerOpen = false }
            EqualizerScreen(
                onBack = { equalizerOpen = false },
                bitPerfectOutput = audioOutput.startsWith(PlaybackStatus.BIT_PERFECT_PREFIX),
                vm = equalizerVm,
            )
        }

        AnimatedVisibility(
            visible = exclusionsOpen,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            BackHandler { exclusionsOpen = false }
            ExcludedFoldersScreen(
                allTracks = state.allTracks,
                excluded = state.excludedFolders,
                onBack = { exclusionsOpen = false },
                onSetExcluded = vm::setFolderExcluded,
                onExcludeAll = vm::excludeFolders,
                onClear = vm::clearExclusions,
            )
        }
    }

    state.userMessage?.let { message ->
        LaunchedEffect(message) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            vm.consumeUserMessage()
        }
    }

    // ---------- Diálogos ----------
    if (alignServerOpen) {
        AlignServerDialog(
            url = lyricsState.alignServerUrl,
            token = lyricsState.alignToken,
            onTest = lyricsVm::testAlignServer,
            onSave = lyricsVm::setAlignServer,
            onDismiss = { alignServerOpen = false },
        )
    }
    state.dialog?.let { dialog ->
        CoverDialog(
            dialog = dialog,
            current = state.covers[dialog.album.key],
            writesToFolder = state.saveCoversInFolder && vm.canWriteToFolder(dialog.album),
            onChoose = vm::chooseCover,
            onChooseLocal = vm::chooseLocalCover,
            onDismiss = vm::closeDialog,
        )
    }
    state.tagEdit?.let { edit ->
        TagEditDialog(edit, onDismiss = vm::closeTagEditor, onSave = vm::saveTags)
    }
    addToPlaylistFor?.let { request ->
        AddToPlaylistDialog(
            playlists = playlists,
            onSelect = { p ->
                playlistsVm.addAll(p.id, request.uris)
                addToPlaylistFor = null
                request.onAdded()
                val name = p.displayName(context)
                val message = if (request.tracks.size == 1) context.getString(R.string.library_added_to_playlist, name)
                else context.resources.getQuantityString(R.plurals.library_added_many_to_playlist, request.tracks.size, request.tracks.size, name)
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            },
            onCreateNew = { createPlaylistWith = request; addToPlaylistFor = null },
            onDismiss = { addToPlaylistFor = null },
        )
    }
    createPlaylistWith?.let { request ->
        PlaylistNameDialog(
            title = stringResource(R.string.library_new_playlist),
            onConfirm = { name ->
                playlistsVm.create(name, request.uris)
                createPlaylistWith = null
                request.onAdded()
            },
            onDismiss = { createPlaylistWith = null },
        )
    }
}

/** Canciones que esperan elegir playlist; [onAdded] corre al añadirlas (p. ej. para quitar la selección). */
private class PlaylistRequest(val tracks: List<Track>, val onAdded: () -> Unit = {}) {
    val uris: List<String> get() = tracks.map { it.uri.toString() }.distinct()
}

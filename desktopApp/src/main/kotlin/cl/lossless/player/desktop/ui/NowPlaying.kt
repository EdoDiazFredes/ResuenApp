package cl.lossless.player.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.ViewSidebar
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.lossless.player.desktop.DesktopEqualizer
import cl.lossless.player.desktop.DesktopLyrics
import cl.lossless.player.desktop.LyricsResult
import cl.lossless.player.desktop.Player
import cl.lossless.player.desktop.PlayerState
import cl.lossless.player.desktop.RepeatMode
import cl.lossless.player.desktop.S
import cl.lossless.player.desktop.Track
import cl.lossless.player.desktop.t
import cl.lossless.player.lyrics.Lyrics
import cl.lossless.player.lyrics.LyricsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Panel derecho de la maqueta: pestañas Reproduciendo (carátula, formato y letra) y Cola. */
@Composable
fun RightPanel(
    track: Track,
    state: PlayerState,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    onPlayQueueItem: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var queueTab by remember { mutableStateOf(false) }
    Row(modifier) {
        Column(Modifier.fillMaxSize().background(Neon.Surface.copy(alpha = 0.75f)).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PanelTab(t(S.nowPlaying), selected = !queueTab) { queueTab = false }
                Spacer(Modifier.width(8.dp))
                PanelTab(t(S.queue, state.queue.size), selected = queueTab) { queueTab = true }
                Spacer(Modifier.weight(1f))
                SmallIconButton(Icons.Rounded.SwapHoriz, t(S.swapSide)) { Appearance.placePanel(!Appearance.panelLeft) }
                SmallIconButton(Icons.Rounded.Close, t(S.togglePanel), onClick = onClose)
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Neon.Border))
            if (queueTab) {
                QueueList(state, onPlayQueueItem)
            } else {
                NowPlayingContent(track, positionMs, onSeek)
            }
        }
    }
}

@Composable
private fun PanelTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Text(
        label,
        modifier = Modifier.clip(shape)
            .background(if (selected) Neon.Accent.copy(alpha = 0.20f) else Color.Transparent)
            .border(1.dp, if (selected) Neon.Accent.copy(alpha = 0.30f) else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        color = if (selected) Neon.Accent else Neon.Muted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun NowPlayingContent(track: Track, positionMs: Long, onSeek: (Long) -> Unit) {
    val lyricsVersion = DesktopLyrics.version
    var retry by remember(track.path) { mutableIntStateOf(0) }
    var lyrics by remember(track.path, lyricsVersion) { mutableStateOf<LyricsResult?>(null) }
    LaunchedEffect(track.path, lyricsVersion, retry) {
        lyrics = null
        lyrics = withContext(Dispatchers.IO) { DesktopLyrics.load(track) }
    }
    val actions = LocalTrackActions.current
    // La carátula crece con el ancho del panel (se ajusta arrastrando su borde), sin pasar
    // de la mitad del alto para dejar sitio a la letra.
    BoxWithConstraints(Modifier.fillMaxSize().padding(top = 16.dp)) {
        val cover = minOf(maxWidth, maxHeight * 0.5f).coerceAtLeast(120.dp)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box {
                CoverImage(
                    track, size = cover, px = if (cover > 300.dp) 1000 else 512, corner = 16.dp,
                    modifier = Modifier.glow(Neon.Accent.copy(alpha = 0.6f), 22.dp, RoundedCornerShape(16.dp)),
                )
                if (actions != null) {
                    Row(
                        Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(RoundedCornerShape(8.dp))
                            .background(Neon.Night.copy(alpha = 0.7f)),
                    ) {
                        SmallIconButton(Icons.Rounded.Edit, t(S.editTags), tint = Neon.Text, size = 15.dp) { actions.editTags(listOf(track)) }
                        SmallIconButton(Icons.Rounded.AddPhotoAlternate, t(S.searchCover), tint = Neon.Text, size = 15.dp) { actions.findCover(track) }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                track.title, color = Neon.TextStrong, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artist.ifBlank { t(S.unknownArtist) }, color = Neon.Muted, fontSize = 12.sp,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NeonBadge(track.codecLabel, color = Neon.Accent, emphasized = true)
                track.specsLabel?.let { NeonBadge(it, color = Neon.Text) }
            }
            Spacer(Modifier.height(16.dp))
            LyricsCard(lyrics, positionMs, onSeek, Modifier.weight(1f).fillMaxWidth(), onSearch = actions?.let { a -> { a.findLyrics(track) } }, onRetry = { retry++ })
        }
    }
}

@Composable
private fun LyricsCard(
    result: LyricsResult?,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier,
    onSearch: (() -> Unit)?,
    onRetry: () -> Unit,
) {
    val found = (result as? LyricsResult.Found)?.lyrics
    Column(modifier.glass(RoundedCornerShape(12.dp), Neon.Card.copy(alpha = 0.6f)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Rounded.Notes, null, tint = Neon.Accent, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            SectionLabel(if (found?.isSynced == true) t(S.lyricsSynced) else t(S.lyrics), Modifier.weight(1f))
            if (found != null) {
                Text(sourceLabel(found.source).uppercase(), color = Neon.Accent, fontFamily = Mono, fontSize = 10.sp, maxLines = 1)
            }
            if (onSearch != null) SmallIconButton(Icons.Rounded.Search, t(S.searchLyrics), size = 14.dp, onClick = onSearch)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Neon.Border))
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
            when {
                result == null -> CenteredNote(t(S.lyricsLoading))
                result == LyricsResult.None -> CenteredNote(t(S.lyricsNone))
                result is LyricsResult.Offline -> RetryNote(t(S.lyricsOffline) + (result.detail?.let { "\n($it)" } ?: ""), onRetry)
                result is LyricsResult.ServerError -> RetryNote(t(S.lyricsServerError, result.code), onRetry)
                found == null -> Unit
                found.instrumental && !found.isSynced && found.plain.isNullOrBlank() -> CenteredNote(t(S.lyricsInstrumental))
                found.isSynced -> SyncedLyrics(found, positionMs, onSeek)
                else -> Text(
                    found.plain.orEmpty(),
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    color = Neon.Text,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun sourceLabel(source: LyricsSource): String = t(
    when (source) {
        LyricsSource.SIDECAR -> S.sourceSidecar
        LyricsSource.LRCLIB -> S.sourceLrclib
        LyricsSource.LYRICS_OVH -> S.sourceLyricsOvh
        else -> S.sourceEmbedded
    },
)

@Composable
private fun SyncedLyrics(lyrics: Lyrics, positionMs: Long, onSeek: (Long) -> Unit) {
    val lines = lyrics.synced.orEmpty()
    val current = lyrics.lineIndexAt(positionMs)
    val listState = rememberLazyListState()
    LaunchedEffect(current) {
        if (current >= 0) listState.animateScrollToItem((current - 2).coerceAtLeast(0))
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        itemsIndexed(lines) { i, line ->
            val active = i == current
            val color by animateColorAsState(
                when {
                    active -> Neon.Accent
                    i < current -> Neon.Faint.copy(alpha = 0.6f)
                    else -> Neon.Faint
                },
            )
            // La línea activa crece con el tamaño de letra y no con un escalado: así el texto se
            // vuelve a acomodar al ancho del panel y nunca queda cortado en los bordes, con
            // cualquier tipo de letra o tamaño de texto.
            val size by animateFloatAsState(if (active) 14f else 13f)
            Text(
                line.text.ifBlank { "♪" },
                color = color,
                fontSize = size.sp,
                lineHeight = (size * 1.45f).sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                softWrap = true,
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onSeek(line.timeMs) }
                    .padding(horizontal = 4.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun RetryNote(text: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = Neon.Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        GhostButton(Icons.Rounded.Refresh, t(S.lyricsRetry), onRetry)
    }
}

@Composable
private fun CenteredNote(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Neon.Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun QueueList(state: PlayerState, onPlay: (Int) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.index) {
        if (state.index >= 0) listState.scrollToItem((state.index - 2).coerceAtLeast(0))
    }
    Column(Modifier.fillMaxSize().padding(top = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(t(S.upNext), Modifier.weight(1f))
            val actions = LocalTrackActions.current
            if (actions != null && state.queue.isNotEmpty()) {
                SmallIconButton(Icons.AutoMirrored.Rounded.PlaylistAdd, t(S.saveQueueAsPlaylist)) { actions.addToPlaylist(state.queue) }
            }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(state.queue) { i, track ->
                val current = i == state.index
                val interaction = remember { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(
                            when {
                                current -> Neon.Accent.copy(alpha = 0.10f)
                                hovered -> Neon.Hover.copy(alpha = 0.6f)
                                else -> Color.Transparent
                            },
                        )
                        .hoverable(interaction).clickable { onPlay(i) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                        if (current) PlayingBars(active = state.playing)
                        else Text("${i + 1}", color = Neon.Faint, fontFamily = Mono, fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    CoverImage(track, size = 32.dp, px = 64, corner = 4.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            track.title, color = if (current) Neon.Accent else Neon.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            track.artist.ifBlank { t(S.unknownArtist) }, color = Neon.Muted, fontSize = 10.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(formatTime(track.durationMs), color = Neon.Faint, fontFamily = Mono, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
internal fun SmallIconButton(icon: ImageVector, description: String, tint: Color = Neon.Muted, size: Dp = 18.dp, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).hoverable(interaction).clickable(onClick = onClick).padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (hovered) Neon.TextStrong else tint, modifier = Modifier.size(size))
    }
}

/**
 * Deslizador fino de la maqueta: pista de 4 dp y perilla cian con resplandor. [onValueChangeFinished]
 * se llama al soltar (para buscar en la pista una sola vez).
 */
@Composable
fun NeonSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChangeFinished: () -> Unit = {},
) {
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    var active by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    BoxWithConstraints(
        modifier.height(20.dp).hoverable(interaction)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    change((offset.x / size.width).coerceIn(0f, 1f))
                    finished()
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset -> active = true; change((offset.x / size.width).coerceIn(0f, 1f)) },
                    onDragEnd = { active = false; finished() },
                    onDragCancel = { active = false; finished() },
                ) { pointer, _ ->
                    pointer.consume()
                    change((pointer.position.x / size.width).coerceIn(0f, 1f))
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val fraction = value.coerceIn(0f, 1f)
        val thumb = if (active || hovered) 14.dp else 12.dp
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Neon.Border))
        Box(
            Modifier.fillMaxWidth(fraction).height(4.dp).clip(RoundedCornerShape(2.dp))
                .background(if (enabled) Neon.Accent else Neon.Faint),
        )
        if (enabled) {
            val x = (maxWidth - thumb) * fraction
            Box(
                Modifier.offset(x = x).size(thumb).glow(Neon.Accent, 8.dp, CircleShape)
                    .clip(CircleShape).background(Neon.Accent),
            )
        }
    }
}

/** Repetir: gris apagado, en el color de acento con "todo" y con el ícono del 1 con "una". */
@Composable
fun RepeatButton(player: Player, size: Dp) {
    val mode by player.repeat.collectAsState()
    SmallIconButton(
        if (mode == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
        t(
            when (mode) {
                RepeatMode.OFF -> S.repeatOff
                RepeatMode.ALL -> S.repeatAll
                RepeatMode.ONE -> S.repeatOne
            },
        ),
        tint = if (mode == RepeatMode.OFF) Neon.Muted else Neon.Accent,
        size = size,
        onClick = player::cycleRepeat,
    )
}

/** Aleatorio: gris apagado o en el color de acento cuando está activo. */
@Composable
fun ShuffleButton(player: Player, size: Dp) {
    val on by player.shuffle.collectAsState()
    SmallIconButton(
        Icons.Rounded.Shuffle,
        t(if (on) S.shuffleOn else S.shuffleOff),
        tint = if (on) Neon.Accent else Neon.Muted,
        size = size,
        onClick = player::toggleShuffle,
    )
}

/** Barra inferior: pista actual, controles con barra de progreso, salida, volumen y panel. */
@Composable
fun PlayerBar(
    player: Player,
    state: PlayerState,
    positionMs: Long,
    panelOpen: Boolean,
    onMiniPlayer: () -> Unit,
    onEqualizer: () -> Unit,
    onTogglePanel: () -> Unit,
) {
    val track = state.current
    val duration = state.durationMs.coerceAtLeast(1)
    var dragging by remember { mutableStateOf<Float?>(null) }
    val volume by player.volumeState.collectAsState()
    val eqOn = DesktopEqualizer.settings.collectAsState().value.isProcessing

    Box(Modifier.fillMaxWidth().height(1.dp).background(Neon.Border))
    Row(
        Modifier.fillMaxWidth().height(80.dp).background(Neon.Surface.copy(alpha = 0.75f)).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Pista actual
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(track, size = 48.dp, px = 128, corner = 8.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Text(
                    track?.title ?: t(S.nothingPlaying), color = Neon.TextStrong, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val error = state.error
                Text(
                    if (error != null) t(S.playbackError, error) else track?.artist.orEmpty(),
                    color = if (error != null) Color(0xFFFF6B81) else Neon.Muted, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (track != null) {
                    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            track.codecLabel, color = Neon.Accent, fontFamily = Mono, fontSize = 9.sp,
                            modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(Neon.Accent.copy(alpha = 0.10f)).padding(horizontal = 4.dp),
                        )
                        if (track.bitsPerSample > 0 && track.sampleRate > 0) {
                            Spacer(Modifier.width(6.dp))
                            Text("${track.bitsPerSample}/${cl.lossless.player.desktop.khz(track.sampleRate)}", color = Neon.Muted, fontFamily = Mono, fontSize = 9.sp)
                        }
                    }
                }
            }
        }

        // Controles y progreso
        Column(Modifier.weight(1.7f).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                // Aleatorio y repetir tienen el mismo ancho, así reproducir queda al centro.
                ShuffleButton(player, size = 18.dp)
                SmallIconButton(Icons.Rounded.SkipPrevious, t(S.previous), tint = Neon.Text, size = 20.dp) { if (track != null) player.previous() }
                val shape = CircleShape
                Box(
                    Modifier.size(40.dp).glow(Neon.Accent, if (track != null) 16.dp else 0.dp, shape).clip(shape)
                        .background(if (track != null) Neon.Accent else Neon.Hover)
                        .clickable(enabled = track != null, onClick = player::toggle),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (state.playing) t(S.pause) else t(S.play),
                        tint = Neon.OnAccent,
                        modifier = Modifier.size(24.dp),
                    )
                }
                SmallIconButton(Icons.Rounded.SkipNext, t(S.next), tint = Neon.Text, size = 20.dp) { if (track != null) player.next() }
                RepeatButton(player, size = 18.dp)
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val shown = dragging?.let { (it * duration).toLong() } ?: positionMs
                Text(formatTime(shown), color = Neon.Muted, fontFamily = Mono, fontSize = 10.sp, modifier = Modifier.width(52.dp), textAlign = TextAlign.End)
                NeonSlider(
                    value = dragging ?: (positionMs.toFloat() / duration).coerceIn(0f, 1f),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { player.seek((it * duration).toLong()) }
                        dragging = null
                    },
                    enabled = track != null,
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                )
                Text(formatTime(state.durationMs), color = Neon.Muted, fontFamily = Mono, fontSize = 10.sp, modifier = Modifier.width(52.dp))
            }
        }

        // Salida, volumen y panel lateral. Los botones (mini reproductor, panel) van siempre
        // completos: si falta ancho se achica la tarjeta de salida, que se oculta si no cabe.
        Row(Modifier.weight(1.3f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            BoxWithConstraints(Modifier.weight(1f, fill = false)) {
                if (maxWidth >= 110.dp) {
                    Column(
                        Modifier.glass(RoundedCornerShape(8.dp), Neon.Card).padding(horizontal = 10.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Memory, null, tint = Neon.Accent, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                t(S.outputDevice), color = Neon.Accent, fontFamily = Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            state.outputFormat ?: t(S.outputIdle), color = Neon.Muted, fontFamily = Mono, fontSize = 10.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.width(14.dp))
            SmallIconButton(
                if (volume <= 0.001f) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                t(S.mute),
                onClick = player::toggleMute,
            )
            NeonSlider(
                value = volume,
                onValueChange = player::setVolume,
                modifier = Modifier.width(80.dp),
            )
            Spacer(Modifier.width(10.dp))
            Box(Modifier.width(1.dp).height(24.dp).background(Neon.Border))
            Spacer(Modifier.width(6.dp))
            SmallIconButton(Icons.Rounded.GraphicEq, t(S.equalizer), tint = if (eqOn) Neon.Accent else Neon.Muted, onClick = onEqualizer)
            SmallIconButton(Icons.Rounded.PictureInPictureAlt, t(S.miniPlayer), onClick = onMiniPlayer)
            SmallIconButton(
                Icons.Rounded.ViewSidebar, t(S.togglePanel),
                tint = if (panelOpen) Neon.Accent else Neon.Muted,
                onClick = onTogglePanel,
            )
        }
    }
}

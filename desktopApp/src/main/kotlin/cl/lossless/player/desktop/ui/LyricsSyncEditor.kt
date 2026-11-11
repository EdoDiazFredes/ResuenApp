package cl.lossless.player.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.lossless.player.desktop.AppSettings
import cl.lossless.player.desktop.DesktopLyrics
import cl.lossless.player.desktop.LyricsResult
import cl.lossless.player.desktop.LyricsSync
import cl.lossless.player.desktop.S
import cl.lossless.player.desktop.Track
import cl.lossless.player.desktop.t
import cl.lossless.player.lyrics.LyricLine
import cl.lossless.player.lyrics.Lyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Letra de una pista ya cargada y, si venía sin tiempos, si se están estimando. */
class LoadedLyrics(
    val result: LyricsResult?,
    val estimating: Boolean,
    /** Estima los tiempos ahora (cuando la estimación automática está desactivada). */
    val estimateNow: () -> Unit,
) {
    val lyrics: Lyrics? get() = (result as? LyricsResult.Found)?.lyrics
}

/**
 * Carga la letra de [track] y, si viene solo como texto, estima sus tiempos analizando el
 * audio (como en Android): se muestra el texto al instante y la estimación cuando está lista.
 * La usan el panel de la letra y el mini reproductor.
 */
@Composable
fun rememberLyrics(track: Track?, retry: Int = 0): LoadedLyrics {
    val version = DesktopLyrics.version
    var result by remember(track?.path, version) { mutableStateOf<LyricsResult?>(null) }
    var estimating by remember(track?.path, version) { mutableStateOf(false) }
    var requested by remember(track?.path, version) { mutableIntStateOf(0) }
    LaunchedEffect(track?.path, version, retry, requested) {
        if (track == null) {
            result = null
            return@LaunchedEffect
        }
        if (requested == 0 || result == null) {
            result = null
            result = withContext(Dispatchers.IO) { DesktopLyrics.load(track) }
        }
        val found = (result as? LyricsResult.Found)?.lyrics ?: return@LaunchedEffect
        if (found.isSynced || found.plain.isNullOrBlank()) return@LaunchedEffect
        withContext(Dispatchers.IO) { LyricsSync.cachedEstimate(track, found) }?.let {
            result = LyricsResult.Found(it)
            return@LaunchedEffect
        }
        if (!AppSettings.lyricsAutoSync && requested == 0) return@LaunchedEffect
        estimating = true
        try {
            LyricsSync.estimate(track, found)?.let { result = LyricsResult.Found(it) }
        } finally {
            estimating = false
        }
    }
    return LoadedLyrics(result, estimating) { requested++ }
}

/** Se descuenta de cada marca: el tiempo típico entre oír la línea y pulsar. */
private const val REACTION_MS = 150L

/**
 * "Sincronizar a mano": mientras suena la canción se pulsa Marcar (o Intro) justo cuando empieza
 * cada línea. Al terminar se guarda como LRC exacto. Igual que "Sincronizar tocando" en Android.
 */
@Composable
fun TapSyncEditor(
    lines: List<String>,
    playing: Boolean,
    livePosition: () -> Long,
    onRestart: () -> Unit,
    onSave: suspend (List<LyricLine>) -> Unit,
    onClose: () -> Unit,
) {
    val marks = remember(lines) { mutableStateListOf<Long>() }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val isPlaying by rememberUpdatedState(playing)
    val next = marks.size
    val done = next >= lines.size

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(next) { if (lines.isNotEmpty()) listState.animateScrollToItem((next - 2).coerceAtLeast(0)) }

    fun save() {
        if (saving) return
        saving = true
        scope.launch {
            onSave(lines.mapIndexed { i, text -> LyricLine(marks[i], text) })
            saving = false
        }
    }

    fun mark() {
        if (saving || lines.isEmpty()) return
        if (done) { save(); return }
        if (!isPlaying) return
        val previous = marks.lastOrNull() ?: -1L
        marks += (livePosition() - REACTION_MS).coerceAtLeast(previous + 100).coerceAtLeast(0)
    }

    fun undo() {
        if (marks.isNotEmpty()) marks.removeAt(marks.lastIndex)
    }

    Column(
        Modifier.fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.Enter, Key.NumPadEnter -> { mark(); true }
                    Key.Backspace -> { undo(); true }
                    Key.Escape -> { onClose(); true }
                    else -> false // Espacio y demás atajos siguen funcionando (pausa, etc.).
                }
            },
    ) {
        Text(t(S.tapSyncHint), color = Neon.Muted, fontSize = 11.sp, lineHeight = 15.sp)
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(lines) { i, text ->
                val isNext = i == next
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isNext) Neon.Accent.copy(alpha = 0.15f) else Neon.Card.copy(alpha = 0f))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        marks.getOrNull(i)?.let(::formatMark) ?: "--:--.--",
                        color = if (i < next) Neon.Accent else Neon.Faint,
                        fontFamily = Mono, fontSize = 11.sp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text,
                        color = when {
                            isNext -> Neon.TextStrong
                            i < next -> Neon.Text
                            else -> Neon.Muted
                        },
                        fontSize = 13.sp,
                        fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        val enabled = !saving && lines.isNotEmpty() && (done || playing)
        val shape = RoundedCornerShape(8.dp)
        Row(
            Modifier.fillMaxWidth().height(44.dp)
                .then(if (enabled) Modifier.glow(Neon.Accent, 10.dp, shape) else Modifier)
                .clip(shape)
                .background(if (enabled) Neon.Accent else Neon.Card)
                .clickable(enabled = enabled) { mark(); runCatching { focus.requestFocus() } }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            val tint = if (enabled) Neon.OnAccent else Neon.Faint
            Icon(if (done) Icons.Rounded.Check else Icons.Rounded.TouchApp, null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    done -> t(S.tapSyncSave)
                    !playing -> t(S.tapSyncPlayToMark)
                    else -> t(S.tapSyncMark, next + 1, lines.size)
                },
                color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SmallTextButton(Icons.Rounded.Replay, t(S.tapSyncRestart)) {
                marks.clear()
                onRestart()
                runCatching { focus.requestFocus() }
            }
            SmallTextButton(Icons.AutoMirrored.Rounded.Undo, t(S.tapSyncUndo), enabled = marks.isNotEmpty()) {
                undo()
                runCatching { focus.requestFocus() }
            }
            Spacer(Modifier.weight(1f))
            SmallTextButton(Icons.Rounded.Close, t(S.tapSyncCancel), onClick = onClose)
        }
    }
}

@Composable
private fun SmallTextButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tint = if (enabled) Neon.Text else Neon.Faint
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = tint, fontSize = 12.sp, maxLines = 1)
    }
}

private fun formatMark(ms: Long): String =
    "%02d:%02d.%02d".format(ms / 60_000, (ms % 60_000) / 1_000, (ms % 1_000) / 10)

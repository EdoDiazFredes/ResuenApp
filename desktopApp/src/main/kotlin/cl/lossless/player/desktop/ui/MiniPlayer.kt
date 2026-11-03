package cl.lossless.player.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.ui.window.WindowScope
import cl.lossless.player.desktop.DesktopLyrics
import cl.lossless.player.desktop.LyricsResult
import cl.lossless.player.desktop.Player
import cl.lossless.player.desktop.S
import cl.lossless.player.desktop.t
import cl.lossless.player.lyrics.Lyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Tamaño de la ventana del mini reproductor; crece con el tamaño del texto elegido en Ajustes. */
fun miniPlayerSize(): DpSize {
    val scale = Appearance.textSize.scale
    return DpSize(440.dp * scale, 222.dp * scale)
}

/**
 * Mini reproductor: carátula, título y artista, dos líneas de la letra sincronizada, controles,
 * volumen y barra de progreso en una ventana pequeña sin bordes que se arrastra desde la
 * carátula o el título. [onExpand] vuelve a la vista completa; [onPin] alterna "siempre visible" (encima de las demás ventanas).
 */
@Composable
fun WindowScope.MiniPlayer(player: Player, pinned: Boolean, onPin: () -> Unit, onExpand: () -> Unit) {
    val state by player.state.collectAsState()
    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            position = player.positionMs()
            delay(250)
        }
    }
    val track = state.current
    val duration = state.durationMs.coerceAtLeast(1)
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shape = RoundedCornerShape(14.dp)
    val volume by player.volumeState.collectAsState()
    val lyricsVersion = DesktopLyrics.version
    var lyrics by remember { mutableStateOf<Lyrics?>(null) }
    LaunchedEffect(track?.path, lyricsVersion) {
        lyrics = null
        if (track != null) {
            // Ya suele estar en memoria (la cargó el panel de la vista completa).
            val result = withContext(Dispatchers.IO) { DesktopLyrics.load(track) }
            lyrics = (result as? LyricsResult.Found)?.lyrics?.takeIf { it.isSynced }
        }
    }

    Column(Modifier.fillMaxSize().glass(shape, Neon.Night)) {
        // Arriba: zona para arrastrar la ventana (carátula y textos) y los botones de la ventana.
        WindowDraggableArea(Modifier.weight(1f).fillMaxWidth()) {
            Row(Modifier.fillMaxSize().padding(start = 12.dp, top = 12.dp, end = 6.dp), verticalAlignment = Alignment.Top) {
                CoverImage(
                    track, size = 64.dp, px = 128, corner = 10.dp,
                    modifier = Modifier.glow(Neon.Accent.copy(alpha = 0.5f), 10.dp, RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(top = 2.dp)) {
                    Text(
                        track?.title ?: t(S.nothingPlaying), color = Neon.TextStrong, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    val error = state.error
                    Text(
                        when {
                            error != null -> t(S.playbackError, error)
                            track != null -> track.artist.ifBlank { t(S.unknownArtist) }
                            else -> ""
                        },
                        color = if (error != null) Color(0xFFFF6B81) else Neon.Muted, fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (track != null) {
                        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            NeonBadge(track.codecLabel, color = Neon.Accent, emphasized = true)
                            if (track.bitsPerSample > 0 && track.sampleRate > 0) {
                                NeonBadge("${track.bitsPerSample}/${cl.lossless.player.desktop.khz(track.sampleRate)}", color = Neon.Text)
                            }
                        }
                    }
                }
                SmallIconButton(
                    Icons.Rounded.PushPin, t(S.alwaysOnTop),
                    tint = if (pinned) Neon.Accent else Neon.Faint, size = 15.dp, onClick = onPin,
                )
                SmallIconButton(Icons.Rounded.OpenInFull, t(S.fullView), size = 15.dp, onClick = onExpand)
            }
        }

        // Dos líneas de letra (la actual y la siguiente); sin letra sincronizada, la franja
        // queda vacía en un tono apenas distinto del fondo.
        LyricsStrip(lyrics, position, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp))

        // Abajo: progreso y controles (fuera de la zona de arrastre para que las barras se puedan mover).
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val shown = dragging?.let { (it * duration).toLong() } ?: position
            Text(formatTime(shown), color = Neon.Muted, fontFamily = Mono, fontSize = 10.sp)
            NeonSlider(
                value = dragging ?: (position.toFloat() / duration).coerceIn(0f, 1f),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { player.seek((it * duration).toLong()) }
                    dragging = null
                },
                enabled = track != null,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text(formatTime(state.durationMs), color = Neon.Muted, fontFamily = Mono, fontSize = 10.sp)
        }
        // Controles centrados en el ancho de la ventana; el volumen queda a la derecha.
        Box(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, bottom = 8.dp)) {
            Row(
                Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Anterior y siguiente del mismo tamaño que el botón de reproducir (28 + 6 + 6 = 40 dp).
                SmallIconButton(Icons.Rounded.SkipPrevious, t(S.previous), tint = Neon.Text, size = 28.dp) { if (track != null) player.previous() }
                Box(
                    Modifier.size(40.dp).glow(Neon.Accent, if (track != null) 12.dp else 0.dp, CircleShape).clip(CircleShape)
                        .background(if (track != null) Neon.Accent else Neon.Hover)
                        .clickable(enabled = track != null, onClick = player::toggle),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (state.playing) t(S.pause) else t(S.play),
                        tint = Neon.OnAccent,
                        modifier = Modifier.size(26.dp),
                    )
                }
                SmallIconButton(Icons.Rounded.SkipNext, t(S.next), tint = Neon.Text, size = 28.dp) { if (track != null) player.next() }
            }
            Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                ShuffleButton(player, size = 18.dp)
                RepeatButton(player, size = 18.dp)
            }
            Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                SmallIconButton(
                    if (volume <= 0.001f) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                    t(S.mute), size = 16.dp, onClick = player::toggleMute,
                )
                NeonSlider(value = volume, onValueChange = player::setVolume, modifier = Modifier.width(84.dp))
            }
        }
    }
}

/**
 * Franja de dos líneas: la línea que suena (en el color de acento) y la siguiente. Siempre
 * ocupa el mismo alto, así la ventana no cambia de tamaño entre canciones con y sin letra.
 */
@Composable
private fun LyricsStrip(lyrics: Lyrics?, positionMs: Long, modifier: Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier.height(46.dp).clip(shape).background(Neon.Card.copy(alpha = 0.35f)).padding(horizontal = 10.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val lines = lyrics?.synced.orEmpty()
        if (lyrics == null || lines.isEmpty()) return@Column
        val current = lyrics.lineIndexAt(positionMs)
        val first = lines.getOrNull(current)?.text?.ifBlank { "♪" } ?: "♪"
        val second = lines.getOrNull(current + 1)?.text?.ifBlank { "♪" }.orEmpty()
        Text(
            first, color = Neon.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
        Text(
            second, color = Neon.Muted, fontSize = 11.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}

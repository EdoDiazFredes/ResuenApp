package cl.lossless.player.ui.player

import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import cl.lossless.player.R
import androidx.media3.common.C
import androidx.media3.common.Player
import cl.lossless.player.library.Track
import kotlinx.coroutines.delay

data class NowPlaying(
    val mediaId: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUri: Uri?,
)

data class QueueEntry(val mediaId: String, val title: String, val artist: String)

/** Instantánea inmutable del reproductor para la UI. */
data class PlayerUiState(
    val connected: Boolean = false,
    val current: NowPlaying? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    /** Momento (SystemClock.elapsedRealtime) en que se leyó [positionMs]; permite interpolar. */
    val positionUpdatedAt: Long = 0L,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<QueueEntry> = emptyList(),
    val currentIndex: Int = -1,
) {
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

private fun Player.snapshot(previous: PlayerUiState, rebuildQueue: Boolean, untitled: String): PlayerUiState {
    val item = currentMediaItem
    val md = mediaMetadata
    return PlayerUiState(
        connected = true,
        current = item?.let {
            NowPlaying(
                mediaId = it.mediaId,
                title = md.title?.toString() ?: untitled,
                artist = md.artist?.toString() ?: "",
                album = md.albumTitle?.toString() ?: "",
                artworkUri = md.artworkUri,
            )
        },
        isPlaying = isPlaying,
        positionMs = currentPosition,
        positionUpdatedAt = SystemClock.elapsedRealtime(),
        durationMs = duration.takeIf { it != C.TIME_UNSET } ?: 0L,
        shuffle = shuffleModeEnabled,
        repeatMode = repeatMode,
        queue = if (rebuildQueue) {
            (0 until mediaItemCount).map { i ->
                val m = getMediaItemAt(i)
                QueueEntry(
                    mediaId = m.mediaId,
                    title = m.mediaMetadata.title?.toString() ?: untitled,
                    artist = m.mediaMetadata.artist?.toString() ?: "",
                )
            }
        } else previous.queue,
        currentIndex = if (item == null) -1 else currentMediaItemIndex,
    )
}

/** Observa el [Player] y avanza la posición mientras suena. */
@Composable
fun rememberPlayerUiState(player: Player?): State<PlayerUiState> {
    val state = remember { mutableStateOf(PlayerUiState()) }
    val untitled = stringResource(R.string.player_untitled)
    DisposableEffect(player, untitled) {
        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                state.value = p.snapshot(
                    previous = state.value,
                    rebuildQueue = events.contains(Player.EVENT_TIMELINE_CHANGED),
                    untitled = untitled,
                )
            }
        }
        player?.addListener(listener)
        state.value = player?.snapshot(PlayerUiState(), rebuildQueue = true, untitled = untitled) ?: PlayerUiState()
        onDispose { player?.removeListener(listener) }
    }
    val playing = state.value.isPlaying
    LaunchedEffect(player, playing) {
        while (player != null && playing) {
            state.value = state.value.copy(
                positionMs = player.currentPosition,
                positionUpdatedAt = SystemClock.elapsedRealtime(),
            )
            delay(500)
        }
    }
    return state
}

/** Comandos de reproducción; no hacen nada si aún no hay conexión con el servicio. */
class PlayerActions(private val player: Player?) {

    fun playTracks(tracks: List<Track>, index: Int, artwork: (Track) -> Uri?) {
        val p = player ?: return
        if (tracks.isEmpty()) return
        p.setMediaItems(tracks.map { it.toMediaItem(artwork(it)) }, index.coerceIn(0, tracks.lastIndex), 0L)
        p.prepare()
        p.play()
    }

    fun shuffleTracks(tracks: List<Track>, artwork: (Track) -> Uri?) {
        val p = player ?: return
        if (tracks.isEmpty()) return
        p.shuffleModeEnabled = true
        playTracks(tracks, tracks.indices.random(), artwork)
    }

    fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            return
        }
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        if (p.playbackState == Player.STATE_ENDED) p.seekToDefaultPosition(0)
        p.play()
    }

    fun next() { player?.seekToNext() }
    fun previous() { player?.seekToPrevious() }
    /** Siempre a la canción anterior (sin el "volver al inicio" de [previous]); deslizar usa esto. */
    fun previousTrack() {
        val p = player ?: return
        if (p.hasPreviousMediaItem()) p.seekToPreviousMediaItem() else p.seekTo(0)
    }
    fun seekTo(positionMs: Long) { player?.seekTo(positionMs) }
    fun playFrom(positionMs: Long) { player?.run { seekTo(positionMs); play() } }
    fun skipToQueueItem(index: Int) { player?.run { seekTo(index, 0L); play() } }
    fun removeQueueItem(index: Int) { player?.removeMediaItem(index) }

    fun toggleShuffle() {
        player?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    fun cycleRepeat() {
        val p = player ?: return
        p.repeatMode = when (p.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun playNext(track: Track, artwork: Uri?) {
        val p = player ?: return
        if (p.mediaItemCount == 0) return playTracks(listOf(track), 0) { artwork }
        p.addMediaItem(p.currentMediaItemIndex + 1, track.toMediaItem(artwork))
    }

    fun enqueue(track: Track, artwork: Uri?) {
        val p = player ?: return
        if (p.mediaItemCount == 0) return playTracks(listOf(track), 0) { artwork }
        p.addMediaItem(track.toMediaItem(artwork))
    }

    /**
     * Suma [track] al final si la cola sigue siendo la que empezó con [firstMediaId] (las pistas
     * de Drive llegan a medida que se descargan). Si la cola ya se había terminado, sigue con ella.
     * false si el usuario puso otra música.
     */
    fun appendIfQueueStartsWith(track: Track, artwork: Uri?, firstMediaId: String): Boolean {
        val p = player ?: return false
        if (p.mediaItemCount == 0 || p.getMediaItemAt(0).mediaId != firstMediaId) return false
        val ended = p.playbackState == Player.STATE_ENDED
        p.addMediaItem(track.toMediaItem(artwork))
        if (ended) {
            p.seekTo(p.mediaItemCount - 1, 0L)
            p.play()
        }
        return true
    }
}

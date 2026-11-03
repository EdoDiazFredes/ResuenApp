package cl.lossless.player.playback

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.DefaultMediaNotificationProvider
import cl.lossless.player.MainActivity
import cl.lossless.player.R
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import cl.lossless.player.covers.CoverProvider
import cl.lossless.player.playback.auto.AutoLibrary
import cl.lossless.player.playback.auto.AutoMediaIds
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Executors

@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private lateinit var bitPerfect: BitPerfectController
    private lateinit var playbackPrefs: PlaybackPrefs
    private lateinit var lastPlayback: LastPlaybackStore
    private val main = Handler(Looper.getMainLooper())

    /** Mientras suena, guarda la posición cada pocos segundos (por si el sistema cierra la app). */
    private val savePositionTick = object : Runnable {
        override fun run() {
            savePosition()
            main.postDelayed(this, POSITION_SAVE_INTERVAL_MS)
        }
    }
    private val io: ListeningExecutorService =
        MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    /** Lectura de la biblioteca para Android Auto, aparte para no demorar la reproducción. */
    private val libraryExecutor: ListeningExecutorService =
        MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
    private lateinit var autoLibrary: AutoLibrary

    override fun onCreate() {
        super.onCreate()
        bitPerfect = BitPerfectController(this)
        playbackPrefs = PlaybackPrefs(this)
        lastPlayback = LastPlaybackStore(this)
        autoLibrary = AutoLibrary(this)

        val player = ExoPlayer.Builder(this, LosslessRenderersFactory(this))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pausa al desconectar audífonos/DAC
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                // Cambio automático de pista: reconfigura la salida si cambia la frecuencia.
                if (item != null && reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                    io.execute { prepareOutputFor(item) }
                }
                savePosition()
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) saveQueue()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                main.removeCallbacks(savePositionTick)
                if (isPlaying) main.postDelayed(savePositionTick, POSITION_SAVE_INTERVAL_MS)
                else savePosition()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) savePosition()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = savePosition()
            override fun onRepeatModeChanged(repeatMode: Int) = savePosition()
        })

        // Notificación multimedia con el isotipo de la app en la barra de estado.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelName(R.string.playback_channel_name)
                .build()
                .apply { setSmallIcon(R.drawable.ic_stat_resuen) }
        )

        session = MediaLibrarySession.Builder(this, player, SessionCallback())
            // Tocar la notificación (o el reproductor de la pantalla de bloqueo) abre la app
            // directamente en "Reproduciendo".
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_OPEN_NOW_PLAYING, true)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .setBitmapLoader(
                LogoFallbackBitmapLoader(CacheBitmapLoader(DataSourceBitmapLoader(this))) { logoBitmap }
            )
            .build()

        restoreLastPlayback(player)
    }

    /**
     * Deja cargada (en pausa) la cola de la última vez, en la misma pista y posición, para
     * no tener que buscar de nuevo el disco al volver a abrir la app.
     */
    private fun restoreLastPlayback(player: Player) {
        io.execute {
            val saved = lastPlayback.load() ?: return@execute
            val items = saved.entries.map { resolveItem(it.toMediaItem()) }
            // Configurar la salida ANTES de que ExoPlayer cree el AudioTrack.
            prepareOutputFor(items[saved.index])
            main.post {
                // Si mientras tanto ya se eligió otra cosa, no se toca.
                if (session == null || player.mediaItemCount > 0) return@post
                player.shuffleModeEnabled = saved.shuffle
                player.repeatMode = saved.repeatMode
                player.setMediaItems(items, saved.index, saved.positionMs)
                player.prepare() // en pausa: playWhenReady sigue en false
            }
        }
    }

    private fun saveQueue() {
        val player = session?.player ?: return
        val entries = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toSavedEntry() }
        io.execute { runCatching { lastPlayback.saveQueue(entries) } }
        savePosition()
    }

    private fun savePosition(now: Boolean = false) {
        val player = session?.player ?: return
        if (player.mediaItemCount == 0) return
        lastPlayback.savePosition(
            index = player.currentMediaItemIndex,
            positionMs = player.currentPosition,
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            now = now,
        )
    }

    /** Logo de la app como carátula de respaldo (se genera una sola vez). */
    private val logoBitmap: Bitmap by lazy {
        getDrawable(R.mipmap.ic_launcher)!!.toBitmap(512, 512)
    }

    /** Lee el archivo y configura la salida (bit-perfect si hay DAC USB). */
    private fun prepareOutputFor(item: MediaItem) {
        val uri = item.localConfiguration?.uri ?: Uri.parse(item.mediaId)
        val spec = AudioProbe.probe(this, uri) ?: return
        PlaybackStatus.source.value = spec.describe(this)
        bitPerfect.apply(spec)
    }

    private inner class SessionCallback : MediaLibrarySession.Callback {
        /**
         * Botones de audífonos y controles Bluetooth. Los gestos táctiles de los audífonos
         * (Sony, Sennheiser, Jabra…) los traduce el propio audífono a estas teclas estándar:
         * play/pausa, siguiente y anterior ya los atiende Media3 (también el doble toque de
         * play/pausa, que avanza). Aquí solo se cambia el avance y retroceso rápido.
         */
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            val event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) ?: return false
            val skipNext = when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> true
                KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> false
                else -> return false
            }
            if (!playbackPrefs.mediaKeysSkip) return false
            // Una sola vez por pulsación, aunque se mantenga presionado.
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val player = session.player
                if (skipNext) player.seekToNextMediaItem()
                else if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
                else player.seekTo(0)
            }
            return true
        }


        // El MediaController no envía la URI al servicio: se reconstruye desde mediaId.
        private fun resolve(items: List<MediaItem>) = items.map(::resolveItem)

        /** Elementos que vienen del árbol de Android Auto (o de una orden de voz). */
        private fun needsLibrary(item: MediaItem) =
            item.mediaId == AutoMediaIds.SHUFFLE_ALL || AutoMediaIds.parseTrack(item.mediaId) != null ||
                (item.mediaId.isEmpty() && item.requestMetadata.searchQuery != null)

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            if (mediaItems.none(::needsLibrary)) io.submit<MutableList<MediaItem>> { resolve(mediaItems).toMutableList() }
            else libraryExecutor.submit<MutableList<MediaItem>> {
                mediaItems.flatMap { item ->
                    when {
                        AutoMediaIds.parseTrack(item.mediaId) != null -> listOfNotNull(autoLibrary.resolveSingle(item.mediaId))
                        needsLibrary(item) -> fromAuto(item).first
                        else -> listOf(resolveItem(item))
                    }
                }.toMutableList()
            }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val index = if (startIndex == C.INDEX_UNSET) 0 else startIndex
            val resolved: ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
                if (mediaItems.size == 1 && needsLibrary(mediaItems[0])) {
                    // Una pista elegida en el auto: suena su álbum/playlist/carpeta desde ella.
                    libraryExecutor.submit<MediaSession.MediaItemsWithStartPosition> {
                        val (items, start) = fromAuto(mediaItems[0])
                        MediaSession.MediaItemsWithStartPosition(items, start, startPositionMs)
                    }
                } else if (mediaItems.any(::needsLibrary)) {
                    libraryExecutor.submit<MediaSession.MediaItemsWithStartPosition> {
                        val items = mediaItems.map { autoLibrary.resolveSingle(it.mediaId) ?: resolveItem(it) }
                        MediaSession.MediaItemsWithStartPosition(items, index, startPositionMs)
                    }
                } else {
                    Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(resolve(mediaItems), index, startPositionMs)
                    )
                }
            // Configurar la salida ANTES de que ExoPlayer cree el AudioTrack.
            return Futures.transform(resolved, { result ->
                result.mediaItems.getOrNull(result.startIndex)?.let { prepareOutputFor(it) }
                result
            }, io)
        }

        /** Cola e índice inicial para algo elegido en el auto o pedido por voz. */
        private fun fromAuto(item: MediaItem): Pair<List<MediaItem>, Int> {
            val query = item.requestMetadata.searchQuery
            if (item.mediaId.isEmpty() && query != null) {
                // "Reproduce X en ResuenApp"; sin nada concreto, toda la biblioteca en aleatorio.
                if (query.isNotBlank()) {
                    val found = autoLibrary.search(query)
                    if (found.isNotEmpty()) return found.map(autoLibrary::playbackItem) to 0
                }
                return fromAuto(MediaItem.Builder().setMediaId(AutoMediaIds.SHUFFLE_ALL).build())
            }
            val queue = autoLibrary.resolveQueue(item.mediaId)
                ?: throw IllegalArgumentException("Elemento desconocido: ${item.mediaId}")
            if (item.mediaId == AutoMediaIds.SHUFFLE_ALL) main.post { session?.player?.shuffleModeEnabled = true }
            return queue
        }

        /** Android Auto (o el sistema) pide retomar lo último que sonó sin abrir la app. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            io.submit<MediaSession.MediaItemsWithStartPosition> {
                val saved = lastPlayback.load() ?: throw IllegalStateException("No hay reproducción guardada")
                val items = saved.entries.map { resolveItem(it.toMediaItem()) }
                prepareOutputFor(items[saved.index])
                main.post {
                    mediaSession.player.shuffleModeEnabled = saved.shuffle
                    mediaSession.player.repeatMode = saved.repeatMode
                }
                MediaSession.MediaItemsWithStartPosition(items, saved.index, saved.positionMs)
            }

        // ---------- Navegación de Android Auto ----------

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            // La raíz de "recientes" (tarjeta de reanudar del sistema) usa onPlaybackResumption.
            if (params?.isRecent == true) {
                return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED))
            }
            return libraryExecutor.submit<LibraryResult<MediaItem>> {
                // Cada vez que el auto se conecta se relee la biblioteca (si no es muy reciente).
                autoLibrary.refresh(maxAgeMs = LIBRARY_MAX_AGE_MS)
                LibraryResult.ofItem(
                    autoLibrary.root(),
                    LibraryParams.Builder().setExtras(autoLibrary.rootExtras()).build(),
                )
            }
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            libraryExecutor.submit<LibraryResult<ImmutableList<MediaItem>>> {
                val children = autoLibrary.children(parentId)
                    ?: return@submit LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                LibraryResult.ofItemList(AutoMediaIds.page(children, page, pageSize), params)
            }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            libraryExecutor.submit<LibraryResult<MediaItem>> {
                autoLibrary.item(mediaId)?.let { LibraryResult.ofItem(it, null) }
                    ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> =
            libraryExecutor.submit<LibraryResult<Void>> {
                val count = autoLibrary.search(query).size
                main.post { session.notifySearchResultChanged(browser, query, count, params) }
                LibraryResult.ofVoid()
            }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            libraryExecutor.submit<LibraryResult<ImmutableList<MediaItem>>> {
                LibraryResult.ofItemList(AutoMediaIds.page(autoLibrary.searchResults(query), page, pageSize), params)
            }
    }

    /**
     * Reconstruye la URI desde mediaId y entrega la carátula como content://, que Android Auto
     * (otro proceso) sí puede abrir; file:// solo lo lee la propia app.
     */
    private fun resolveItem(item: MediaItem): MediaItem {
        val artwork = item.mediaMetadata.artworkUri
        val shared = CoverProvider.publicUri(this, artwork)
        return item.buildUpon()
            .setUri(item.mediaId)
            .apply {
                if (shared != artwork) setMediaMetadata(item.mediaMetadata.buildUpon().setArtworkUri(shared).build())
            }
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        savePosition(now = true)
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        main.removeCallbacks(savePositionTick)
        savePosition(now = true)
        session?.run {
            player.release()
            release()
        }
        session = null
        bitPerfect.release()
        // Deja terminar la escritura de la cola que esté pendiente.
        io.shutdown()
        libraryExecutor.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val POSITION_SAVE_INTERVAL_MS = 5_000L
        /** Android Auto relee la biblioteca al conectarse si la copia tiene más de esto. */
        const val LIBRARY_MAX_AGE_MS = 30_000L
    }
}

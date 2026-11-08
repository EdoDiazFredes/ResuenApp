package cl.lossless.player.ui

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cl.lossless.player.R
import cl.lossless.player.drive.AndroidDrive
import cl.lossless.player.drive.AndroidDriveTokens
import cl.lossless.player.drive.DriveAccount
import cl.lossless.player.drive.DriveAuthException
import cl.lossless.player.drive.DriveClient
import cl.lossless.player.drive.DriveItem
import cl.lossless.player.drive.DrivePrefs
import cl.lossless.player.drive.GoogleOAuth
import cl.lossless.player.library.Track
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

data class DriveFolder(val id: String, val name: String)

data class DriveDownload(val name: String, val index: Int, val total: Int, val progress: Float)

data class DriveUiState(
    val account: DriveAccount? = null,
    val connecting: Boolean = false,
    /** Pantalla de Google que hay que mostrar para dar el permiso (se consume al abrirla). */
    val authIntent: PendingIntent? = null,
    val stack: List<DriveFolder> = emptyList(),
    val items: List<DriveItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val download: DriveDownload? = null,
    val cacheBytes: Long = 0L,
) {
    val folder: DriveFolder? get() = stack.lastOrNull()
    val files: List<DriveItem> get() = items.filterNot { it.isFolder }
}

/** Recibe cada pista de Drive a medida que se descarga, para ponerla a sonar o sumarla a la cola. */
interface DrivePlayback {
    fun start(track: Track)

    /** false si el usuario ya puso otra música: entonces se dejan de descargar las siguientes. */
    fun append(track: Track, firstUri: String): Boolean
}

/**
 * Google Drive en Android: inicio de sesión con Google Play Services, explorador de carpetas y
 * reproducción desde la caché (cada canción se descarga entera antes de sonar, sin pérdida).
 */
class DriveViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = DrivePrefs(app)
    private val tokens = AndroidDriveTokens(app)
    private val client = DriveClient(tokens, "ResuenApp-Android")
    val cache = AndroidDrive.cache(app)

    private val myDrive get() = DriveFolder(DriveClient.ROOT, getApplication<Application>().getString(R.string.drive_my_drive))
    private val shared get() = DriveFolder(DriveClient.SHARED, getApplication<Application>().getString(R.string.drive_shared))

    private val _ui = MutableStateFlow(DriveUiState(account = prefs.account))
    val ui = _ui.asStateFlow()

    /** Pistas de Drive ya descargadas, por URI (para mostrar sus datos en "Reproduciendo"). */
    private val _tracks = MutableStateFlow<Map<String, Track>>(emptyMap())
    val tracks = _tracks.asStateFlow()

    private var listJob: Job? = null
    private var playJob: Job? = null

    init {
        refreshCacheSize()
    }

    fun uriFor(item: DriveItem): String = android.net.Uri.fromFile(cache.fileFor(item)).toString()

    /** Al abrir la pestaña: carga Mi unidad la primera vez. */
    fun onShown() {
        if (_ui.value.account != null && _ui.value.stack.isEmpty()) openRoot(DriveClient.ROOT)
    }

    // ---------- Sesión ----------

    fun connect() {
        _ui.update { it.copy(connecting = true, error = null) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    Tasks.await(Identity.getAuthorizationClient(getApplication<Application>()).authorize(AndroidDriveTokens.request()))
                }
                if (result.hasResolution()) {
                    _ui.update { it.copy(authIntent = result.pendingIntent) }
                } else {
                    onAuthorized()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(connecting = false, error = describe(e)) }
            }
        }
    }

    fun consumeAuthIntent() = _ui.update { it.copy(authIntent = null) }

    /** Vuelta de la pantalla de Google. */
    fun onAuthResult(data: Intent?) {
        val result = runCatching {
            Identity.getAuthorizationClient(getApplication<Application>()).getAuthorizationResultFromIntent(data)
        }
        if (result.isFailure || result.getOrNull()?.accessToken == null) {
            _ui.update { it.copy(connecting = false) }
            return
        }
        viewModelScope.launch { onAuthorized() }
    }

    private suspend fun onAuthorized() {
        try {
            val account = withContext(Dispatchers.IO) { client.account() }
            prefs.account = account
            _ui.update { it.copy(account = account, connecting = false, error = null) }
            openRoot(DriveClient.ROOT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _ui.update { it.copy(connecting = false, error = describe(e)) }
        }
    }

    fun signOut() {
        cancelDownloads()
        viewModelScope.launch(Dispatchers.IO) {
            // Revocar el token quita el permiso de la app en la cuenta de Google.
            runCatching { tokens.accessToken() }.getOrNull()?.let {
                GoogleOAuth.revoke(it)
                tokens.invalidate(it)
            }
        }
        prefs.account = null
        _ui.update { DriveUiState(cacheBytes = it.cacheBytes) }
    }

    // ---------- Navegación ----------

    fun openRoot(id: String) = load(listOf(if (id == DriveClient.SHARED) shared else myDrive))

    fun open(item: DriveItem) = load(_ui.value.stack + DriveFolder(item.id, item.name))

    /** false si ya está en la raíz. */
    fun back(): Boolean {
        val stack = _ui.value.stack
        if (stack.size <= 1) return false
        load(stack.dropLast(1))
        return true
    }

    fun reload() = load(_ui.value.stack.ifEmpty { listOf(myDrive) })

    private fun load(stack: List<DriveFolder>) {
        listJob?.cancel()
        _ui.update { it.copy(stack = stack, items = emptyList(), loading = true, error = null) }
        listJob = viewModelScope.launch {
            try {
                val items = withContext(Dispatchers.IO) { client.list(stack.last().id, AndroidDrive.audioExtensions) }
                _ui.update { it.copy(items = items, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            }
        }
    }

    // ---------- Reproducción ----------

    /**
     * Descarga [start] y lo pone a sonar; luego baja las siguientes canciones de la carpeta y
     * las suma a la cola. Se detiene si el usuario pone otra música.
     */
    fun play(start: DriveItem, playback: DrivePlayback) {
        val state = _ui.value
        val files = state.files
        val folderName = state.folder?.name.orEmpty()
        val pending = files.drop(files.indexOf(start).coerceAtLeast(0))
        if (pending.isEmpty()) return
        cancelDownloads()
        playJob = viewModelScope.launch {
            var firstUri: String? = null
            try {
                for ((i, item) in pending.withIndex()) {
                    _ui.update { it.copy(download = DriveDownload(item.name, i + 1, pending.size, 0f)) }
                    val track = withContext(Dispatchers.IO) {
                        val file = runInterruptible {
                            cache.fetch(item, client) { p ->
                                _ui.update { it.copy(download = DriveDownload(item.name, i + 1, pending.size, p)) }
                            }
                        }
                        AndroidDrive.toTrack(file, item, folderName)
                    }
                    _tracks.update { it + (track.uri.toString() to track) }
                    val first = firstUri
                    if (first == null) {
                        firstUri = track.uri.toString()
                        playback.start(track)
                    } else if (!playback.append(track, first)) {
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            } finally {
                _ui.update { it.copy(download = null) }
                refreshCacheSize()
            }
        }
    }

    fun cancelDownloads() {
        playJob?.cancel()
        playJob = null
        _ui.update { it.copy(download = null) }
    }

    fun clearCache() {
        cancelDownloads()
        viewModelScope.launch(Dispatchers.IO) {
            cache.clear()
            refreshCacheSize()
        }
    }

    private fun refreshCacheSize() {
        viewModelScope.launch(Dispatchers.IO) {
            val size = cache.sizeBytes()
            _ui.update { it.copy(cacheBytes = size) }
        }
    }

    private fun onError(e: Exception) {
        if (e is DriveAuthException) {
            // Google pide volver a dar el permiso: se vuelve a la pantalla de conexión.
            prefs.account = null
            _ui.update { DriveUiState(cacheBytes = it.cacheBytes, error = describe(e)) }
        } else {
            _ui.update { it.copy(loading = false, error = describe(e)) }
        }
    }

    private fun describe(e: Throwable): String {
        val app = getApplication<Application>()
        return if (e is DriveAuthException) app.getString(R.string.drive_session_expired)
        else app.getString(R.string.drive_error, e.message ?: e.javaClass.simpleName)
    }
}

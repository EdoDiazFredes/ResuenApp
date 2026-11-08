package cl.lossless.player.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cl.lossless.player.update.AvailableUpdate
import cl.lossless.player.update.UpdateChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.system.exitProcess

/** Estado de la búsqueda e instalación de versiones nuevas. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val update: AvailableUpdate) : UpdateState
    data class Downloading(val update: AvailableUpdate, val progress: Float) : UpdateState
    /** Falló la búsqueda ([update] null) o la descarga de [update], que se puede reanudar. */
    data class Failed(val message: String, val update: AvailableUpdate? = null) : UpdateState
}

/**
 * Actualiza la app desde el repositorio público de instaladores (canal `exp`, igual que la
 * build experimental de Android). En Windows descarga el Setup.exe, lo abre y cierra la app para
 * que el instalador reemplace la versión anterior; en macOS abre la .dmg de su arquitectura.
 * Si la descarga se corta, el archivo parcial queda guardado y "Reanudar" sigue desde ahí.
 */
object DesktopUpdater {

    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    private val checker by lazy { UpdateChecker(userAgent = "ResuenApp/$APP_VERSION (${System.getProperty("os.name")})") }

    private fun pickAsset(name: String): Boolean = when {
        IsMac && System.getProperty("os.arch") in listOf("aarch64", "arm64") -> name.endsWith("macOS-apple-silicon.dmg")
        IsMac -> name.endsWith("macOS-intel.dmg")
        else -> name.endsWith("-Setup.exe")
    }

    /** [quiet]: al abrir la app, un error de red no se muestra (queda en Idle). */
    suspend fun check(quiet: Boolean = false) {
        if (state is UpdateState.Checking || state is UpdateState.Downloading) return
        state = UpdateState.Checking
        state = withContext(Dispatchers.IO) {
            runCatching { checker.check(APP_VERSION, "exp", ::pickAsset) }.fold(
                onSuccess = { it?.let(UpdateState::Available) ?: UpdateState.UpToDate },
                onFailure = { if (quiet) UpdateState.Idle else UpdateState.Failed(it.message ?: it.javaClass.simpleName) },
            )
        }
    }

    @Volatile
    private var job: Job? = null

    /** Detiene la descarga; lo ya bajado queda guardado para reanudar. */
    fun cancel() {
        job?.cancel()
    }

    /** Descarga (o reanuda) [update], abre el instalador y cierra la app (detiene el audio antes). */
    suspend fun install(update: AvailableUpdate, player: Player) {
        if (state is UpdateState.Downloading) return
        state = UpdateState.Downloading(update, 0f)
        val dir = File(AppDirs.data, "updates")
        val file = File(dir, update.assetName)
        val result = supervisorScope {
            val download = async(Dispatchers.IO) {
                // Borra descargas de otras versiones, pero no el .part de esta.
                dir.listFiles()?.forEach { if (!it.name.startsWith(file.name)) it.delete() }
                var lastPercent = -1
                checker.download(update, file, isActive = { isActive }) { p ->
                    val percent = (p * 100).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        state = UpdateState.Downloading(update, p)
                    }
                }
                val command = if (IsMac) listOf("open", file.absolutePath) else listOf(file.absolutePath)
                ProcessBuilder(command).directory(dir).start()
            }
            job = download
            runCatching { download.await() }
        }
        job = null
        result.onFailure {
            state = if (it is CancellationException) UpdateState.Available(update)
            else UpdateState.Failed(it.message ?: it.javaClass.simpleName, update)
            return
        }
        player.shutdown()
        exitProcess(0)
    }
}

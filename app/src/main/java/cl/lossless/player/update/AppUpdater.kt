package cl.lossless.player.update

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

/** Estado de la búsqueda e instalación de versiones nuevas. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val update: AvailableUpdate) : UpdateState
    data class Downloading(val update: AvailableUpdate, val progress: Float) : UpdateState
    /** Hace falta permitir "Instalar apps desconocidas" para esta app y volver a intentar. */
    data class NeedsPermission(val update: AvailableUpdate) : UpdateState
    /** Falló la búsqueda ([update] null) o la descarga de [update], que se puede reanudar. */
    data class Failed(val message: String, val update: AvailableUpdate? = null) : UpdateState
}

/**
 * Actualiza la app desde el repositorio público de instaladores: la build experimental (.exp)
 * sigue las versiones `exp-…` y la estable las `v…`. Descarga el APK en un servicio en primer
 * plano ([UpdateDownloadService]), así sigue aunque la app quede en segundo plano, y abre el
 * instalador de Android, que lo instala encima porque el CI firma con la misma llave.
 * Si la descarga se corta, el archivo parcial queda guardado y "Reanudar" sigue desde ahí.
 */
object AppUpdater {

    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    /** El aviso al abrir la app ya se mostró (o se descartó) en esta sesión. */
    var promptDismissed by mutableStateOf(false)

    /** Descarga en curso (la lanza el servicio); null si no hay ninguna. */
    @Volatile
    internal var job: Job? = null

    private fun isExperimental(context: Context) = context.packageName.endsWith(".exp")

    private fun currentVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    private fun checker(context: Context) = UpdateChecker(userAgent = "ResuenApp/${currentVersion(context)} (Android)")

    private fun updatesDir(context: Context) = File(context.cacheDir, "updates")

    internal fun apkFile(context: Context, update: AvailableUpdate) = File(updatesDir(context), update.assetName)

    suspend fun check(context: Context) {
        if (state is UpdateState.Checking || state is UpdateState.Downloading) return
        state = UpdateState.Checking
        val exp = isExperimental(context)
        state = withContext(Dispatchers.IO) {
            runCatching {
                checker(context).check(currentVersion(context), if (exp) "exp" else "v") { name ->
                    name.endsWith(".apk") && name.contains("-exp-") == exp
                }
            }.fold(
                onSuccess = { it?.let(UpdateState::Available) ?: UpdateState.UpToDate },
                onFailure = { UpdateState.Failed(it.message ?: it.javaClass.simpleName) },
            )
        }
    }

    /**
     * Descarga (o reanuda) [update] en segundo plano y luego abre el instalador. Vuelve enseguida:
     * el avance se ve en [state] y en la notificación del servicio.
     */
    fun install(context: Context, update: AvailableUpdate) {
        if (state is UpdateState.Downloading) return
        val app = context.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            state = UpdateState.NeedsPermission(update)
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        state = UpdateState.Downloading(update, 0f)
        UpdateDownloadService.start(app, update)
    }

    /** Detiene la descarga; lo ya bajado queda guardado para reanudar. */
    fun cancel() {
        job?.cancel()
    }

    /**
     * La descarga en sí, llamada por [UpdateDownloadService]. Devuelve el APK listo, o null si
     * falló o se canceló (el estado queda en Failed o Available, siempre con opción de reanudar).
     */
    internal suspend fun download(context: Context, update: AvailableUpdate, onProgress: (Float) -> Unit): File? {
        state = UpdateState.Downloading(update, 0f)
        val apk = apkFile(context, update)
        return try {
            val active = currentCoroutineContext()
            withContext(Dispatchers.IO) {
                // Borra descargas de otras versiones, pero no el .part de esta.
                updatesDir(context).listFiles()?.forEach { if (!it.name.startsWith(apk.name)) it.delete() }
                var lastPercent = -1
                checker(context).download(update, apk, isActive = { active.isActive }) { p ->
                    val percent = (p * 100).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        state = UpdateState.Downloading(update, p)
                        onProgress(p)
                    }
                }
            }
            state = UpdateState.Available(update)
            apk
        } catch (e: CancellationException) {
            state = UpdateState.Available(update)
            throw e
        } catch (e: Exception) {
            state = UpdateState.Failed(e.message ?: e.javaClass.simpleName, update)
            null
        } finally {
            // Pase lo que pase, nunca queda pegado en "Descargando".
            if (state is UpdateState.Downloading) state = UpdateState.Available(update)
        }
    }

    /** Intent que abre el instalador de Android con el APK descargado. */
    internal fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** La app está a la vista (Android no deja abrir pantallas desde segundo plano). */
    internal fun isInForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
}

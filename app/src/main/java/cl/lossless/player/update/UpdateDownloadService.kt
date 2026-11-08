package cl.lossless.player.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import cl.lossless.player.MainActivity
import cl.lossless.player.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Servicio en primer plano que descarga la actualización: Android no lo detiene cuando la app
 * pasa a segundo plano o se apaga la pantalla. Muestra el avance en una notificación con botón
 * "Cancelar". Al terminar abre el instalador si la app está a la vista; si no, deja una
 * notificación "Toca para instalar".
 */
class UpdateDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.update_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            AppUpdater.cancel()
            if (AppUpdater.job?.isActive != true) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (AppUpdater.job?.isActive == true) return START_NOT_STICKY
        val update = pending
        if (update == null) {
            // startForegroundService exige pasar a primer plano aunque no haya nada que hacer.
            startForeground(NOTIFICATION_ID, emptyNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, progressNotification(update, 0f), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        AppUpdater.job = scope.launch {
            try {
                val apk = AppUpdater.download(this@UpdateDownloadService, update) { p ->
                    notifications.notify(NOTIFICATION_ID, progressNotification(update, p))
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                if (apk != null) {
                    val install = AppUpdater.installIntent(this@UpdateDownloadService, apk)
                    if (AppUpdater.isInForeground()) {
                        startActivity(install)
                    } else {
                        notifications.notify(RESULT_ID, resultNotification(
                            getString(R.string.update_ready_title, update.version),
                            getString(R.string.update_ready_body),
                            PendingIntent.getActivity(this@UpdateDownloadService, 1, install, PendingIntent.FLAG_IMMUTABLE),
                        ))
                    }
                } else {
                    notifications.notify(RESULT_ID, resultNotification(
                        getString(R.string.update_interrupted_title),
                        getString(R.string.update_interrupted_body),
                        openAppIntent(),
                    ))
                }
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                AppUpdater.job = null
                pending = null
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun progressNotification(update: AvailableUpdate, progress: Float): Notification {
        val cancel = PendingIntent.getService(
            this, 2, Intent(this, UpdateDownloadService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_resuen)
            .setContentTitle(getString(R.string.update_notification_title, update.version))
            .setContentText(getString(R.string.update_downloading, (progress * 100).toInt()))
            .setProgress(100, (progress * 100).toInt(), progress <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(Notification.Action.Builder(null as Icon?, getString(R.string.update_cancel), cancel).build())
            .build()
    }

    private fun emptyNotification(): Notification =
        Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_resuen).build()

    private fun resultNotification(title: String, text: String, tap: PendingIntent): Notification =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_resuen)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val CHANNEL = "updates"
        private const val NOTIFICATION_ID = 7301
        private const val RESULT_ID = 7302
        private const val ACTION_CANCEL = "cl.lossless.player.update.CANCEL"

        /** La versión a descargar; el servicio corre en el mismo proceso que la app. */
        @Volatile
        private var pending: AvailableUpdate? = null

        fun start(context: Context, update: AvailableUpdate) {
            pending = update
            context.getSystemService(NotificationManager::class.java).cancel(RESULT_ID)
            context.startForegroundService(Intent(context, UpdateDownloadService::class.java))
        }
    }
}

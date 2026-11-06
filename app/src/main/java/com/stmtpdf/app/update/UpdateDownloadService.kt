package com.stmtpdf.app.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.stmtpdf.app.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 应用内更新下载：前台服务，切后台/锁屏继续，通知栏显示进度。 */
class UpdateDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var lastNotified = -1
    private lateinit var manager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(NotificationManager::class.java)
        ensureChannels()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            job?.cancel()
            UpdateState.reset()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        val url = intent?.getStringExtra(EXTRA_URL)
        val version = intent?.getStringExtra(EXTRA_VERSION)
        if (url.isNullOrBlank() || version.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val info = UpdateInfo(
            version = version,
            url = url,
            sha256 = intent.getStringExtra(EXTRA_SHA) ?: "",
            sizeBytes = intent.getLongExtra(EXTRA_SIZE, 0L),
            force = false
        )

        job?.cancel()
        lastNotified = -1
        UpdateState.set(UpdateProgress(UpdatePhase.DOWNLOADING))
        startForeground(NOTIF_PROGRESS, progressNotification(0))

        job = scope.launch {
            try {
                val file = UpdateChecker.download(
                    context = this@UpdateDownloadService,
                    info = info,
                    onProgress = { percent, received, total ->
                        UpdateState.set(
                            UpdateProgress(UpdatePhase.DOWNLOADING, percent, received, total)
                        )
                        notifyProgress(percent)
                    },
                    isCancelled = { !isActive }
                )
                UpdateState.set(
                    UpdateProgress(
                        phase = UpdatePhase.READY,
                        percent = 100,
                        received = file.length(),
                        total = file.length(),
                        apkPath = file.absolutePath
                    )
                )
                notifyReady()
                stopForegroundCompat()
                stopSelf()
            } catch (e: CancellationException) {
                UpdateState.reset()
                stopForegroundCompat()
                stopSelf()
            } catch (e: Throwable) {
                UpdateState.set(
                    UpdateProgress(
                        phase = UpdatePhase.FAILED,
                        error = UpdateErrorText.of(e.message, info.sizeBytes)
                    )
                )
                manager.cancel(NOTIF_PROGRESS)
                stopForegroundCompat()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 通知

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Update download", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_READY, "Update ready", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun progressNotification(percent: Int): Notification {
        val p = percent.coerceIn(0, 100)
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading update…")
            .setContentText("$p%")
            .setProgress(100, p, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "Cancel", cancelIntent())
            .build()
    }

    private fun notifyProgress(percent: Int) {
        val bucket = percent / 5 * 5
        if (bucket == lastNotified) return
        lastNotified = bucket
        manager.notify(NOTIF_PROGRESS, progressNotification(percent))
    }

    private fun notifyReady() {
        val notification = NotificationCompat.Builder(this, CHANNEL_READY)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Update ready")
            .setContentText("Tap to install the new version.")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        manager.cancel(NOTIF_PROGRESS)
        manager.notify(NOTIF_READY, notification)
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_INSTALL, true)
        }
        return PendingIntent.getActivity(this, 0, intent, pendingFlags())
    }

    private fun cancelIntent(): PendingIntent {
        val intent = Intent(this, UpdateDownloadService::class.java).setAction(ACTION_CANCEL)
        return PendingIntent.getService(this, 1, intent, pendingFlags())
    }

    private fun pendingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        private const val CHANNEL_PROGRESS = "update_progress"
        private const val CHANNEL_READY = "update_ready"
        private const val NOTIF_PROGRESS = 4101
        private const val NOTIF_READY = 4102

        private const val ACTION_CANCEL = "com.stmtpdf.app.update.CANCEL"
        private const val EXTRA_URL = "url"
        private const val EXTRA_VERSION = "version"
        private const val EXTRA_SHA = "sha"
        private const val EXTRA_SIZE = "size"
        const val EXTRA_OPEN_INSTALL = "open_install"

        fun start(context: Context, info: UpdateInfo) {
            val intent = Intent(context, UpdateDownloadService::class.java).apply {
                putExtra(EXTRA_URL, info.url)
                putExtra(EXTRA_VERSION, info.version)
                putExtra(EXTRA_SHA, info.sha256)
                putExtra(EXTRA_SIZE, info.sizeBytes)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, UpdateDownloadService::class.java).setAction(ACTION_CANCEL)
            context.startService(intent)
        }
    }
}

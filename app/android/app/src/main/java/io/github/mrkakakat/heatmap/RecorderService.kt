package io.github.mrkakakat.heatmap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.File

/**
 * Foreground service (type specialUse) that keeps recording order books while the app is closed.
 * Started every time the app opens; the notification's "Стоп" button stops it until the next launch.
 */
class RecorderService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var recorder: Recorder? = null
    private var picker: CoinPicker? = null
    @Volatile private var stopping = false
    @Volatile private var text = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        text = getString(R.string.recorder_starting)
        goForeground(buildNotification(text))
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "heatmap:recorder")
            .apply { setReferenceCounted(false); acquire() }
        val rec = Recorder(ColumnStore(historyDir(this)), ::onStatus).apply { start() }
        recorder = rec
        RecorderHub.recorder = rec
        picker = CoinPicker(CoinPicker.watchlistFile(filesDir), rec::setSymbols).apply { start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopping = true
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // Every startForegroundService() call must be answered with startForeground().
        goForeground(buildNotification(text))
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        RecorderHub.recorder = null
        picker?.stop()
        picker = null
        recorder?.stop()
        recorder = null
        wakeLock?.release()
        wakeLock = null
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    /** Called on the recorder thread whenever the coin list or sync state changes. */
    private fun onStatus(st: Recorder.Status) {
        if (stopping) return
        text = getString(R.string.recorder_status, st.symbols.size)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun goForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val ch = NotificationChannel(CHANNEL_ID, getString(R.string.recorder_channel), NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return b.setSmallIcon(R.drawable.ic_stat_heatmap)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(null, getString(R.string.recorder_stop), stop).build())
            .build()
    }

    companion object {
        const val ACTION_STOP = "io.github.mrkakakat.heatmap.action.STOP"
        private const val CHANNEL_ID = "recorder"
        private const val NOTIFICATION_ID = 1

        fun historyDir(ctx: Context) = File(ctx.filesDir, "heat")

        fun start(ctx: Context) {
            val i = Intent(ctx, RecorderService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}

package io.github.mrkakakat.heatmap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.TrafficStats
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import java.io.File
import java.util.Calendar
import java.util.Locale

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
    @Volatile private var status: Recorder.Status? = null
    private lateinit var traffic: TrafficCounter
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, REFRESH_MS) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        traffic = TrafficCounter(prefs.getInt(KEY_DAY, 0), prefs.getLong(KEY_TODAY, 0), prefs.getLong(KEY_LAST, -1))
        text = getString(R.string.recorder_starting)
        goForeground(buildNotification(text))
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "heatmap:recorder")
            .apply { setReferenceCounted(false); acquire() }
        val rec = Recorder(ColumnStore(historyDir(this)), ::onStatus).apply { start() }
        recorder = rec
        RecorderHub.recorder = rec
        picker = CoinPicker(CoinPicker.watchlistFile(filesDir), rec::setSymbols).apply { start() }
        handler.postDelayed(ticker, REFRESH_MS)
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
        handler.removeCallbacks(ticker)
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

    /** Called on the recorder thread whenever the coin list, sync state or mode changes. */
    private fun onStatus(st: Recorder.Status) {
        status = st
        refresh()
    }

    /** "Запись N монет · фон/активно · X МБ сегодня"; runs on the recorder thread and every 10 s on main. */
    @Synchronized
    private fun refresh() {
        if (stopping) return
        val st = status ?: return
        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        val total = if (rx < 0 || tx < 0) -1L else rx + tx
        val c = Calendar.getInstance()
        val day = c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
        val bytes = traffic.update(total, day)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt(KEY_DAY, traffic.day).putLong(KEY_TODAY, traffic.today).putLong(KEY_LAST, traffic.lastTotal).apply()
        val mb = bytes / 1_048_576.0
        val mbText = String.format(Locale.forLanguageTag("ru"), if (mb < 10) "%.1f" else "%.0f", mb)
        val mode = getString(if (st.active) R.string.recorder_mode_active else R.string.recorder_mode_background)
        text = getString(R.string.recorder_status, st.symbols.size, mode, mbText)
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
        private const val REFRESH_MS = 10_000L
        private const val PREFS = "recorder"
        private const val KEY_DAY = "trafficDay"
        private const val KEY_TODAY = "trafficToday"
        private const val KEY_LAST = "trafficLastTotal"

        fun historyDir(ctx: Context) = File(ctx.filesDir, "heat")

        fun start(ctx: Context) {
            val i = Intent(ctx, RecorderService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}

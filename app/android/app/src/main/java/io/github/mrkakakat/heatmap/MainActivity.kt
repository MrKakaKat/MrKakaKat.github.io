package io.github.mrkakakat.heatmap

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(HeatRecorderPlugin::class.java)   // must precede super.onCreate, which builds the bridge
        super.onCreate(savedInstanceState)
        RecorderService.start(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        } else {
            askBatteryExemption()
        }
    }

    override fun onStart() {
        super.onStart()
        RecorderHub.setActive(true)
    }

    override fun onStop() {
        RecorderHub.setActive(false)
        super.onStop()
    }

    @Deprecated("Activity result API is not used here; the framework callback is enough for one permission")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATIONS) askBatteryExemption()
    }

    /** Recording keeps running with the screen off only if Doze leaves the app alone. Asked at most once a day. */
    private fun askBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_BATTERY_ASKED, 0) < 24 * 3600_000L) return
        prefs.edit().putLong(KEY_BATTERY_ASKED, now).apply()
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            // Some vendor builds have no such screen; the service still runs, Doze may just pause it.
        }
    }

    companion object {
        private const val REQ_NOTIFICATIONS = 1
        private const val PREFS = "main"
        private const val KEY_BATTERY_ASKED = "batteryAskedAt"
    }
}

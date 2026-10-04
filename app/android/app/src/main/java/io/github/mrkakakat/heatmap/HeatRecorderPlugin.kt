package io.github.mrkakakat.heatmap

import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Bridge between index.html and the recorder:
 *  - getHistory({symbol, hours}) -> {path, count, rows, from, to}: columns written to a cache file
 *    (format in [HistoryFile]); the page reads it via Capacitor.convertFileSrc(path)
 *  - setActive({active}) -> switches the recorder between on-screen and background speed
 *  - getWatchlist() -> contents of watchlist.json plus {recording, active}
 */
@CapacitorPlugin(name = "HeatRecorder")
class HeatRecorderPlugin : Plugin() {

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "heat-history").apply { isDaemon = true } }

    @PluginMethod
    fun getHistory(call: PluginCall) {
        val symbol = call.getString("symbol")?.uppercase()
        if (symbol.isNullOrEmpty() || !symbol.matches(Regex("[A-Z0-9]+"))) {
            call.reject("symbol required")
            return
        }
        val hours = (call.getDouble("hours", 1.0) ?: 1.0).coerceIn(0.01, 24.0)
        io.execute {
            try {
                val now = System.currentTimeMillis()
                val from = now - (hours * 3_600_000).toLong()
                val store = ColumnStore(RecorderService.historyDir(context))
                val cols = try { store.read(symbol, from, now + 60_000) } finally { store.close() }
                val dir = File(context.cacheDir, "history").apply { mkdirs() }
                dir.listFiles()?.forEach { if (now - it.lastModified() > 60_000) it.delete() }
                val f = File(dir, "$symbol-$now.bin")
                HistoryFile.write(f, cols)
                call.resolve(
                    JSObject().put("path", f.absolutePath).put("count", cols.size).put("rows", ColumnCodec.ROWS)
                        .put("from", from).put("to", now)
                )
            } catch (e: Exception) {
                call.reject("history read failed: ${e.message}", e)
            }
        }
    }

    @PluginMethod
    fun setActive(call: PluginCall) {
        RecorderHub.setActive(call.getBoolean("active", true) ?: true)
        call.resolve()
    }

    @PluginMethod
    fun getWatchlist(call: PluginCall) {
        val saved = CoinPicker.read(CoinPicker.watchlistFile(context.filesDir)) ?: JSONObject()
        val out = JSObject.fromJSONObject(saved)
        if (!out.has("symbols")) out.put("symbols", JSArray())
        out.put("recording", RecorderHub.recorder != null)
        out.put("active", RecorderHub.active)
        call.resolve(out)
    }
}

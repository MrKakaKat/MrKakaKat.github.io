package io.github.mrkakakat.heatmap

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Re-selects the recorded coins every 15 minutes (see [CoinSelection]) and saves the result to
 * [file] (watchlist.json), which also seeds the next start and answers getWatchlist() in the app.
 * Network failures keep the previous list.
 */
class CoinPicker(
    private val file: File,
    private val onPick: (List<String>) -> Unit,
    private val bybit: String = "https://api.bybit.com",
    private val binance: String = Binance.REST,
    private val coingecko: String = "https://api.coingecko.com",
) {
    private val exec = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "coin-picker").apply { isDaemon = true } }
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    private var caps: Map<String, Double>? = null
    private var capsAt = 0L
    @Volatile private var current: List<String> = emptyList()

    fun start() {
        current = readSymbols(file) ?: CoinSelection.PINNED
        onPick(current)
        exec.scheduleWithFixedDelay({
            try { pick() } catch (e: Exception) { Log.w(TAG, "coin selection failed: ${e.message}") }
        }, 0, INTERVAL_MIN, TimeUnit.MINUTES)
    }

    fun stop() {
        exec.shutdownNow()
        http.dispatcher.executorService.shutdown()
    }

    private fun pick() {
        val tickers = getJson("$bybit/v5/market/tickers?category=linear").getJSONObject("result").getJSONArray("list")
            .objects().map { CoinSelection.Ticker(it.getString("symbol"), it.optString("turnover24h").toDoubleOrNull() ?: 0.0) }
        val onBinance = Binance.parseTicks(getJson(Binance.exchangeInfoUrl(binance))).keys
        refreshCaps()
        val atr = HashMap<String, Double>()
        for (t in CoinSelection.universe(tickers, caps, onBinance)) {
            try {
                val list = getJson("$bybit/v5/market/kline?category=linear&symbol=${t.symbol}&interval=15&limit=60")
                    .getJSONObject("result").getJSONArray("list")
                val candles = (list.length() - 1 downTo 0).map { i ->
                    val k = list.getJSONArray(i)
                    doubleArrayOf(k.getString(2).toDouble(), k.getString(3).toDouble(), k.getString(4).toDouble())
                }
                CoinSelection.atrPct(candles)?.let { atr[t.symbol] = it }
            } catch (e: Exception) {
                Log.w(TAG, "ATR ${t.symbol}: ${e.message}")
            }
        }
        val res = CoinSelection.select(tickers, caps, onBinance, atr, current)
        current = res.symbols
        save(res)
        onPick(res.symbols)
    }

    /** CoinGecko top 500 by market cap, refreshed hourly; a failed refresh keeps the last good map. */
    private fun refreshCaps() {
        if (caps != null && System.currentTimeMillis() - capsAt < 3_600_000) return
        try {
            val m = HashMap<String, Double>()
            for (page in 1..2) {
                val arr = JSONArray(get("$coingecko/api/v3/coins/markets?vs_currency=usd&order=market_cap_desc&per_page=250&page=$page"))
                for (c in arr.objects()) m.putIfAbsent(c.optString("symbol").lowercase(), c.optDouble("market_cap", 0.0))
            }
            caps = m
            capsAt = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.w(TAG, "CoinGecko: ${e.message}")
        }
    }

    private fun save(res: CoinSelection.Result) {
        val o = JSONObject()
            .put("updatedAt", System.currentTimeMillis())
            .put("symbols", JSONArray(res.symbols))
            .put("pinned", JSONArray(CoinSelection.PINNED))
            .put("capsOk", caps != null)
            .put("medianAtrPct", res.median)
            .put("ranked", JSONArray().also { a ->
                for (e in res.ranked) a.put(
                    JSONObject().put("symbol", e.symbol).put("atrPct", e.atrPct).put("turnover", e.turnover)
                        .put("cap", e.cap ?: JSONObject.NULL).put("recorded", e.symbol in res.symbols)
                )
            })
        val tmp = File(file.path + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private fun get(url: String): String =
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for $url")
            r.body?.string() ?: throw IOException("empty body for $url")
        }

    private fun getJson(url: String) = JSONObject(get(url))

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    companion object {
        private const val TAG = "CoinPicker"
        private const val INTERVAL_MIN = 15L

        fun watchlistFile(dir: File) = File(dir, "watchlist.json")

        fun read(file: File): JSONObject? = try { JSONObject(file.readText()) } catch (e: Exception) { null }

        fun readSymbols(file: File): List<String>? =
            read(file)?.optJSONArray("symbols")?.let { a -> (0 until a.length()).map { a.getString(it) } }
    }
}

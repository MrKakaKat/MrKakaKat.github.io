package io.github.mrkakakat.heatmap

import org.json.JSONArray
import org.json.JSONObject

/** Binance USD-M futures endpoints and JSON parsing (pure, no I/O). */
object Binance {
    const val REST = "https://fapi.binance.com"
    const val WS = "wss://fstream.binance.com/stream"

    fun depthUrl(rest: String, symbol: String) = "$rest/fapi/v1/depth?symbol=$symbol&limit=1000"
    fun exchangeInfoUrl(rest: String) = "$rest/fapi/v1/exchangeInfo"

    class Snapshot(val updateId: Long, val bids: List<Level>, val asks: List<Level>)

    fun parseLevels(arr: JSONArray?): List<Level> {
        if (arr == null) return emptyList()
        val out = ArrayList<Level>(arr.length())
        for (i in 0 until arr.length()) {
            val l = arr.getJSONArray(i)
            out.add(Level(l.getString(0).toDouble(), l.getString(1).toDouble()))
        }
        return out
    }

    /** "data" of a combined-stream depthUpdate message. */
    fun parseDepthEvent(data: JSONObject) = DepthEvent(
        firstId = data.getLong("U"),
        lastId = data.getLong("u"),
        prevId = data.getLong("pu"),
        bids = parseLevels(data.optJSONArray("b")),
        asks = parseLevels(data.optJSONArray("a")),
    )

    fun parseSnapshot(o: JSONObject) = Snapshot(
        updateId = o.getLong("lastUpdateId"),
        bids = parseLevels(o.optJSONArray("bids")),
        asks = parseLevels(o.optJSONArray("asks")),
    )

    /** Tick size of every trading USDT perpetual, by symbol. */
    fun parseTicks(info: JSONObject): Map<String, Double> {
        val out = HashMap<String, Double>()
        val symbols = info.optJSONArray("symbols") ?: return out
        for (i in 0 until symbols.length()) {
            val s = symbols.getJSONObject(i)
            if (s.optString("status") != "TRADING" || s.optString("contractType") != "PERPETUAL" ||
                s.optString("quoteAsset") != "USDT"
            ) continue
            val filters = s.optJSONArray("filters") ?: continue
            for (j in 0 until filters.length()) {
                val f = filters.getJSONObject(j)
                if (f.optString("filterType") == "PRICE_FILTER") {
                    val tick = f.optString("tickSize").toDoubleOrNull()
                    if (tick != null && tick > 0) out[s.getString("symbol")] = tick
                }
            }
        }
        return out
    }
}

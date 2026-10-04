package io.github.mrkakakat.heatmap

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class BinanceTest {

    @Test
    fun parsesDepthUpdate() {
        val msg = JSONObject(
            """{"stream":"btcusdt@depth@500ms","data":{"e":"depthUpdate","E":1,"T":1,"s":"BTCUSDT",
               "U":157,"u":160,"pu":149,"b":[["84833.90","1.250"],["84833.80","0"]],"a":[["84834.00","0.010"]]}}"""
        )
        val ev = Binance.parseDepthEvent(msg.getJSONObject("data"))
        assertEquals(157L, ev.firstId); assertEquals(160L, ev.lastId); assertEquals(149L, ev.prevId)
        assertEquals(84833.9, ev.bids[0].price, 0.0); assertEquals(1.25, ev.bids[0].qty, 0.0)
        assertEquals(0.0, ev.bids[1].qty, 0.0)
        assertEquals(84834.0, ev.asks[0].price, 0.0)
    }

    @Test
    fun parsesTicksOfTradingUsdtPerpetualsOnly() {
        val info = JSONObject(
            """{"symbols":[
              {"symbol":"BTCUSDT","status":"TRADING","contractType":"PERPETUAL","quoteAsset":"USDT",
               "filters":[{"filterType":"PRICE_FILTER","tickSize":"0.10"},{"filterType":"LOT_SIZE","stepSize":"0.001"}]},
              {"symbol":"BTCUSDT_261225","status":"TRADING","contractType":"CURRENT_QUARTER","quoteAsset":"USDT",
               "filters":[{"filterType":"PRICE_FILTER","tickSize":"0.10"}]},
              {"symbol":"OLDUSDT","status":"SETTLING","contractType":"PERPETUAL","quoteAsset":"USDT",
               "filters":[{"filterType":"PRICE_FILTER","tickSize":"0.001"}]},
              {"symbol":"ZECUSDT","status":"TRADING","contractType":"PERPETUAL","quoteAsset":"USDT",
               "filters":[{"filterType":"PRICE_FILTER","tickSize":"0.01"}]}]}"""
        )
        assertEquals(mapOf("BTCUSDT" to 0.1, "ZECUSDT" to 0.01), Binance.parseTicks(info))
    }
}

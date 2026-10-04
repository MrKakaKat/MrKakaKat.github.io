package io.github.mrkakakat.heatmap

import io.github.mrkakakat.heatmap.CoinSelection.Ticker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoinSelectionTest {

    @Test
    fun baseOfMatchesScreener() {
        assertEquals("pepe", CoinSelection.baseOf("1000PEPEUSDT"))
        assertEquals("shib", CoinSelection.baseOf("SHIB1000USDT"))
        assertEquals("1inch", CoinSelection.baseOf("1INCHUSDT"))
        assertEquals("btc", CoinSelection.baseOf("BTCUSDT"))
        assertEquals("bonk", CoinSelection.baseOf("1000000BONKUSDT"))
    }

    /** 60 coins C00..C59 with ATR% 60..1 (C00 most volatile), all liquid, capped and on Binance. */
    private val names = (0 until 60).map { "C%02dUSDT".format(it) }
    private val tickers = names.map { Ticker(it, 200e6) }
    private val caps = names.associate { CoinSelection.baseOf(it) to 1e9 }
    private val binance = names.toSet() + "ZECUSDT"
    private val atr = names.withIndex().associate { (i, s) -> s to (60 - i).toDouble() }

    @Test
    fun takesTop15PlusPinned() {
        val r = CoinSelection.select(tickers, caps, binance, atr, current = emptyList())
        assertEquals(listOf("ZECUSDT") + names.take(15), r.symbols)
        assertEquals(30.5, r.median, 1e-9)
    }

    @Test
    fun keepsRecordedCoinsWhileInTop25() {
        val current = listOf("C17USDT", "C24USDT", "C25USDT", "C30USDT")
        val r = CoinSelection.select(tickers, caps, binance, atr, current)
        // C17 and C24 (ranks 18 and 25) stay; C25 (rank 26) and C30 fall out.
        assertEquals(listOf("ZECUSDT") + names.take(15) + listOf("C17USDT", "C24USDT"), r.symbols)
    }

    @Test
    fun dropsCoinsBelowMedianOfFilteredUniverse() {
        // Only 10 coins pass: top 15 would take all 10, the median cut keeps the upper half.
        val few = tickers.take(10)
        val r = CoinSelection.select(few, caps, binance, atr, current = emptyList())
        assertEquals(listOf("ZECUSDT") + names.take(5), r.symbols)
    }

    @Test
    fun appliesTurnoverCapAndBinanceFilters() {
        val t = listOf(
            Ticker("AAAUSDT", 50e6),           // turnover too low
            Ticker("BBBUSDT", 500e6),          // cap too small
            Ticker("CCCUSDT", 500e6),          // not on Binance
            Ticker("DDDUSDT", 500e6),
            Ticker("EEE-PERP", 500e6),         // not a USDT contract
        )
        val c = mapOf("aaa" to 1e9, "bbb" to 1e8, "ccc" to 1e9, "ddd" to 1e9)
        val b = setOf("AAAUSDT", "BBBUSDT", "DDDUSDT", "EEE-PERP")
        val a = mapOf("AAAUSDT" to 9.0, "BBBUSDT" to 9.0, "CCCUSDT" to 9.0, "DDDUSDT" to 1.0, "EEE-PERP" to 9.0)
        assertEquals(listOf("DDDUSDT"), CoinSelection.select(t, c, b, a, emptyList()).symbols)
        // Without CoinGecko the cap filter is skipped: BBB is back and, being more volatile, wins the median cut.
        assertEquals(listOf("BBBUSDT"), CoinSelection.select(t, null, b, a, emptyList()).symbols)
    }

    @Test
    fun pinnedCoinMustTradeOnBinance() {
        val r = CoinSelection.select(tickers, caps, names.toSet(), atr, emptyList())
        assertEquals(names.take(15), r.symbols)
    }

    @Test
    fun atrIsWilderOverTrueRange() {
        // Constant 2-wide candles around 100 -> ATR 2 -> 2 %.
        val c = List(60) { doubleArrayOf(101.0, 99.0, 100.0) }
        assertEquals(2.0, CoinSelection.atrPct(c)!!, 1e-9)
        assertNull(CoinSelection.atrPct(emptyList()))
    }
}

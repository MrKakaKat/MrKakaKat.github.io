package io.github.mrkakakat.heatmap

/**
 * Which coins the recorder follows, by the same rules as the screener in index.html:
 * Bybit linear USDT contracts with 24h turnover >= 100M and market cap >= 300M (CoinGecko top 500),
 * that also trade as Binance USD-M perpetuals, ranked by 15m ATR%.
 *
 * Top 15 get in; a coin already recorded stays while it is in the top 25. Coins whose ATR% is below
 * the median of everything that passed the filters are left out. Pinned coins are always recorded.
 */
object CoinSelection {
    const val MIN_TURNOVER = 100e6
    const val MIN_CAP = 300e6
    const val ENTER_TOP = 15
    const val KEEP_TOP = 25
    val PINNED = listOf("ZECUSDT")

    class Ticker(val symbol: String, val turnover: Double)

    class Entry(val symbol: String, val turnover: Double, val cap: Double?, val atrPct: Double)

    class Result(val symbols: List<String>, val ranked: List<Entry>, val median: Double)

    private val USDT = Regex("^[A-Z0-9]+USDT$")

    /** "1000PEPEUSDT" -> "pepe", "SHIB1000USDT" -> "shib", "1INCHUSDT" -> "1inch" (as in index.html). */
    fun baseOf(symbol: String): String = symbol.removeSuffix("USDT")
        .replace(Regex("^(10|100|1000|10000|100000|1000000)(?=[A-Z])"), "")
        .removeSuffix("1000")
        .lowercase()

    /** Contracts that pass turnover, cap and Binance filters; ATR is fetched only for these. */
    fun universe(tickers: List<Ticker>, caps: Map<String, Double>?, binance: Set<String>): List<Ticker> =
        tickers.filter {
            USDT.matches(it.symbol) && it.turnover >= MIN_TURNOVER && it.symbol in binance &&
                (caps == null || (caps[baseOf(it.symbol)] ?: 0.0) >= MIN_CAP)
        }

    /**
     * @param caps market caps by lowercase base symbol, or null when CoinGecko is unavailable
     *             (then the cap filter is skipped, as the screener does)
     * @param atr  15m ATR% by symbol; coins without one are not ranked
     */
    fun select(
        tickers: List<Ticker>,
        caps: Map<String, Double>?,
        binance: Set<String>,
        atr: Map<String, Double>,
        current: Collection<String>,
        pinned: List<String> = PINNED,
    ): Result {
        val ranked = universe(tickers, caps, binance)
            .mapNotNull { t -> atr[t.symbol]?.let { Entry(t.symbol, t.turnover, caps?.get(baseOf(t.symbol)), it) } }
            .sortedByDescending { it.atrPct }
        val median = median(ranked.map { it.atrPct })
        val picked = ranked.filterIndexed { i, e ->
            (i < ENTER_TOP || (i < KEEP_TOP && e.symbol in current)) && e.atrPct >= median
        }
        val symbols = (pinned.filter { it in binance } + picked.map { it.symbol }).distinct()
        return Result(symbols, ranked, median)
    }

    fun median(xs: List<Double>): Double {
        if (xs.isEmpty()) return 0.0
        val s = xs.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    /** Wilder ATR(14) of candles (high, low, close), oldest first, as % of the last close; as in the screener. */
    fun atrPct(candles: List<DoubleArray>): Double? {
        var atr: Double? = null
        for (n in 1 until candles.size) {
            val (h, l, _) = candles[n]
            val pc = candles[n - 1][2]
            val tr = maxOf(h - l, kotlin.math.abs(h - pc), kotlin.math.abs(l - pc))
            atr = if (atr == null) tr else (atr * 13 + tr) / 14
        }
        val last = candles.lastOrNull()?.get(2) ?: return null
        return if (atr == null || last <= 0) null else atr / last * 100
    }
}

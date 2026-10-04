package io.github.mrkakakat.heatmap

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * One heatmap column: resting volume summed per price bucket around mid at [time].
 * Row r covers bucket (top - r), i.e. prices [(top - r) * step, (top - r + 1) * step); row 0 is the highest.
 * Rows hold uint8 log-scale levels relative to [scale], the column's largest bucket volume (see [ColumnCodec]).
 */
class Column(val time: Long, val step: Double, val top: Long, val scale: Float, val rows: ByteArray)

object ColumnCodec {
    const val ROWS = 2048           // ±1024 buckets around mid
    private const val HALF = ROWS / 2
    private val LN_RANGE = ln(1e5)  // levels 1..255 span scale/1e5 .. scale; 0 = empty bucket

    fun build(book: LocalBook, step: Double, time: Long): Column? {
        val bb = book.bestBid(); val ba = book.bestAsk()
        if (bb.isNaN() || ba.isNaN()) return null
        val top = bucket((bb + ba) / 2, step) + HALF
        val acc = DoubleArray(ROWS)
        for (m in arrayOf(book.bids, book.asks)) {
            for ((p, q) in m) {
                val r = top - bucket(p, step)
                if (r in 0 until ROWS) acc[r.toInt()] += q
            }
        }
        val max = acc.max()
        if (max <= 0) return null
        return Column(time, step, top, max.toFloat(), ByteArray(ROWS) { encode(acc[it], max).toByte() })
    }

    /** Same bucketing as index.html: floor(price / step) with a little slack for float noise. */
    fun bucket(price: Double, step: Double): Long = floor(price / step + 1e-9).toLong()

    fun encode(v: Double, scale: Double): Int {
        if (v <= 0) return 0
        val t = (1 + ln(v / scale) / LN_RANGE).coerceIn(0.0, 1.0)
        return 1 + (254 * t).roundToInt()
    }

    fun decode(level: Int, scale: Double): Double =
        if (level == 0) 0.0 else scale * exp(((level - 1) / 254.0 - 1) * LN_RANGE)

    /**
     * Bucket step for a contract tick: tick * 3 rounded up to the 1-2-5 ladder, exactly like
     * pickBucket() in index.html, so recorded history lines up with the heatmap's default step.
     */
    fun stepForTick(tick: Double): Double {
        val ladder = mutableListOf(tick)
        var e = floor(log10(tick)).toInt()
        repeat(12) {
            for (m in intArrayOf(1, 2, 5)) {
                val v = BigDecimal(m).scaleByPowerOfTen(e).setScale(12, RoundingMode.HALF_UP).toDouble()
                if (v > ladder.last() * 1.0001) ladder.add(v)
            }
            e++
        }
        val target = tick * 3
        return ladder.firstOrNull { it >= target } ?: ladder.last()
    }
}

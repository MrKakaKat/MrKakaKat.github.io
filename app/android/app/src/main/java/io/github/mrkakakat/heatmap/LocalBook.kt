package io.github.mrkakakat.heatmap

/** One Binance USD-M diff-depth event: levels changed between update ids [firstId, lastId]. */
class DepthEvent(
    val firstId: Long,   // U
    val lastId: Long,    // u
    val prevId: Long,    // pu: lastId of the previous event in this stream
    val bids: List<Level>,
    val asks: List<Level>,
)

/** Price level; qty is the absolute size at that price, 0 removes the level. */
class Level(val price: Double, val qty: Double)

/**
 * Local order book kept in sync with Binance diff-depth events, per
 * https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/How-to-manage-a-local-order-book-correctly
 *
 * Events are buffered until a REST snapshot arrives. After that every event must continue the
 * previous one (pu == last applied u); otherwise the book reports a gap and buffers again until
 * the next snapshot. Not thread-safe: the recorder calls it from one thread.
 */
class LocalBook {
    val bids = HashMap<Double, Double>()
    val asks = HashMap<Double, Double>()

    var synced = false
        private set
    var lastId = 0L
        private set

    private val buffer = ArrayDeque<DepthEvent>()

    enum class Result { APPLIED, BUFFERED, SKIPPED, GAP }

    fun onEvent(ev: DepthEvent): Result {
        if (!synced) {
            buffer.addLast(ev)
            while (buffer.size > MAX_BUFFER) buffer.removeFirst()
            return Result.BUFFERED
        }
        if (ev.lastId <= lastId) return Result.SKIPPED
        // pu < lastId is fine: levels carry absolute sizes, so an event that overlaps what was
        // already applied (e.g. right after switching 500ms <-> 100ms streams) still lands correctly.
        if (ev.prevId > lastId) {
            invalidate()
            buffer.addLast(ev)
            return Result.GAP
        }
        apply(ev)
        return Result.APPLIED
    }

    /**
     * Installs a REST snapshot and replays the buffered events after it.
     * Returns false if the buffer does not connect to the snapshot; the caller should fetch a newer one.
     */
    fun applySnapshot(updateId: Long, snapBids: List<Level>, snapAsks: List<Level>): Boolean {
        while (buffer.isNotEmpty() && buffer.first().lastId < updateId) buffer.removeFirst()
        val first = buffer.firstOrNull()
        if (first != null && first.firstId > updateId) return false
        bids.clear(); asks.clear()
        for (l in snapBids) if (l.qty > 0) bids[l.price] = l.qty
        for (l in snapAsks) if (l.qty > 0) asks[l.price] = l.qty
        lastId = updateId
        synced = true
        val pending = buffer.toList()
        buffer.clear()
        for ((i, ev) in pending.withIndex()) {
            if (onEvent(ev) == Result.GAP) {
                // Keep the newer events buffered so the next snapshot can still connect to them.
                for (j in i + 1 until pending.size) buffer.addLast(pending[j])
                return false
            }
        }
        return true
    }

    /** Drops the book and starts buffering for a new snapshot (gap, reconnect, stream change). */
    fun invalidate() {
        synced = false
        buffer.clear()
        bids.clear(); asks.clear()
    }

    fun bestBid(): Double = bids.keys.maxOrNull() ?: Double.NaN
    fun bestAsk(): Double = asks.keys.minOrNull() ?: Double.NaN

    /** Drops levels further than [frac] from mid, so the deep book stays bounded over 24h of diffs. */
    fun trim(frac: Double) {
        val bb = bestBid(); val ba = bestAsk()
        if (bb.isNaN() || ba.isNaN()) return
        val mid = (bb + ba) / 2
        val lo = mid * (1 - frac); val hi = mid * (1 + frac)
        bids.keys.removeAll { it < lo }
        asks.keys.removeAll { it > hi }
    }

    private fun apply(ev: DepthEvent) {
        for (l in ev.bids) if (l.qty > 0) bids[l.price] = l.qty else bids.remove(l.price)
        for (l in ev.asks) if (l.qty > 0) asks[l.price] = l.qty else asks.remove(l.price)
        lastId = ev.lastId
    }

    companion object {
        private const val MAX_BUFFER = 2000
    }
}

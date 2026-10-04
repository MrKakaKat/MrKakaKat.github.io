package io.github.mrkakakat.heatmap

/**
 * Bytes the app moved today, from cumulative per-UID counters (TrafficStats rx + tx since boot).
 * Survives restarts through the persisted fields; a counter that went down means a reboot.
 * The count restarts at local midnight ([day] is yyyymmdd).
 */
class TrafficCounter(var day: Int, var today: Long, var lastTotal: Long) {

    /** Feeds the current cumulative total (negative = unsupported) and returns today's bytes. */
    fun update(total: Long, nowDay: Int): Long {
        if (nowDay != day) { day = nowDay; today = 0 }
        if (total < 0) return today
        val delta = when {
            lastTotal < 0 -> 0L              // first sample ever
            total >= lastTotal -> total - lastTotal
            else -> total                    // rebooted: counters restarted from zero
        }
        today += delta
        lastTotal = total
        return today
    }
}

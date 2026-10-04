package io.github.mrkakakat.heatmap

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficCounterTest {

    @Test
    fun countsDeltasResetsAtMidnightAndSurvivesReboot() {
        val c = TrafficCounter(day = 0, today = 0, lastTotal = -1)
        assertEquals(0L, c.update(5_000, 20261004))      // first sample only sets the baseline
        assertEquals(1_000L, c.update(6_000, 20261004))
        assertEquals(1_000L, c.update(-1, 20261004))      // unsupported reading changes nothing
        assertEquals(1_500L, c.update(6_500, 20261004))
        assertEquals(500L, c.update(7_000, 20261005))     // new day: only traffic after midnight
        assertEquals(800L, c.update(300, 20261005))       // reboot: counters restarted at 0
        assertEquals(1_000L, c.update(500, 20261005))
    }
}

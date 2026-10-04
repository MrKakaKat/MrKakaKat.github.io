package io.github.mrkakakat.heatmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBookTest {

    private fun ev(first: Long, last: Long, prev: Long, bids: List<Level> = emptyList(), asks: List<Level> = emptyList()) =
        DepthEvent(first, last, prev, bids, asks)

    private fun lv(p: Double, q: Double) = Level(p, q)

    @Test
    fun syncsFromSnapshotAndReplaysBufferedEvents() {
        val b = LocalBook()
        assertEquals(LocalBook.Result.BUFFERED, b.onEvent(ev(90, 95, 89, bids = listOf(lv(9.0, 1.0)))))   // older than snapshot
        assertEquals(LocalBook.Result.BUFFERED, b.onEvent(ev(96, 105, 95, bids = listOf(lv(10.0, 7.0)))))  // spans snapshot id 100
        assertEquals(LocalBook.Result.BUFFERED, b.onEvent(ev(106, 110, 105, asks = listOf(lv(11.0, 0.0)))))
        assertTrue(b.applySnapshot(100, listOf(lv(10.0, 2.0), lv(9.5, 3.0)), listOf(lv(11.0, 4.0), lv(12.0, 5.0))))
        assertTrue(b.synced)
        assertEquals(110L, b.lastId)
        assertEquals(7.0, b.bids[10.0]!!, 0.0)
        assertEquals(null, b.bids[9.0])          // the pre-snapshot event was dropped
        assertEquals(null, b.asks[11.0])         // qty 0 removes the level
        assertEquals(10.0, b.bestBid(), 0.0)
        assertEquals(12.0, b.bestAsk(), 0.0)
    }

    @Test
    fun liveEventsMustChainOnPu() {
        val b = LocalBook()
        assertTrue(b.applySnapshot(100, listOf(lv(10.0, 1.0)), listOf(lv(11.0, 1.0))))
        assertEquals(LocalBook.Result.APPLIED, b.onEvent(ev(101, 120, 100, bids = listOf(lv(10.0, 3.0)))))
        assertEquals(LocalBook.Result.SKIPPED, b.onEvent(ev(110, 120, 105)))
        assertEquals(LocalBook.Result.APPLIED, b.onEvent(ev(121, 130, 120)))
        assertEquals(LocalBook.Result.GAP, b.onEvent(ev(140, 150, 135)))
        assertFalse(b.synced)
        assertTrue(b.bids.isEmpty())
        // The gap event stays buffered; a snapshot inside it connects again.
        assertTrue(b.applySnapshot(145, listOf(lv(10.0, 9.0)), listOf(lv(11.0, 1.0))))
        assertEquals(150L, b.lastId)
    }

    @Test
    fun overlappingEventAfterStreamSwitchIsAccepted() {
        val b = LocalBook()
        assertTrue(b.applySnapshot(100, listOf(lv(10.0, 1.0)), listOf(lv(11.0, 1.0))))
        b.onEvent(ev(101, 110, 100))
        // A 500ms stream event covering (95, 130] overlaps what the 100ms stream already applied.
        assertEquals(LocalBook.Result.APPLIED, b.onEvent(ev(96, 130, 95, bids = listOf(lv(10.0, 4.0)))))
        assertEquals(130L, b.lastId)
        assertEquals(4.0, b.bids[10.0]!!, 0.0)
    }

    @Test
    fun snapshotOlderThanBufferIsRejectedAndBufferKept() {
        val b = LocalBook()
        b.onEvent(ev(200, 210, 199))
        b.onEvent(ev(211, 220, 210))
        assertFalse(b.applySnapshot(150, emptyList(), emptyList()))
        assertFalse(b.synced)
        assertTrue(b.applySnapshot(205, listOf(lv(1.0, 1.0)), listOf(lv(2.0, 1.0))))
        assertEquals(220L, b.lastId)
    }

    @Test
    fun gapInsideReplayKeepsNewerEvents() {
        val b = LocalBook()
        b.onEvent(ev(96, 105, 95))
        b.onEvent(ev(120, 130, 115))   // gap after 105
        b.onEvent(ev(131, 140, 130))
        assertFalse(b.applySnapshot(100, emptyList(), emptyList()))
        assertTrue(b.applySnapshot(125, listOf(lv(1.0, 1.0)), listOf(lv(2.0, 1.0))))
        assertEquals(140L, b.lastId)
    }

    @Test
    fun trimDropsFarLevels() {
        val b = LocalBook()
        b.applySnapshot(1, listOf(lv(100.0, 1.0), lv(80.0, 1.0)), listOf(lv(101.0, 1.0), lv(130.0, 1.0)))
        b.trim(0.15)
        assertEquals(setOf(100.0), b.bids.keys)
        assertEquals(setOf(101.0), b.asks.keys)
    }
}

package io.github.mrkakakat.heatmap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile
import kotlin.math.abs

class ColumnTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun stepMatchesIndexHtmlPickBucket() {
        // Expected values computed with makeBuckets()/pickBucket() from terminal/index.html.
        val cases = mapOf(
            0.1 to 0.5, 0.01 to 0.05, 0.001 to 0.005, 0.0001 to 0.0005, 0.00001 to 0.00005, 1.0 to 5.0,
            0.5 to 2.0, 0.25 to 1.0, 0.05 to 0.2, 0.02 to 0.1, 0.005 to 0.02, 10.0 to 50.0, 1e-7 to 5e-7,
        )
        for ((tick, step) in cases) assertEquals("tick $tick", step, ColumnCodec.stepForTick(tick), step * 1e-9)
    }

    @Test
    fun logLevelsRoundTripWithinFivePercent() {
        val scale = 1234.5
        assertEquals(0, ColumnCodec.encode(0.0, scale))
        assertEquals(255, ColumnCodec.encode(scale, scale))
        assertEquals(1, ColumnCodec.encode(scale * 1e-9, scale))   // tiny but present stays non-zero
        var v = scale
        while (v > scale * 1e-5) {
            val back = ColumnCodec.decode(ColumnCodec.encode(v, scale), scale)
            assertTrue("$v -> $back", abs(back / v - 1) < 0.05)
            v *= 0.83
        }
    }

    @Test
    fun buildSumsBucketsAroundMid() {
        val b = LocalBook()
        b.applySnapshot(1, listOf(Level(100.0, 2.0), Level(99.6, 3.0), Level(99.4, 4.0)), listOf(Level(100.2, 5.0), Level(100.4, 1.0)))
        val c = ColumnCodec.build(b, 0.5, 42)!!
        // mid 100.1 -> bucket 200; top = 200 + 1024. Bucket 200 = [100.0, 100.5) holds 2 + 5 + 1.
        assertEquals(1224L, c.top)
        assertEquals(8f, c.scale)
        assertEquals(255, c.rows[1024].toInt() and 0xff)
        assertEquals(3.0, ColumnCodec.decode(c.rows[1025].toInt() and 0xff, 8.0), 0.15)   // [99.5, 100.0)
        assertEquals(4.0, ColumnCodec.decode(c.rows[1026].toInt() and 0xff, 8.0), 0.2)    // [99.0, 99.5)
        assertEquals(0, c.rows[1023].toInt())
        assertNull(ColumnCodec.build(LocalBook(), 0.5, 0))
    }

    @Test
    fun storeRoundTripsAcrossHoursAndPrunes() {
        val store = ColumnStore(tmp.root)
        val h = ColumnStore.HOUR_MS
        val t0 = 1000 * h + 10_000
        val cols = (0 until 5).map { i ->
            Column(t0 + i * (h / 2), 0.5, 2000L + i, 10f + i, ByteArray(ColumnCodec.ROWS) { (it * 7 + i).toByte() })
        }
        cols.forEach { store.append("BTCUSDT", it) }
        val all = store.read("BTCUSDT", 0, Long.MAX_VALUE)
        assertEquals(5, all.size)
        for ((a, b) in cols.zip(all)) {
            assertEquals(a.time, b.time); assertEquals(a.top, b.top); assertEquals(a.scale, b.scale)
            assertEquals(a.step, b.step, 0.0); assertArrayEquals(a.rows, b.rows)
        }
        assertEquals(listOf(cols[1].time, cols[2].time), store.read("BTCUSDT", cols[1].time, cols[3].time).map { it.time })
        assertTrue(store.read("ETHUSDT", 0, Long.MAX_VALUE).isEmpty())

        // Files for hours 1000..1002 exist; keeping 24h at hour 1026.5 drops hours 1000 and 1001.
        store.prune(1026 * h + h / 2)
        assertEquals(listOf(cols[4].time), store.read("BTCUSDT", 0, Long.MAX_VALUE).map { it.time })
        store.close()
    }

    @Test
    fun truncatedTailIsIgnored() {
        val store = ColumnStore(tmp.root)
        val t = 5000 * ColumnStore.HOUR_MS
        store.append("ZECUSDT", Column(t, 0.05, 1, 1f, ByteArray(ColumnCodec.ROWS) { 3 }))
        store.append("ZECUSDT", Column(t + 6000, 0.05, 1, 1f, ByteArray(ColumnCodec.ROWS) { 4 }))
        store.close()
        val f = tmp.root.resolve("ZECUSDT/5000.col")
        RandomAccessFile(f, "rw").use { it.setLength(it.length() - 5) }
        val got = ColumnStore(tmp.root).read("ZECUSDT", 0, Long.MAX_VALUE)
        assertEquals(listOf(t), got.map { it.time })
    }
}

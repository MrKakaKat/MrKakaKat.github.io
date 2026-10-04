package io.github.mrkakakat.heatmap

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Heatmap columns on disk: root/<SYMBOL>/<epoch hour>.col, one file per coin per hour, so 24h
 * retention is just deleting old files. A file is "HMC1" followed by records:
 *
 *   int32 length of the rest | int64 time ms | float64 step | int64 top bucket | float32 scale |
 *   int16 row count | raw-deflated uint8 rows
 *
 * Records are appended and flushed one by one; a record cut short by a crash ends the read.
 * Not thread-safe: the recorder writes from one thread, readers open their own streams.
 */
class ColumnStore(private val root: File) {

    private class Open(val hour: Long, val out: DataOutputStream)

    private val open = HashMap<String, Open>()
    private val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
    private val buf = ByteArray(ColumnCodec.ROWS * 2)

    fun append(symbol: String, c: Column) {
        val hour = c.time / HOUR_MS
        var o = open[symbol]
        if (o == null || o.hour != hour) {
            o?.out?.close()
            val dir = File(root, symbol).apply { mkdirs() }
            val f = File(dir, "$hour.col")
            val fresh = !f.exists() || f.length() == 0L
            o = Open(hour, DataOutputStream(BufferedOutputStream(FileOutputStream(f, true))))
            if (fresh) o.out.write(MAGIC)
            open[symbol] = o
        }
        deflater.reset()
        deflater.setInput(c.rows)
        deflater.finish()
        val packed = ByteArrayOutputStream(1024)
        while (!deflater.finished()) packed.write(buf, 0, deflater.deflate(buf))
        val out = o.out
        out.writeInt(HEADER_BYTES + packed.size())
        out.writeLong(c.time)
        out.writeDouble(c.step)
        out.writeLong(c.top)
        out.writeFloat(c.scale)
        out.writeShort(c.rows.size)
        packed.writeTo(out)
        out.flush()
    }

    /** Columns of [symbol] with fromMs <= time < toMs, oldest first. */
    fun read(symbol: String, fromMs: Long, toMs: Long): List<Column> {
        val dir = File(root, symbol)
        val files = dir.listFiles { f -> f.name.endsWith(".col") } ?: return emptyList()
        val result = ArrayList<Column>()
        val inflater = Inflater(true)
        try {
            for (f in files.sortedBy { it.name.removeSuffix(".col").toLongOrNull() ?: Long.MAX_VALUE }) {
                val hour = f.name.removeSuffix(".col").toLongOrNull() ?: continue
                if ((hour + 1) * HOUR_MS <= fromMs || hour * HOUR_MS >= toMs) continue
                readFile(f, inflater) { c -> if (c.time in fromMs until toMs) result.add(c) }
            }
        } finally {
            inflater.end()
        }
        return result
    }

    /** Deletes hour files that ended before now - keepMs, and coin folders left empty. */
    fun prune(nowMs: Long, keepMs: Long = DAY_MS) {
        for (dir in root.listFiles() ?: return) {
            if (!dir.isDirectory) continue
            for (f in dir.listFiles() ?: continue) {
                val hour = f.name.removeSuffix(".col").toLongOrNull()
                if (hour == null || (hour + 1) * HOUR_MS <= nowMs - keepMs) {
                    if (open[dir.name]?.hour == hour) open.remove(dir.name)?.out?.close()
                    f.delete()
                }
            }
            if (dir.list()?.isEmpty() == true) dir.delete()
        }
    }

    /** Total size of recorded files, bytes. */
    fun sizeBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun close() {
        for (o in open.values) runCatching { o.out.close() }
        open.clear()
        deflater.end()
    }

    private inline fun readFile(f: File, inflater: Inflater, sink: (Column) -> Unit) {
        DataInputStream(f.inputStream().buffered()).use { inp ->
            val magic = ByteArray(MAGIC.size)
            if (inp.read(magic) != magic.size || !magic.contentEquals(MAGIC)) return
            try {
                while (true) {
                    val len = inp.readInt()
                    if (len < HEADER_BYTES || len > HEADER_BYTES + MAX_PACKED) return
                    val time = inp.readLong()
                    val step = inp.readDouble()
                    val top = inp.readLong()
                    val scale = inp.readFloat()
                    val n = inp.readShort().toInt()
                    val packed = ByteArray(len - HEADER_BYTES)
                    inp.readFully(packed)
                    if (n <= 0 || n > MAX_ROWS) return
                    val rows = ByteArray(n)
                    inflater.reset()
                    inflater.setInput(packed)
                    var got = 0
                    while (got < n && !inflater.finished()) {
                        val k = inflater.inflate(rows, got, n - got)
                        if (k == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                        got += k
                    }
                    if (got != n) return
                    sink(Column(time, step, top, scale, rows))
                }
            } catch (e: EOFException) {
                // End of file, or a record cut short by a crash.
            }
        }
    }

    companion object {
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 24 * HOUR_MS
        private val MAGIC = "HMC1".toByteArray()
        private const val HEADER_BYTES = 8 + 8 + 8 + 4 + 2
        private const val MAX_ROWS = 8192
        private const val MAX_PACKED = 1 shl 20
    }
}

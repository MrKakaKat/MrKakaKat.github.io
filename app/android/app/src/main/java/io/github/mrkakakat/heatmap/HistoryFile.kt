package io.github.mrkakakat.heatmap

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Uncompressed column dump handed to the web page (read with DataView in index.html), big-endian:
 *
 *   "HMH1" | int32 column count | int32 rows per column |
 *   per column: float64 time ms | float64 step | float64 top bucket | float32 scale | uint8[rows]
 */
object HistoryFile {
    const val HEADER_BYTES = 12
    const val COLUMN_HEADER_BYTES = 8 + 8 + 8 + 4

    fun write(f: File, cols: List<Column>, rows: Int = ColumnCodec.ROWS) {
        DataOutputStream(BufferedOutputStream(f.outputStream(), 1 shl 16)).use { out ->
            val usable = cols.filter { it.rows.size == rows }
            out.write("HMH1".toByteArray())
            out.writeInt(usable.size)
            out.writeInt(rows)
            for (c in usable) {
                out.writeDouble(c.time.toDouble())
                out.writeDouble(c.step)
                out.writeDouble(c.top.toDouble())
                out.writeFloat(c.scale)
                out.write(c.rows)
            }
        }
    }
}

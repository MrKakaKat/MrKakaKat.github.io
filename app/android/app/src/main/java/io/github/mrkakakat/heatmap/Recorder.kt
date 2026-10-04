package io.github.mrkakakat.heatmap

import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Keeps a local Binance USD-M order book per coin and writes one heatmap column per coin at a
 * fixed interval. All state lives on one thread ([exec]); network callbacks only parse and post.
 *
 * One combined WebSocket carries every coin's diff-depth stream; coins are added and removed with
 * SUBSCRIBE / UNSUBSCRIBE. Each book is (re)synced from a REST snapshot, one snapshot at a time.
 */
class Recorder(
    private val store: ColumnStore,
    private val onStatus: (Status) -> Unit,
    private val restBase: String = Binance.REST,
    private val wsUrl: String = Binance.WS,
) {
    class Status(val symbols: List<String>, val synced: Int, val active: Boolean)

    enum class Speed(val suffix: String, val columnMs: Long) {
        ACTIVE("@depth@100ms", 2_000),
        BACKGROUND("@depth@500ms", 6_000),
    }

    private class Sym(val name: String, val step: Double) {
        val book = LocalBook()
        var snapshotAt = Long.MAX_VALUE   // when to fetch a snapshot; MAX = not needed
        var fails = 0
    }

    private val exec = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "recorder").apply { isDaemon = true } }.apply {
        executeExistingDelayedTasksAfterShutdownPolicy = false
    }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val syms = LinkedHashMap<String, Sym>()
    private var wanted: List<String> = emptyList()
    private var ticks: Map<String, Double> = emptyMap()
    private var ticksRequestedAt = 0L
    private var speed = Speed.BACKGROUND

    private var ws: WebSocket? = null
    private var wsGen = 0
    private var wsOpen = false
    private var lastMessageAt = 0L
    private var reconnectDelay = MIN_RECONNECT_MS
    private var requestId = 0

    private var snapshotInFlight = false
    private var snapshotsPausedUntil = 0L
    private var nextColumnAt = 0L
    private var stopped = false

    fun start() {
        exec.execute { loadTicks(); connect() }
        exec.scheduleWithFixedDelay(::tick, 250, 250, TimeUnit.MILLISECONDS)
        exec.scheduleWithFixedDelay(::watchdog, 5, 5, TimeUnit.SECONDS)
        exec.scheduleWithFixedDelay({ syms.values.forEach { it.book.trim(TRIM_FRACTION) } }, 30, 30, TimeUnit.SECONDS)
        exec.scheduleWithFixedDelay({ safe { store.prune(System.currentTimeMillis()) } }, 0, 10, TimeUnit.MINUTES)
        exec.scheduleWithFixedDelay(::loadTicks, 1, 1, TimeUnit.HOURS)
    }

    fun stop() {
        post {
            stopped = true
            ws?.close(1000, null)
            ws = null
            safe { store.close() }
            exec.shutdown()
        }
        http.dispatcher.executorService.shutdown()
    }

    /** Coins to record; ones not trading on Binance USD-M are skipped. */
    fun setSymbols(symbols: List<String>) = post { wanted = symbols.distinct(); applySymbols() }

    // ---------------------------------------------------------------- symbols

    private fun loadTicks() {
        ticksRequestedAt = System.currentTimeMillis()
        get(Binance.exchangeInfoUrl(restBase)) { body ->
            val t = Binance.parseTicks(JSONObject(body))
            post { if (t.isNotEmpty()) { ticks = t; applySymbols() } }
        }
    }

    private fun applySymbols() {
        if (ticks.isEmpty() || stopped) return
        val target = wanted.filter { it in ticks }.associateWith { ColumnCodec.stepForTick(ticks.getValue(it)) }
        val drop = syms.values.filter { target[it.name] != it.step }
        if (drop.isNotEmpty()) {
            drop.forEach { syms.remove(it.name) }
            send("UNSUBSCRIBE", drop.map { stream(it.name) })
        }
        val add = target.filterKeys { it !in syms }.map { (name, step) -> Sym(name, step) }
        if (add.isNotEmpty()) {
            add.forEach { syms[it.name] = it; it.snapshotAt = System.currentTimeMillis() + SNAPSHOT_AFTER_SUBSCRIBE_MS }
            send("SUBSCRIBE", add.map { stream(it.name) })
        }
        status()
    }

    private fun stream(symbol: String) = symbol.lowercase() + speed.suffix

    // ---------------------------------------------------------------- websocket

    private fun connect() {
        if (stopped) return
        val gen = ++wsGen
        wsOpen = false
        ws = http.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = post {
                if (gen != wsGen) return@post
                wsOpen = true
                lastMessageAt = System.currentTimeMillis()
                reconnectDelay = MIN_RECONNECT_MS
                val now = System.currentTimeMillis()
                for (s in syms.values) { s.book.invalidate(); s.snapshotAt = now + SNAPSHOT_AFTER_SUBSCRIBE_MS }
                send("SUBSCRIBE", syms.keys.map(::stream))
                status()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Parse on the socket thread; only the book update runs on the recorder thread.
                val parsed = runCatching {
                    val o = JSONObject(text)
                    val data = o.optJSONObject("data")
                    if (data != null && data.optString("e") == "depthUpdate")
                        Triple(o.optString("stream"), data.getString("s"), Binance.parseDepthEvent(data))
                    else null
                }.getOrNull()
                post {
                    if (gen != wsGen) return@post
                    lastMessageAt = System.currentTimeMillis()
                    if (parsed != null) onDepth(parsed.first, parsed.second, parsed.third)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = post { dropped(gen) }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = post {
                Log.w(TAG, "websocket failed: ${t.message}")
                dropped(gen)
            }
        })
    }

    private fun dropped(gen: Int) {
        if (gen != wsGen || stopped) return
        ws = null
        wsOpen = false
        syms.values.forEach { it.book.invalidate(); it.snapshotAt = Long.MAX_VALUE }
        status()
        exec.schedule(::connect, reconnectDelay, TimeUnit.MILLISECONDS)
        reconnectDelay = (reconnectDelay * 2).coerceAtMost(MAX_RECONNECT_MS)
    }

    private fun send(method: String, params: List<String>) {
        if (!wsOpen || params.isEmpty()) return
        // Binance caps a connection at 10 incoming messages per second; batching keeps us far below.
        for (chunk in params.chunked(50)) {
            ws?.send(JSONObject().put("method", method).put("params", JSONArray(chunk)).put("id", ++requestId).toString())
        }
    }

    private fun watchdog() {
        if (wsOpen && System.currentTimeMillis() - lastMessageAt > STALE_MS) {
            Log.w(TAG, "no data for ${STALE_MS / 1000}s, reconnecting")
            ws?.cancel()   // reported through onFailure -> dropped -> reconnect
        }
    }

    private fun onDepth(stream: String, symbol: String, ev: DepthEvent) {
        val s = syms[symbol] ?: return
        if (stream != stream(symbol)) return   // leftover of a stream we already left
        val r = s.book.onEvent(ev)
        if (r == LocalBook.Result.GAP) {
            Log.i(TAG, "$symbol: gap at ${ev.firstId}, resyncing")
            s.snapshotAt = System.currentTimeMillis()
            status()
        }
    }

    // ---------------------------------------------------------------- snapshots + columns

    private fun tick() {
        val now = System.currentTimeMillis()
        if (ticks.isEmpty() && now - ticksRequestedAt > 30_000) loadTicks()
        if (now >= nextColumnAt) {
            val ms = speed.columnMs
            if (nextColumnAt != 0L) writeColumns(now / ms * ms)
            nextColumnAt = (now / ms + 1) * ms
        }
        if (wsOpen && !snapshotInFlight && now >= snapshotsPausedUntil) {
            syms.values.firstOrNull { !it.book.synced && now >= it.snapshotAt }?.let(::fetchSnapshot)
        }
    }

    private fun fetchSnapshot(s: Sym) {
        snapshotInFlight = true
        s.snapshotAt = Long.MAX_VALUE
        val req = Request.Builder().url(Binance.depthUrl(restBase, s.name)).build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = post { snapshotFailed(s, null) }

            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val snap = response.use { r ->
                    if (r.isSuccessful) runCatching { Binance.parseSnapshot(JSONObject(r.body!!.string())) }.getOrNull() else null
                }
                post {
                    if (snap == null) { snapshotFailed(s, code); return@post }
                    snapshotInFlight = false
                    if (syms[s.name] !== s || s.book.synced) return@post
                    if (s.book.applySnapshot(snap.updateId, snap.bids, snap.asks)) {
                        s.fails = 0
                        status()
                    } else {
                        retry(s)
                    }
                }
            }
        })
    }

    private fun snapshotFailed(s: Sym, httpCode: Int?) {
        snapshotInFlight = false
        // 429 / 418: Binance rate limit or IP ban warning; back off every snapshot for a minute.
        if (httpCode == 429 || httpCode == 418) snapshotsPausedUntil = System.currentTimeMillis() + 60_000
        if (syms[s.name] === s) retry(s)
    }

    private fun retry(s: Sym) {
        s.fails++
        s.snapshotAt = System.currentTimeMillis() + (1_000L shl (s.fails - 1).coerceAtMost(5))
    }

    private fun writeColumns(time: Long) {
        for (s in syms.values) {
            if (!s.book.synced) continue
            val c = ColumnCodec.build(s.book, s.step, time) ?: continue
            safe { store.append(s.name, c) }
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Runs on the recorder thread; silently dropped once the recorder is stopped. */
    private fun post(block: () -> Unit) {
        try { exec.execute(block) } catch (e: RejectedExecutionException) { /* stopped */ }
    }

    private fun status() {
        onStatus(Status(syms.keys.toList(), syms.values.count { it.book.synced }, speed == Speed.ACTIVE))
    }

    private fun get(url: String, onBody: (String) -> Unit) {
        http.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { Log.w(TAG, "GET $url: ${e.message}") }
            override fun onResponse(call: Call, response: Response) {
                val body = response.use { if (it.isSuccessful) it.body?.string() else null } ?: return
                safe { onBody(body) }
            }
        })
    }

    private inline fun safe(block: () -> Unit) {
        try { block() } catch (e: Exception) { Log.w(TAG, "recorder: ${e.message}", e) }
    }

    companion object {
        private const val TAG = "Recorder"
        private const val MIN_RECONNECT_MS = 2_000L
        private const val MAX_RECONNECT_MS = 60_000L
        private const val STALE_MS = 30_000L
        private const val SNAPSHOT_AFTER_SUBSCRIBE_MS = 1_000L
        private const val TRIM_FRACTION = 0.15
    }
}

package com.kawaiipet.app.util

import android.util.Log
import com.kawaiipet.app.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Debug-session NDJSON sink. Buffers events in memory and flushes once per turn
 * to avoid spawning a thread + HTTP POST per streamed chunk.
 */
object DebugSessionLog {
    private const val TAG = "DebugSessionLog"
    private const val ENDPOINT = "http://127.0.0.1:7932/ingest"
    private const val SESSION_ID = "886061"
    private const val MAX_BUFFER = 64
    /** Debug builds only — stage timings for VAD / ASR / LLM / TTS. */
    private val enabled: Boolean get() = BuildConfig.DEBUG

    private val buffer = ConcurrentLinkedQueue<String>()
    private val buffered = AtomicInteger(0)

    fun log(
        hypothesisId: String,
        location: String,
        message: String,
        data: Map<String, Any?> = emptyMap(),
        runId: String = "post-fix",
    ) {
        if (!enabled) return
        val payload = JSONObject()
        payload.put("sessionId", SESSION_ID)
        payload.put("hypothesisId", hypothesisId)
        payload.put("location", location)
        payload.put("message", message)
        payload.put("runId", runId)
        payload.put("timestamp", System.currentTimeMillis())
        val dataObj = JSONObject()
        for ((k, v) in data) {
            dataObj.put(k, v ?: JSONObject.NULL)
        }
        payload.put("data", dataObj)
        val line = payload.toString()
        Log.i(TAG, line)
        while (buffered.get() >= MAX_BUFFER) {
            if (buffer.poll() != null) buffered.decrementAndGet()
            else break
        }
        buffer.offer(line)
        buffered.incrementAndGet()
    }

    /** Flush buffered events once at end of a turn (daemon thread). */
    fun flushTurn(extra: Map<String, Any?> = emptyMap()) {
        if (!enabled) return
        if (extra.isNotEmpty()) {
            log(
                hypothesisId = "TRACE",
                location = "DebugSessionLog.flushTurn",
                message = "turn complete",
                data = extra,
            )
        }
        val batch = ArrayList<String>(buffered.get())
        while (true) {
            val line = buffer.poll() ?: break
            buffered.decrementAndGet()
            batch.add(line)
        }
        if (batch.isEmpty()) return
        Thread({
            for (line in batch) {
                runCatching {
                    val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 800
                        readTimeout = 800
                        doOutput = true
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json")
                    }
                    conn.outputStream.use { it.write(line.toByteArray()) }
                    conn.responseCode
                    conn.disconnect()
                }
            }
        }, "DebugSessionLog-flush").apply { isDaemon = true }.start()
    }
}

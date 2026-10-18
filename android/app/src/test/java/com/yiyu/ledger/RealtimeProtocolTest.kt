package com.yiyu.ledger

import com.yiyu.ledger.backup.ledgerJson
import com.yiyu.ledger.cloud.RealtimeSession
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class RealtimeProtocolTest {
    @Test fun queuesPcmUntilTaskStartedAndFinishesAfterFrames() = runBlocking {
        val server = MockWebServer()
        val bytes = AtomicInteger()
        var id = ""
        server.enqueue(MockResponse().withWebSocketUpgrade(object: WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val root = ledgerJson.parseToJsonElement(text).jsonObject
                val header = root.getValue("header").jsonObject
                when (header.getValue("action").jsonPrimitive.content) {
                    "run-task" -> {
                        id = header.getValue("task_id").jsonPrimitive.content
                        assertEquals("fun-asr-realtime", root.getValue("payload").jsonObject.getValue("model").jsonPrimitive.content)
                        webSocket.send("""{"header":{"event":"task-started","task_id":"$id"}}""")
                    }
                    "finish-task" -> {
                        assertEquals(6400, bytes.get())
                        listOf("午饭15元。", "午饭13元。").forEach { sentence -> webSocket.send("""{"header":{"event":"result-generated","task_id":"$id"},"payload":{"output":{"sentence":{"begin_time":0,"sentence_end":true,"text":"$sentence"}}}}""") }
                        webSocket.send("""{"header":{"event":"task-finished","task_id":"$id"}}""")
                    }
                }
            }
            override fun onMessage(webSocket: WebSocket, data: ByteString) { bytes.addAndGet(data.size) }
        }))
        server.start()
        val client = OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url("/voice")).build()) }.build()
        val session = RealtimeSession(client, "test-placeholder", {})
        try {
            session.push(ByteArray(3200)); session.push(ByteArray(3200))
            assertEquals("午饭13元。", withTimeout(5000) { session.finish() })
        } finally { session.cancel(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll(); server.shutdown() }
    }
    @Test fun shortRecordingIsRejectedWithoutSaving() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object: WebSocketListener() {})); server.start()
        val client = OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url("/voice")).build()) }.build()
        val session = RealtimeSession(client, "test-placeholder", {})
        try { var rejected = false; try { session.finish() } catch (_: IllegalArgumentException) { rejected = true }; assertTrue(rejected) }
        finally { session.cancel(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll(); server.shutdown() }
    }
}

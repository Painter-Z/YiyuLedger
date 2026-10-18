package com.yiyu.ledger.cloud

import com.yiyu.ledger.backup.ledgerJson
import com.yiyu.ledger.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface RecognitionProvider {
    suspend fun parse(text: String, reference: String, custom: List<CustomCategory>): RecognitionResult
    fun startVoice(reference: String, custom: List<CustomCategory>, onTranscript: (String) -> Unit): RealtimeSession
}
class DirectCloudClient(private val key: String, private val systemPrompt: String,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS).build()): RecognitionProvider {
    private val reviewClient = client.newBuilder().readTimeout(15, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    override suspend fun parse(text: String, reference: String, custom: List<CustomCategory>): RecognitionResult {
        require(text.isNotBlank() && text.length <= 2000) { "请描述2000字以内的记账内容" }
        require(isDateKey(reference)) { "记账日期不正确" }
        return recognizeGrounded(text.trim(), reference, custom) { review -> requestProposals(text.trim(), reference, custom, review) }
    }
    private suspend fun requestProposals(text: String, reference: String, custom: List<CustomCategory>, review: RecognitionReview?): CloudProposalResult {
        val user = buildJsonObject {
            put("reference_date", reference); put("text", text)
            put("custom_categories", ledgerJson.encodeToJsonElement(custom))
            if (review != null) put("review", ledgerJson.encodeToJsonElement(RecognitionReview.serializer(), review))
        }
        val body = buildJsonObject {
            put("model", "qwen-plus"); put("temperature", 0)
            put("response_format", buildJsonObject { put("type", "json_object") })
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "system"); put("content", systemPrompt) })
                add(buildJsonObject { put("role", "user"); put("content", user.toString()) })
            }
        }
        val request = Request.Builder().url("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")
            .header("Authorization", "Bearer $key").post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val content = execute(request, review != null)
        try {
            val choice = ledgerJson.parseToJsonElement(content).jsonObject.getValue("choices").jsonArray.first().jsonObject
            require(choice["finish_reason"]?.jsonPrimitive?.content != "length") { "识别内容过长，请分次记录" }
            val message = choice.getValue("message").jsonObject.getValue("content").jsonPrimitive
            require(message.isString)
            return parseProposals(message.content)
        } catch (error: IllegalArgumentException) { throw error }
        catch (_: Exception) { throw IllegalStateException("识别结果不完整，请重试或手动记账") }
    }
    private suspend fun execute(request: Request, review: Boolean): String = suspendCancellableCoroutine { continuation ->
        val call = (if (review) reviewClient else client).newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object: Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(IOException("网络连接失败，请检查网络后重试")) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!response.isSuccessful) error(statusMessage(response.code))
                        val stream = response.body?.byteStream() ?: error("识别服务没有返回内容")
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) { val count = stream.read(buffer); if (count < 0) break; require(output.size() + count <= 2 * 1024 * 1024) { "识别返回内容过大" }; output.write(buffer, 0, count) }
                        val bytes = output.toByteArray()
                        if (continuation.isActive) continuation.resume(String(bytes, Charsets.UTF_8))
                    } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                }
            }
        })
    }
    override fun startVoice(reference: String, custom: List<CustomCategory>, onTranscript: (String) -> Unit) =
        RealtimeSession(client.newBuilder().readTimeout(0, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build(), key, onTranscript)
}
fun statusMessage(code: Int) = when (code) { 401, 403 -> "API Key 无效或没有模型权限，请检查百炼配置"; 429 -> "服务额度或请求频率已达上限，请稍后重试"; else -> "识别服务暂时不可用，请稍后重试" }
fun parseProposals(content: String): CloudProposalResult {
    val entries = ledgerJson.parseToJsonElement(content).jsonObject["entries"] as? JsonArray ?: error("识别结果格式不正确")
    require(entries.size <= 20) { "一次最多识别20笔账目，请分次记录" }
    return CloudProposalResult(entries.map { element ->
        val item = element.jsonObject
        fun string(name: String): String? = (item[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        CloudProposal(kind = string("kind") ?: error("识别类型缺失"), title = string("title") ?: error("识别名称缺失"),
            currency = if (item.containsKey("currency")) string("currency") ?: "unknown" else null,
            amount_text = string("amount_text"), amount_yuan = string("amount_yuan"), category_id = string("category_id"),
            occurred_on = string("occurred_on"), date_text = string("date_text"), time_hint = string("time_hint"), note = string("note"),
            source_text = string("source_text"), title_text = string("title_text"))
    })
}
class RealtimeSession(client: OkHttpClient, key: String, private val onTranscript: (String) -> Unit) {
    private val taskId = UUID.randomUUID().toString().replace("-", "")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val result = CompletableDeferred<String>()
    private val frames = ArrayDeque<ByteString>()
    private val accumulator = SpeechAccumulator()
    private var socket: WebSocket? = null
    private var started = false
    private var finishing = false
    private var finishSent = false
    private var totalBytes = 0
    init {
        val request = Request.Builder().url("wss://dashscope.aliyuncs.com/api-ws/v1/inference").header("Authorization", "Bearer $key").build()
        socket = client.newWebSocket(request, object: WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(this@RealtimeSession) {
                    socket = webSocket
                    if (result.isCompleted) { webSocket.cancel(); return }
                    webSocket.send(buildJsonObject {
                        put("header", header("run-task"))
                        put("payload", buildJsonObject {
                            put("task_group", "audio"); put("task", "asr"); put("function", "recognition"); put("model", "fun-asr-realtime")
                            put("parameters", buildJsonObject { put("format", "pcm"); put("sample_rate", 16000) })
                            put("input", buildJsonObject {})
                        })
                    }.toString())
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                synchronized(this@RealtimeSession) {
                    if (result.isCompleted) return
                    try {
                        val root = ledgerJson.parseToJsonElement(text).jsonObject
                        val header = root.getValue("header").jsonObject
                        if (header["task_id"]?.jsonPrimitive?.content != taskId) return
                        when(header["event"]?.jsonPrimitive?.content) {
                            "task-started" -> { started = true; flush() }
                            "task-failed" -> fail("语音服务未能识别，请检查密钥权限后重试")
                            "result-generated" -> {
                                val sentence = root["payload"]?.jsonObject?.get("output")?.jsonObject?.get("sentence")?.jsonObject
                                if (sentence != null) onTranscript(accumulator.accept(sentence["begin_time"]?.jsonPrimitive?.longOrNull ?: 0,
                                    sentence["text"]?.jsonPrimitive?.content ?: "", sentence["sentence_end"]?.jsonPrimitive?.booleanOrNull ?: false))
                            }
                            "task-finished" -> { result.complete(accumulator.finish()); webSocket.close(1000, "done"); scope.cancel() }
                        }
                    } catch (_: Exception) { fail("语音返回内容不完整，请重试") }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { synchronized(this@RealtimeSession) { fail(response?.let { statusMessage(it.code) } ?: "语音连接中断，请检查网络后重试") } }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { synchronized(this@RealtimeSession) { if (!result.isCompleted) fail("语音连接已断开，请重试") } }
        })
        scope.launch { delay(15000); synchronized(this@RealtimeSession) { if (!started) fail("语音连接超时，请检查网络后重试") } }
    }
    private fun header(action: String) = buildJsonObject { put("action", action); put("task_id", taskId); put("streaming", "duplex") }
    @Synchronized fun push(frame: ByteArray) {
        if (result.isCompleted || finishing) return
        require(frame.size % 2 == 0 && totalBytes + frame.size <= 960000) { "单次录音最多30秒" }
        totalBytes += frame.size; frames.addLast(frame.toByteString()); flush()
    }
    private fun flush() {
        if (!started || result.isCompleted) return
        while (frames.isNotEmpty()) if (socket?.send(frames.removeFirst()) != true) { fail("语音发送失败，请重试"); return }
        if (finishing && !finishSent) {
            finishSent = true
            socket?.send(buildJsonObject { put("header", header("finish-task")); put("payload", buildJsonObject { put("input", buildJsonObject {}) }) }.toString())
        }
    }
    suspend fun finish(): String {
        synchronized(this) {
            if (totalBytes < 6400) { cancel(); throw IllegalArgumentException("录音太短，请按住说出一笔收支") }
            finishing = true; flush()
        }
        return try { withTimeout(90000) { result.await() } }
        catch (error: TimeoutCancellationException) { cancel(); throw IOException("语音识别超时，请重试") }
    }
    @Synchronized fun cancel() { if (!result.isCompleted) result.cancel(); frames.clear(); socket?.cancel(); scope.cancel() }
    private fun fail(message: String) { if (!result.isCompleted) result.completeExceptionally(IOException(message)); frames.clear(); socket?.cancel(); scope.cancel() }
}

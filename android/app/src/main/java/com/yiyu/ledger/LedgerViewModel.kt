package com.yiyu.ledger

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yiyu.ledger.audio.VoiceRecorder
import com.yiyu.ledger.backup.*
import com.yiyu.ledger.cloud.DirectCloudClient
import com.yiyu.ledger.cloud.RealtimeSession
import com.yiyu.ledger.data.*
import com.yiyu.ledger.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.InputStreamReader
import java.util.UUID

data class EditorState(val entry: LedgerEntry, val amount: String, val editing: Boolean = false,
    val pendingId: String? = null, val pendingIndex: Int? = null, val transcript: String = "", val issues: List<String> = emptyList(), val formError: String = "",
    val proposalNumber: Int = 0, val proposalTotal: Int = 0)
data class VoiceUi(val stage: String = "idle", val seconds: Int = 0, val transcript: String = "") {
    val capturing get() = stage == "starting" || stage == "recording"
    val active get() = stage != "idle"
}
class LedgerViewModel(application: Application): AndroidViewModel(application) {
    private val app = application as LedgerApplication
    val repository = app.repository
    private val events = Channel<String>(Channel.BUFFERED)
    val messages = events.receiveAsFlow()
    val book = MutableStateFlow(Book())
    val loaded = MutableStateFlow(false)
    val hasKey = MutableStateFlow(app.keyStore.hasKey())
    val busy = MutableStateFlow("")
    val editor = MutableStateFlow<EditorState?>(null)
    val voice = MutableStateFlow(VoiceUi())
    private val recorder = VoiceRecorder(app, app.audioDirectory)
    private var session: RealtimeSession? = null
    @Volatile private var captureId: String? = null
    private var holdActive = false
    private var voiceJob: Job? = null
    private var timer: Job? = null
    init {
        viewModelScope.launch {
            try {
                repository.cleanup()
                repository.book.collect { snapshot ->
                    book.value = snapshot; loaded.value = true
                    editor.value?.let { current ->
                        if ((current.editing && snapshot.entries.none { it.id == current.entry.id }) ||
                            (current.pendingId != null && snapshot.pending.none { it.id == current.pendingId })) editor.value = null
                    }
                }
            } catch (error: Exception) { failure(error); loaded.value = true }
        }
    }
    fun notify(message: String) { events.trySend(message) }
    private fun failure(error: Throwable) {
        if (error is CancellationException) return
        val safe = error.message?.takeIf { Regex("^(?:[\\u4e00-\\u9fff]|API Key)[^\\r\\n]{0,159}$").matches(it) && !it.contains("sk-") && !it.contains("Exception") }
        val message = safe ?: "操作未能完成，请重试"
        editor.value?.let { editor.value = it.copy(formError = message) }
        notify(message)
    }
    private fun task(label: String, action: suspend () -> Unit) {
        if (busy.value.isNotEmpty() || voice.value.active) return
        busy.value = label
        viewModelScope.launch { try { action() } catch (error: Exception) { failure(error) } finally { busy.value = "" } }
    }
    private suspend fun provider(): DirectCloudClient = withContext(Dispatchers.IO) {
        DirectCloudClient(app.keyStore.read() ?: error("请先在设置中添加百炼 API Key"), app.cloudPrompt)
    }
    fun newManual() {
        if (voice.value.active || busy.value.isNotEmpty()) return
        val now = System.currentTimeMillis()
        editor.value = EditorState(LedgerEntry(UUID.randomUUID().toString(), "expense", 0, "CNY", "food", "", today(), "", "", "manual", "", "", now, now, 0), "")
    }
    fun edit(entry: LedgerEntry) { if (!voice.value.active && busy.value.isEmpty()) editor.value = EditorState(entry, decimalAmount(entry.amountMinor), editing = true) }
    fun changeEditor(transform: (EditorState) -> EditorState) { editor.value?.let { editor.value = transform(it).copy(formError = "") } }
    fun closeEditor() { if (busy.value.isEmpty()) editor.value = null }
    fun openPending(recording: PendingRecording) {
        if (recording.resultJson.isEmpty()) return
        runCatching {
            val result = pendingResult(recording)
            val draft = result.drafts.firstOrNull() ?: return
            val now = System.currentTimeMillis()
            editor.value = EditorState(LedgerEntry("${recording.id}_item_${recording.savedCount}", draft.kind, draft.amount_minor ?: 0, "CNY", draft.category_id,
                draft.title, draft.occurred_on, draft.time_hint, draft.note, recording.source, recording.id, "", now, now, 0),
                draft.amount_minor?.let(::decimalAmount) ?: "", pendingId = recording.id, pendingIndex = recording.savedCount,
                transcript = draft.source_text.ifBlank { result.transcript }, issues = draft.issues,
                proposalNumber = recording.savedCount + 1, proposalTotal = recording.savedCount + result.drafts.size)
        }.onFailure(::failure)
    }
    fun saveEditor() {
        val current = editor.value ?: return
        val entry = current.entry.copy(title = current.entry.title.trim(), note = current.entry.note.trim(), amountMinor = parseMinor(current.amount), updatedAt = System.currentTimeMillis())
        val error = validateEntry(entry, book.value.categories)
        if (error.isNotEmpty()) { editor.value = current.copy(formError = error); return }
        task("保存中") {
            repository.save(entry, current.editing, current.pendingId, current.pendingIndex)
            editor.value = null
            notify("已保存这一笔")
            if (current.pendingId != null) repository.snapshot().pending.find { it.id == current.pendingId }?.let(::openPending)
        }
    }
    fun delete(entry: LedgerEntry) = task("删除中") { repository.delete(entry.id); if (editor.value?.entry?.id == entry.id) editor.value = null; notify("已删除，统计已同步更新") }
    fun setBudget(budget: MonthlyBudget, onSuccess: () -> Unit) = task("保存预算") {
        repository.setMonthlyBudget(budget); onSuccess()
        notify(if (budget.amountMinor == 0L) "已从该月起关闭预算，之后可重新设置" else "月预算已保存，后续月份自动沿用")
    }
    fun addCategory(name: String, kind: String, onSuccess: (String) -> Unit) = task("保存分类") {
        val category = CustomCategory("custom_" + UUID.randomUUID().toString().replace("-", ""), name.trim(), categoryScope(kind))
        repository.addCategory(category)
        changeEditor { it.copy(entry = it.entry.copy(categoryId = category.id)) }
        onSuccess(category.id)
    }
    fun deleteCategory(id: String) = task("删除分类") { repository.deleteCategory(id); notify("分类已删除") }
    fun saveKey(value: String, onSuccess: () -> Unit) = task("验证密钥") {
        val key = value.trim()
        require(key.startsWith("sk-") && key.length in 10..4096 && key.none { it.isWhitespace() }) { "请填写有效的百炼 API Key" }
        DirectCloudClient(key, app.cloudPrompt).parse("连接测试，没有需要记录的账目", today(), emptyList())
        withContext(Dispatchers.IO) { app.keyStore.save(key) }
        hasKey.value = true; onSuccess(); notify("密钥已验证并加密保存")
    }
    fun clearKey() = task("移除密钥") { withContext(Dispatchers.IO) { app.keyStore.clear() }; hasKey.value = false; notify("已移除个人密钥") }
    fun parseText(text: String, onSuccess: () -> Unit) {
        if (book.value.pending.isNotEmpty()) { notify("请先处理上次未完成的记账"); return }
        task("理解记账内容") {
            val reference = today()
            val result = provider().parse(text.trim(), reference, book.value.categories)
            if (result.drafts.isEmpty()) { notify(result.issues.first()); return@task }
            val pending = PendingRecording("text_" + UUID.randomUUID().toString().replace("-", ""), "", reference, System.currentTimeMillis(), ledgerJson.encodeToString(RecognitionResult.serializer(), result), 0, 0, "text")
            repository.startPending(pending); onSuccess(); openPending(pending)
        }
    }
    fun beginRecording() {
        if (!loaded.value || voice.value.active || busy.value.isNotEmpty() || editor.value != null) return
        if (!hasKey.value) { notify("请先在设置中添加百炼 API Key"); return }
        if (book.value.pending.isNotEmpty()) { notify("请先处理上次未完成的记账"); return }
        val id = "voice_" + UUID.randomUUID().toString().replace("-", "")
        captureId = id; holdActive = true; voice.value = VoiceUi("starting")
        voiceJob = viewModelScope.launch {
            try {
                val reference = today()
                val cloud = provider()
                if (!holdActive || captureId != id) { voice.value = VoiceUi(); return@launch }
                val pending = PendingRecording(id, "recording_${id}.wav", reference, System.currentTimeMillis(), "", 0, 0, "voice")
                repository.startPending(pending)
                if (!holdActive || captureId != id) { repository.discardPending(id); voice.value = VoiceUi(); return@launch }
                val currentSession = cloud.startVoice(reference, book.value.categories) { transcript ->
                    if (captureId == id) voice.update { it.copy(transcript = transcript.take(2000)) }
                }
                session = currentSession
                withContext(Dispatchers.IO) { recorder.start(pending.audioName, currentSession::push,
                    onLimit = { viewModelScope.launch { endRecording(false) } },
                    onFailure = { message -> viewModelScope.launch { notify(message); endRecording(true) } }) }
                voice.value = voice.value.copy(stage = "recording")
                if (!holdActive || captureId != id) { endRecording(true); return@launch }
                timer = viewModelScope.launch { while (isActive) { delay(1000); voice.update { it.copy(seconds = (recorder.byteCount / 32000).coerceAtMost(30)) } } }
            } catch (error: Exception) {
                session?.cancel(); session = null
                withContext(NonCancellable + Dispatchers.IO) { runCatching { recorder.cancel() }; repository.discardPending(id) }
                voice.value = VoiceUi(); captureId = null; failure(error)
            }
        }
    }
    fun endRecording(cancel: Boolean) {
        holdActive = false
        if (voice.value.stage == "starting") return
        if (voice.value.stage != "recording") return
        timer?.cancel()
        val id = captureId ?: return
        val currentSession = session ?: return
        voice.value = voice.value.copy(stage = if (cancel) "canceling" else "recognizing")
        voiceJob = viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { recorder.stop() }
                if (cancel) {
                    currentSession.cancel(); repository.discardPending(id); notify("已取消录音")
                } else if (file == null || file.length() < 6444) {
                    currentSession.cancel(); repository.discardPending(id); notify("录音太短，请按住说出一笔收支")
                } else {
                    val transcript = currentSession.finish()
                    if (captureId != id) return@launch
                    val pending = repository.snapshot().pending.find { it.id == id } ?: return@launch
                    val result = if (transcript.isBlank()) RecognitionResult("", emptyList(), listOf("没有听清内容，请重新录入"))
                        else provider().parse(transcript, pending.referenceDate, book.value.categories)
                    repository.acceptRecognition(id, result)
                    if (result.drafts.isEmpty()) notify(result.issues.first())
                    else repository.snapshot().pending.find { it.id == id }?.let(::openPending)
                }
            } catch (error: Exception) { currentSession.cancel(); failure(error); notify("录音可在首页重试，24小时后自动清理") }
            finally { session = null; captureId = null; voice.value = VoiceUi() }
        }
    }
    fun retry(recording: PendingRecording) = task("重新识别录音") {
        val file = repository.audioFile(recording.audioName) ?: error("录音已清理，请重新录入")
        val cloud = provider()
        val currentSession = cloud.startVoice(recording.referenceDate, book.value.categories) {}
        try {
            withContext(Dispatchers.IO) { VoiceRecorder.replay(file, currentSession::push) }
            val transcript = currentSession.finish()
            val result = if (transcript.isBlank()) RecognitionResult("", emptyList(), listOf("没有听清内容，请重新录入")) else cloud.parse(transcript, recording.referenceDate, book.value.categories)
            repository.acceptRecognition(recording.id, result)
            if (result.drafts.isEmpty()) notify(result.issues.first()) else repository.snapshot().pending.find { it.id == recording.id }?.let(::openPending)
        } finally { currentSession.cancel() }
    }
    fun discard(recording: PendingRecording) = task("清理未完成记录") { repository.discardPending(recording.id); if (editor.value?.pendingId == recording.id) editor.value = null; notify("已清理未完成的记录") }
    fun export(uri: Uri, csv: Boolean) = task("导出账本") {
        withContext(Dispatchers.IO) {
            val snapshot = repository.snapshot()
            val content = if (csv) exportCsv(snapshot.entries, snapshot.categories) else exportBackup(snapshot.entries, snapshot.categories, monthlyBudgets = snapshot.monthlyBudgets)
            app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray(Charsets.UTF_8)) } ?: error("无法写入所选文件")
        }
        notify(if (csv) "账单已导出" else "备份已导出，可在鸿蒙版恢复")
    }
    fun import(uri: Uri) = task("导入账本") {
        val content = withContext(Dispatchers.IO) {
            app.contentResolver.openInputStream(uri)?.use { source ->
                InputStreamReader(source, Charsets.UTF_8).use { reader ->
                    val result = StringBuilder(); val buffer = CharArray(8192)
                    while (true) { val n = reader.read(buffer); if (n < 0) break; require(result.length + n <= 5 * 1024 * 1024) { "备份文件过大" }; result.append(buffer, 0, n) }
                    result.toString()
                }
            } ?: error("无法读取所选文件")
        }
        val document = withContext(Dispatchers.Default) { parseBackup(content.removePrefix("\uFEFF")) }
        val added = repository.importBackup(document)
        notify("导入完成，新增${added}笔；相同编号不会重复")
    }
    fun onBackground() {
        if (voice.value.capturing) endRecording(true)
    }
    fun onForeground() { if (!voice.value.active) viewModelScope.launch { runCatching { repository.cleanup() }.onFailure(::failure) } }
    override fun onCleared() {
        timer?.cancel(); session?.cancel()
        CoroutineScope(Dispatchers.IO).launch { runCatching { recorder.stop() } }
    }
}

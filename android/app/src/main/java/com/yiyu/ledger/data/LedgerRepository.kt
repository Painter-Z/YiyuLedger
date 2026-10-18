package com.yiyu.ledger.data

import androidx.room.withTransaction
import com.yiyu.ledger.backup.*
import com.yiyu.ledger.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

data class Book(val entries: List<LedgerEntry> = emptyList(), val categories: List<CustomCategory> = emptyList(), val pending: List<PendingRecording> = emptyList(), val monthlyBudgets: List<MonthlyBudget> = emptyList())
const val FAILED_RECORDING_TTL = 24 * 60 * 60 * 1000L
fun pendingResult(recording: PendingRecording): RecognitionResult {
    val result = ledgerJson.decodeFromString<RecognitionResult>(recording.resultJson)
    require(recording.resultOffset >= 0 && recording.savedCount >= recording.resultOffset && recording.savedCount <= recording.resultOffset + result.drafts.size) { "待确认记录进度异常" }
    return result.copy(drafts = result.drafts.drop(recording.savedCount - recording.resultOffset))
}
class LedgerRepository(val database: LedgerDatabase, private val audioDirectory: File) {
    private val dao = database.dao()
    val book: Flow<Book> = database.invalidationTracker.createFlow("entries", "categories", "pending", "monthly_budgets", emitInitialState = true)
        .map { snapshot() }.flowOn(Dispatchers.IO)
    suspend fun snapshot(): Book = database.withTransaction { Book(dao.entries(), dao.categories(), dao.pending(), dao.monthlyBudgets()) }
    suspend fun setMonthlyBudget(budget: MonthlyBudget) = withContext(Dispatchers.IO) {
        database.withTransaction {
            requireValid(validateMonthlyBudgets(dao.monthlyBudgets().filter { it.month != budget.month } + budget))
            dao.putBudget(budget)
        }
    }
    suspend fun save(entry: LedgerEntry, editing: Boolean = false, pendingId: String? = null, expectedIndex: Int? = null) = withContext(Dispatchers.IO) {
        var audio = ""
        database.withTransaction {
            val custom = dao.categories()
            require(entry.deletedAt == 0L) { "已删除的账单不能重新保存" }
            requireValid(validateEntry(entry, custom))
            if (pendingId != null) {
                val recording = dao.pending(pendingId) ?: error("这笔待确认账目已结束，请重新打开")
                require(recording.savedCount == expectedIndex) { "这笔账目已处理，请重新打开" }
                val result = pendingResult(recording)
                require(result.drafts.isNotEmpty()) { "没有待确认的账目" }
                val saved = entry.copy(id = "${recording.id}_item_${recording.savedCount}", source = recording.source, groupId = recording.id)
                requireValid(validateEntry(saved, custom))
                dao.insert(saved)
                val remaining = result.copy(transcript = "", drafts = result.drafts.drop(1), issues = emptyList())
                audio = recording.audioName
                if (remaining.drafts.isEmpty()) dao.deletePending(recording.id)
                else dao.putPending(recording.copy(audioName = "", resultJson = ledgerJson.encodeToString(RecognitionResult.serializer(), remaining),
                    savedCount = recording.savedCount + 1, resultOffset = recording.savedCount + 1))
            } else if (editing) require(dao.update(entry) == 1) { "这笔账目已经删除" }
            else dao.insert(entry)
        }
        deleteAudio(audio)
    }
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val audio = mutableListOf<String>()
        database.withTransaction {
            val entry = dao.entry(id) ?: return@withTransaction
            dao.deleteEntry(id)
            dao.unlink(id, System.currentTimeMillis())
            // Proposals for saved rows never remain as a second ledger; preserve only independent unsaved bills.
            if (entry.groupId.isNotEmpty()) dao.pending(entry.groupId)?.let { recording ->
                val remaining = pendingResult(recording).copy(transcript = "", issues = emptyList())
                audio += recording.audioName
                if (remaining.drafts.isEmpty()) dao.deletePending(recording.id)
                else dao.putPending(recording.copy(audioName = "", resultJson = ledgerJson.encodeToString(RecognitionResult.serializer(), remaining), resultOffset = recording.savedCount))
            }
        }
        audio.forEach(::deleteAudio)
        checkpoint()
    }
    suspend fun addCategory(category: CustomCategory) = withContext(Dispatchers.IO) {
        database.withTransaction { requireValid(validateCategoryCatalog(dao.categories() + category)); dao.addCategory(category) }
    }
    suspend fun deleteCategory(id: String) = withContext(Dispatchers.IO) {
        database.withTransaction {
            require(dao.entries().none { it.categoryId == id } && dao.pending().none { it.resultJson.isNotEmpty() && pendingResult(it).drafts.any { draft -> draft.category_id == id } }) { "该分类仍有账目使用，请先调整账目分类" }
            dao.deleteCategory(id)
        }
    }
    suspend fun importBackup(document: BackupDocument): Int = withContext(Dispatchers.IO) {
        database.withTransaction {
            val existing = dao.categories()
            val combined = mergeCategoryCatalog(existing, document.categories)
            val previousBudgets = dao.monthlyBudgets()
            val combinedBudgets = mergeMonthlyBudgets(previousBudgets, document.monthlyBudgets)
            document.entries.forEach { requireValid(validateEntry(it, combined)) }
            combined.filter { incoming -> existing.none { it.id == incoming.id } }.forEach { dao.addCategory(it) }
            combinedBudgets.filter { incoming -> previousBudgets.none { it.month == incoming.month } }.forEach { dao.putBudget(it) }
            var count = 0
            document.entries.filter { it.deletedAt == 0L }.forEach { if (dao.entry(it.id) == null) { dao.insert(it); count++ } }
            count
        }
    }
    suspend fun startPending(recording: PendingRecording) = withContext(Dispatchers.IO) {
        database.withTransaction {
            require(dao.pending().isEmpty()) { "请先处理上次未完成的记账" }
            dao.putPending(recording)
        }
    }
    suspend fun acceptRecognition(id: String, result: RecognitionResult) = withContext(Dispatchers.IO) {
        var audio = ""
        database.withTransaction {
            val current = dao.pending(id) ?: return@withTransaction
            audio = current.audioName
            if (result.drafts.isEmpty()) dao.deletePending(id)
            else dao.putPending(current.copy(audioName = "", resultJson = ledgerJson.encodeToString(RecognitionResult.serializer(), result), savedCount = 0, resultOffset = 0))
        }
        deleteAudio(audio)
    }
    suspend fun discardPending(id: String) = withContext(Dispatchers.IO) {
        var audio = ""
        database.withTransaction { audio = dao.pending(id)?.audioName ?: ""; dao.deletePending(id) }
        deleteAudio(audio)
        checkpoint()
    }
    suspend fun cleanup(now: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val release = mutableListOf<String>()
        database.withTransaction {
            dao.pending().forEach { recording ->
                if (recording.resultJson.isNotEmpty()) {
                    val result = runCatching { pendingResult(recording) }.getOrNull()
                    if (result != null) {
                        release += recording.audioName
                        if (result.drafts.isEmpty()) dao.deletePending(recording.id)
                        else if (recording.audioName.isNotEmpty()) dao.putPending(recording.copy(audioName = ""))
                    }
                } else if (now - recording.createdAt >= FAILED_RECORDING_TTL || audioFile(recording.audioName)?.length()?.let { it < 6444 } != false) {
                    release += recording.audioName; dao.deletePending(recording.id)
                }
            }
        }
        release.forEach(::deleteAudio)
        val keep = dao.pending().map { it.audioName }.toSet()
        audioDirectory.listFiles()?.filter { it.name !in keep && Regex("^recording_[a-zA-Z0-9_-]+\\.wav$").matches(it.name) }?.forEach { deleteAudio(it.name) }
    }
    fun audioFile(name: String): File? {
        if (!Regex("^recording_[a-zA-Z0-9_-]+\\.wav$").matches(name)) return null
        val file = File(audioDirectory, name)
        return file.takeIf { it.canonicalFile.parentFile == audioDirectory.canonicalFile }
    }
    private fun deleteAudio(name: String) { audioFile(name)?.delete() }
    private fun checkpoint() { runCatching { database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { while (it.moveToNext()) Unit } } }
}

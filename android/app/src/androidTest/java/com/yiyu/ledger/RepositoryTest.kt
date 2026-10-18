package com.yiyu.ledger

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.yiyu.ledger.backup.*
import com.yiyu.ledger.data.*
import com.yiyu.ledger.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import org.json.JSONObject
import androidx.test.platform.app.InstrumentationRegistry

@RunWith(AndroidJUnit4::class)
class RepositoryTest {
    private lateinit var db: LedgerDatabase
    private lateinit var repository: LedgerRepository
    private lateinit var directory: File
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        directory = File(context.cacheDir, "repository-test-${UUID.randomUUID()}").apply { mkdirs() }
        repository = LedgerRepository(db, directory)
    }
    @After fun teardown() { db.close(); directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    private fun entry(id: String = "manual_test") = LedgerEntry(id, "expense", 900, "CNY", "food", "米线", "2026-10-11", "", "", "manual", "", "", 1000, 1000, 0)
    @Test fun productionDatabaseOpensAndPersistsWithSecureDeletion() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "production-open-test-${UUID.randomUUID()}.db"
        val disk = LedgerDatabase.open(context, name)
        try {
            val saved = LedgerRepository(disk, directory)
            assertTrue(saved.snapshot().entries.isEmpty())
            saved.save(entry())
            assertEquals("米线", saved.snapshot().entries.single().title)
            disk.openHelper.readableDatabase.query("PRAGMA secure_delete").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
            saved.delete("manual_test")
            assertTrue(saved.snapshot().entries.isEmpty())
        } finally { disk.close(); context.deleteDatabase(name) }
    }
    @Test fun physicalDeletionUnlinksButPreservesIndependentRefund() = runBlocking {
        repository.save(entry())
        repository.save(entry("refund_test").copy(kind = "refund", amountMinor = 300, linkedEntryId = "manual_test"))
        repository.delete("manual_test")
        val book = repository.snapshot()
        assertEquals(1, book.entries.size); assertEquals("", book.entries.single().linkedEntryId)
        assertEquals(-300L, summarize(book.entries, periodFor("month", "2026-10-11")).netExpense)
        assertFalse(exportBackup(book.entries, book.categories).contains("manual_test"))
        db.openHelper.readableDatabase.query("SELECT count(*) FROM entries WHERE id='manual_test'").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        try { repository.save(entry(), editing = true); fail("Deleted editor must not recreate a bill") } catch (_: IllegalArgumentException) { }
    }
    @Test fun saveAndCompactAreAtomicAndDeletingSavedBillCannotResurrectIt() = runBlocking {
        val first = RecognizedDraft("expense", "米线", 900, "food", "2026-10-11", "", "", emptyList())
        val result = RecognitionResult("米线9元，地铁3元", listOf(first, first.copy(title = "地铁", amount_minor = 300, category_id = "transport")), emptyList())
        repository.startPending(PendingRecording("voice_test", "", "2026-10-11", 1000, ledgerJson.encodeToString(RecognitionResult.serializer(), result), 0, 0, "voice"))
        repository.save(entry(), pendingId = "voice_test", expectedIndex = 0)
        var book = repository.snapshot()
        assertEquals("voice_test_item_0", book.entries.single().id)
        assertEquals(1, book.pending.single().savedCount); assertEquals(1, book.pending.single().resultOffset)
        assertEquals("", pendingResult(book.pending.single()).transcript)
        assertEquals("地铁", pendingResult(book.pending.single()).drafts.single().title)
        repository.delete("voice_test_item_0")
        assertTrue(repository.snapshot().entries.isEmpty())
        repository.save(entry().copy(title = "地铁", amountMinor = 300, categoryId = "transport"), pendingId = "voice_test", expectedIndex = 1)
        book = repository.snapshot()
        assertEquals("voice_test_item_1", book.entries.single().id); assertTrue(book.pending.isEmpty())
        try { repository.save(entry(), pendingId = "voice_test", expectedIndex = 1); fail("Double save must fail") } catch (_: IllegalStateException) { }
        assertEquals(1, repository.snapshot().entries.size)
    }
    @Test fun categoryConflictAndInvalidImportLeaveExistingBookIntact() = runBlocking {
        val category = CustomCategory("custom_pet", "宠物", "expense")
        repository.addCategory(category); repository.save(entry())
        val before = repository.snapshot()
        val conflict = BackupDocument("yiyu-ledger", 2, entries = listOf(entry("new")), categories = listOf(category.copy(name = "另一种")))
        try { repository.importBackup(conflict); fail("Conflict must fail") } catch (_: IllegalArgumentException) { }
        assertEquals(before, repository.snapshot())
        val invalid = conflict.copy(categories = listOf(CustomCategory("custom_new", "新类别", "expense")), entries = listOf(entry("new"), entry("invalid").copy(amountMinor = 0)))
        try { repository.importBackup(invalid); fail("Invalid amount must fail") } catch (_: IllegalArgumentException) { }
        assertEquals(before, repository.snapshot())
        val valid = BackupDocument("yiyu-ledger", 2, entries = listOf(entry()), categories = listOf(category))
        assertEquals(0, repository.importBackup(valid)); assertEquals(before, repository.snapshot())
    }
    @Test fun unrelatedSpeechAndExpiredFailedAudioAreDiscarded() = runBlocking {
        val failed = PendingRecording("voice_failed", "recording_failed.wav", today(), 1000, "", 0, 0, "voice")
        File(directory, failed.audioName).writeBytes(ByteArray(6444))
        repository.startPending(failed)
        repository.cleanup(1000 + FAILED_RECORDING_TTL)
        assertTrue(repository.snapshot().pending.isEmpty()); assertFalse(File(directory, failed.audioName).exists())
        val unrelated = failed.copy(id = "voice_unrelated", createdAt = System.currentTimeMillis())
        File(directory, unrelated.audioName).writeBytes(ByteArray(6444)); repository.startPending(unrelated)
        repository.acceptRecognition(unrelated.id, RecognitionResult("天气很好", emptyList(), emptyList()))
        assertTrue(repository.snapshot().pending.isEmpty()); assertFalse(File(directory, unrelated.audioName).exists())
    }
    @Test fun recognizedAudioIsReleasedButUnsavedProposalSurvivesTtl() = runBlocking {
        val recording = PendingRecording("voice_keep", "recording_keep.wav", today(), 1000, "", 0, 0, "voice")
        File(directory, recording.audioName).writeBytes(ByteArray(6444)); repository.startPending(recording)
        val draft = RecognizedDraft("expense", "米线", 900, "food", today(), "", "", emptyList())
        repository.acceptRecognition(recording.id, RecognitionResult("米线9元", listOf(draft), emptyList()))
        assertFalse(File(directory, recording.audioName).exists())
        repository.cleanup(1000 + FAILED_RECORDING_TTL * 2)
        assertEquals(1, repository.snapshot().pending.size)
        assertEquals("米线", pendingResult(repository.snapshot().pending.single()).drafts.single().title)
    }
    @Test fun usedCustomCategoryCannotBeRemoved() = runBlocking {
        val category = CustomCategory("custom_pet", "宠物", "expense")
        repository.addCategory(category); repository.save(entry().copy(categoryId = category.id))
        try { repository.deleteCategory(category.id); fail("Used category must survive") } catch (_: IllegalArgumentException) { }
        assertEquals(listOf(category), repository.snapshot().categories)
    }
    @Test fun monthlyBudgetPersistsAndDeletingExpenseRefreshesConsumptionOnly() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "budget-persistence-${UUID.randomUUID()}.db"
        var disk = LedgerDatabase.open(context, name)
        try {
            var saved = LedgerRepository(disk, directory)
            saved.save(entry()); saved.setMonthlyBudget(MonthlyBudget("2026-10", 10000))
            disk.close(); disk = LedgerDatabase.open(context, name); saved = LedgerRepository(disk, directory)
            val before = saved.snapshot()
            assertEquals(9100L, monthlyBudgetProgress(before.entries, before.monthlyBudgets, "2026-10").remaining)
            saved.delete("manual_test")
            val after = saved.snapshot()
            assertEquals(before.monthlyBudgets, after.monthlyBudgets)
            assertEquals(10000L, monthlyBudgetProgress(after.entries, after.monthlyBudgets, "2026-10").remaining)
        } finally { disk.close(); context.deleteDatabase(name) }
    }
    @Test fun failedBudgetBackupImportRollsBackAndExistingMonthWins() = runBlocking {
        repository.save(entry()); repository.setMonthlyBudget(MonthlyBudget("2026-10", 10000))
        val before = repository.snapshot()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_budget BEFORE INSERT ON entries WHEN NEW.id='reject' BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
        val backup = BackupDocument("yiyu-ledger", 3, entries = listOf(entry("reject")), monthlyBudgets = listOf(MonthlyBudget("2026-10", 500), MonthlyBudget("2026-11", 20000)))
        try { repository.importBackup(backup); fail("Synthetic database error must abort all changes") } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(before, repository.snapshot())
        assertEquals(1, repository.importBackup(backup.copy(entries = listOf(entry("new")))))
        assertEquals(listOf(MonthlyBudget("2026-10", 10000), MonthlyBudget("2026-11", 20000)), repository.snapshot().monthlyBudgets)
    }
    @Test fun migrationFromReleasedSchemaPreservesBillsCategoriesAndPending() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-v1-${UUID.randomUUID()}.db"
        val schema = InstrumentationRegistry.getInstrumentation().context.assets.open("com.yiyu.ledger.data.LedgerDatabase/1.json").bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for(index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for(index in 0 until setup.length()) old.execSQL(setup.getString(index))
            old.execSQL("INSERT INTO entries VALUES ('manual_test','expense',900,'CNY','food','米线','2026-10-11','','','manual','','',1000,1000,0)")
            old.execSQL("INSERT INTO categories VALUES ('custom_pet','宠物','expense')")
            old.execSQL("INSERT INTO pending VALUES ('voice_migration','','2026-10-11',1000,'',0,0,'voice')")
            old.version = 1
        }
        val migrated = LedgerDatabase.open(context, name)
        try {
            val saved = LedgerRepository(migrated, directory)
            val book = saved.snapshot()
            assertEquals(entry(), book.entries.single()); assertEquals("宠物", book.categories.single().name)
            assertEquals("voice_migration", book.pending.single().id); assertTrue(book.monthlyBudgets.isEmpty())
            saved.setMonthlyBudget(MonthlyBudget("2026-10", 10000)); assertEquals(10000L, saved.snapshot().monthlyBudgets.single().amountMinor)
            assertEquals(2, migrated.openHelper.readableDatabase.version)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}

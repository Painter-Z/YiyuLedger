package com.yiyu.ledger

import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.yiyu.ledger.backup.ledgerJson
import com.yiyu.ledger.data.*
import com.yiyu.ledger.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Exercises only isolated synthetic pending groups on a development emulator. */
@RunWith(AndroidJUnit4::class)
class RecognitionUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<LedgerApplication>()
    private val device = UiDevice.getInstance(instrumentation)
    private val reference = "2026-10-18"
    private val original = "今天我吃了保障餐，花了八块。又喝了一碗粥，花了两块五"
    private val first = CloudProposal("expense", "保障餐", "CNY", "八块", "8.00", "food", source_text = "今天我吃了保障餐，花了八块", title_text = "保障餐")
    private val second = first.copy(title = "粥", title_text = "粥", source_text = "又喝了一碗粥，花了两块五", amount_text = "两块五", amount_yuan = "2.50")

    private fun editor(model: LedgerViewModel, number: Int): EditorState {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            var current: EditorState? = null
            instrumentation.runOnMainSync { current = model.editor.value }
            if (current?.proposalNumber == number) return current!!
            SystemClock.sleep(60)
        }
        error("Expected proposal number $number did not open")
    }

    private fun capture(name: String) {
        device.waitForIdle()
        assertTrue(device.takeScreenshot(File(app.filesDir, "recognition-v070-$name.png")))
        device.dumpWindowHierarchy(File(app.filesDir, "recognition-v070-$name.xml"))
    }

    private fun assertVisibleFooter() {
        val save = device.wait(Until.findObject(By.res("editor_save")), 5000)
        assertNotNull("Save must remain visible", save)
        assertTrue(save!!.visibleBounds.height() >= 40)
        assertTrue(save.visibleBounds.bottom <= device.displayHeight)
    }

    private fun emulatorOnly() {
        assumeTrue("Synthetic UI checks run only on the development emulator", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("gphone"))
    }

    @Test fun twoBillsAdvanceWithTheirOwnNamesSourcesAndStableProgress() = runBlocking {
        emulatorOnly()
        val repository = app.repository
        val before = repository.snapshot()
        assumeTrue("An idle test ledger is required", before.pending.isEmpty())
        val id = "recognition-v070-ui-${UUID.randomUUID()}"
        val result = normalizeCloudResult(CloudProposalResult(listOf(first, second)), original, reference)
        repository.startPending(PendingRecording(id, "", reference, System.currentTimeMillis(), "", 0, 0, "text"))
        repository.acceptRecognition(id, result)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var model: LedgerViewModel
        try {
            scenario.onActivity { activity -> model = ViewModelProvider(activity)[LedgerViewModel::class.java] }
            val pending = repository.snapshot().pending.single { it.id == id }
            instrumentation.runOnMainSync { model.openPending(pending) }
            val current = editor(model, 1)
            assertEquals("保障餐", current.entry.title); assertEquals("8.00", current.amount)
            assertEquals(2, current.proposalTotal); assertFalse(current.transcript.contains("粥"))
            assertTrue(device.wait(Until.hasObject(By.text("第1笔 / 共2笔")), 5000))
            assertVisibleFooter(); capture("first")
            device.findObject(By.res("editor_save")).click()
            val next = editor(model, 2)
            assertEquals("粥", next.entry.title); assertEquals("2.50", next.amount)
            assertEquals(2, next.proposalTotal); assertFalse(next.transcript.contains("保障餐"))
            assertTrue(device.wait(Until.hasObject(By.text("第2笔 / 共2笔")), 5000))
            val compacted = repository.snapshot().pending.single { it.id == id }
            assertEquals(1, compacted.savedCount); assertEquals(1, compacted.resultOffset)
            assertFalse(compacted.resultJson.contains("保障餐"))
            assertEquals(second.source_text, pendingResult(compacted).drafts.single().source_text)
            assertVisibleFooter(); capture("second")
            device.findObject(By.res("editor_save")).click()
            assertTrue(device.wait(Until.gone(By.res("editor_save")), 10000))
            val saved = repository.snapshot().entries.filter { it.groupId == id }.sortedBy { it.id }
            assertEquals(listOf("保障餐", "粥"), saved.map { it.title })
            assertEquals(listOf(800L, 250L), saved.map { it.amountMinor })
            assertTrue(repository.snapshot().pending.none { it.id == id })
        } finally {
            scenario.close()
            repository.discardPending(id)
            repository.snapshot().entries.filter { it.groupId == id }.forEach { repository.delete(it.id) }
            val after = repository.snapshot()
            assertEquals(before.entries.sortedBy { it.id }, after.entries.sortedBy { it.id })
            assertEquals(before.monthlyBudgets, after.monthlyBudgets)
        }
    }

    @Test fun spokenCentsAppearAs1189AndCanBeClosedWithoutSaving() = runBlocking {
        emulatorOnly()
        val repository = app.repository
        val before = repository.snapshot()
        assumeTrue("An idle test ledger is required", before.pending.isEmpty())
        val id = "recognition-v070-cents-${UUID.randomUUID()}"
        val text = "中午吃了一碗牛肉面，花了十一块八毛九"
        val proposal = first.copy(title = "牛肉面", title_text = "牛肉面", source_text = text, amount_text = "十一块八毛九", amount_yuan = "11.89")
        repository.startPending(PendingRecording(id, "", reference, System.currentTimeMillis(), "", 0, 0, "text"))
        repository.acceptRecognition(id, normalizeCloudResult(CloudProposalResult(listOf(proposal)), text, reference))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var model: LedgerViewModel
        try {
            scenario.onActivity { activity -> model = ViewModelProvider(activity)[LedgerViewModel::class.java] }
            val pending = repository.snapshot().pending.single { it.id == id }
            instrumentation.runOnMainSync { model.openPending(pending) }
            val current = editor(model, 1)
            assertEquals("11.89", current.amount); assertEquals("牛肉面", current.entry.title)
            assertEquals(text, current.transcript); assertEquals(1, current.proposalTotal)
            assertVisibleFooter(); capture("cents")
            assertFalse(device.hasObject(By.res("editor_progress")))
            device.findObject(By.desc("关闭确认页面")).click()
            assertTrue(device.wait(Until.gone(By.res("editor_save")), 5000))
            assertTrue(repository.snapshot().entries.none { it.groupId == id })
        } finally {
            scenario.close(); repository.discardPending(id)
            assertEquals(before.entries.sortedBy { it.id }, repository.snapshot().entries.sortedBy { it.id })
        }
    }
}

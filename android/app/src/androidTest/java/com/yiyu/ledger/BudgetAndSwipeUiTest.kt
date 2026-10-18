package com.yiyu.ledger

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.graphics.Rect
import android.graphics.RectF
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.yiyu.ledger.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.BeforeClass
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Synthetic financial records are permitted only on disposable development emulators. */
@RunWith(AndroidJUnit4::class)
class BudgetAndSwipeUiTest {
    companion object {
        @BeforeClass @JvmStatic fun clearGeneratedCaptures() {
            require(Build.HARDWARE in listOf("ranchu", "goldfish"))
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.filesDir, "budget-swipe").listFiles()?.filter { it.extension in listOf("png", "xml", "txt") }?.forEach { it.delete() }
        }
    }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val repository get() = (context.applicationContext as LedgerApplication).repository
    private val density get() = context.resources.displayMetrics.density
    private val directory get() = File(context.filesDir, "budget-swipe").apply { mkdirs() }
    @Before fun prepareSyntheticBook() {
        require(Build.HARDWARE in listOf("ranchu", "goldfish")) { "Tests may only run on development emulators" }
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
        runBlocking {
            val before = repository.snapshot()
            before.entries.forEach { repository.delete(it.id) }; before.pending.forEach { repository.discardPending(it.id) }
            repository.setMonthlyBudget(MonthlyBudget(today().take(7), 0))
        }
    }
    private fun find(selector: BySelector, scroll: Boolean = false, minHeightDp: Int = 0): UiObject2 {
        var upwards = false
        repeat(if(scroll) 20 else 3) {
            try {
            device.waitForIdle()
            val view = device.findObject(By.res("stats_page"))
            device.wait(Until.findObject(selector), 600)?.let { node ->
                val box = node.visibleBounds
                val viewport = view?.visibleBounds
                if(box.height() >= maxOf(1, (minHeightDp * density - 2).toInt()) &&
                    (!scroll || viewport == null || box.centerY() in (viewport.top + 4)..(viewport.bottom - 4))) {
                    SystemClock.sleep(200)
                    val settled = device.findObject(selector)
                    if(settled != null && settled.visibleBounds == box) return settled
                }
                if(box.height() > 0) upwards = viewport != null && box.top <= viewport.top + 4
            }
            if(scroll) view?.let { view ->
                // Compose can report the end of a UiObject2 scroll before the
                // viewport actually reaches its boundary. Use a slow, small
                // gesture and compare the resulting child positions instead.
                val before = view.children.map { it.visibleBounds.toString() }
                val viewport = view.visibleBounds
                val middle = viewport.top + viewport.height()/2
                val lower = viewport.top + viewport.height()*3/4
                device.swipe(viewport.centerX(), if(upwards) middle else lower,
                    viewport.centerX(), if(upwards) lower else middle, 100)
                device.waitForIdle(); SystemClock.sleep(300)
                val after = device.findObject(By.res("stats_page"))?.children?.map { it.visibleBounds.toString() }
                if(before == after) upwards = !upwards
            }
            } catch (_: StaleObjectException) {
                // IME dismissal and page transitions replace accessibility nodes.
                SystemClock.sleep(300)
            }
        }
        capture("failure")
        error("Missing node: $selector")
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync(); device.waitForIdle(); SystemClock.sleep(500)
        val output = directory
        device.takeScreenshot(File(output, "$name.png")); device.dumpWindowHierarchy(File(output, "$name.xml"))
    }
    private fun accessibilityNode(id: String, node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        if(node == null) return null
        if(node.viewIdResourceName == id) return node
        for(index in 0 until node.childCount) accessibilityNode(id, node.getChild(index))?.let { return it }
        return null
    }
    @Suppress("DEPRECATION")
    private fun checkTextFits(id: String, expected: String, ring: Rect? = null) {
        find(By.res(id), true)
        instrumentation.waitForIdleSync(); SystemClock.sleep(500)
        val key = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
        var node = accessibilityNode(id) ?: error("Missing accessibility node $id")
        var glyphs: List<RectF?>? = null
        val deadline = SystemClock.uptimeMillis() + 4000
        while(glyphs == null && SystemClock.uptimeMillis() < deadline) {
            node = accessibilityNode(id) ?: error("Missing accessibility node $id")
            val start = node.text?.toString().orEmpty().indexOf(expected)
            assertTrue("Full value must be exposed: $id / $expected / ${node.text}", start >= 0)
            val args = Bundle().apply {
                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, start)
                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, expected.length)
            }
            assertTrue(node.refreshWithExtraData(key, args))
            glyphs = node.extras.getParcelableArray(key)?.map { it as? RectF }
            if(glyphs == null) SystemClock.sleep(300)
        }
        val locations = glyphs ?: error("Renderer did not expose glyph positions: $id")
        val bounds = Rect().also(node::getBoundsInScreen)
        File(directory, "$id-bounds.txt").writeText("text=$expected\nbounds=$bounds\nring=$ring\nglyphs=$locations\n")
        assertEquals(expected.length, locations.size)
        locations.forEachIndexed { index, glyph ->
            assertNotNull("Character $index of $id must be drawn", glyph)
            val box = glyph!!
            assertTrue("Character $index clipped in $id: $box / $bounds", box.left >= bounds.left - 2 && box.right <= bounds.right + 2)
            if(ring != null && expected[index] != ' ') {
                val radius = ring.width()/2f - 28 * density
                for(x in listOf(box.left, box.right)) for(y in listOf(box.top, box.bottom)) {
                    val distance = kotlin.math.hypot(x - ring.exactCenterX(), y - ring.exactCenterY())
                    assertTrue("Ring must not overlap $id character $index: $box / $ring", distance <= radius + 2)
                }
            }
        }
    }
    private fun checkRingText() {
        val ring = find(By.res("monthly_budget_ring"), true, minHeightDp = 208).visibleBounds
        for(id in listOf("budget_state", "budget_remaining", "budget_used_percent"))
            checkTextFits(id, find(By.res(id)).text, ring)
    }
    private fun launch() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.res("home_page")), 15000)); device.waitForIdle(); SystemClock.sleep(500)
    }
    private fun navigate(index: Int) {
        repeat(3) {
            try {
                find(By.res("nav_$index")).click()
                val page = listOf("home_page", "ledger_page", "stats_page")[index]
                assertTrue(device.wait(Until.hasObject(By.res(page)), 10000))
                device.waitForIdle()
                return
            } catch (_: StaleObjectException) {
                // Closing an IME/dialog may replace accessibility nodes during its last animation frame.
                SystemClock.sleep(300)
            }
        }
        error("Navigation did not stabilize")
    }
    private fun setupBudget(amount: String) {
        find(By.res("setup_monthly_budget"), true).click()
        val input = find(By.res("monthly_budget_input"))
        input.click(); input.text = amount; device.waitForIdle()
        // Accessibility idle does not wait for the off-thread IME animation.
        SystemClock.sleep(500)
        val save = find(By.res("save_monthly_budget"))
        assertTrue("Budget save needs a full touch target", save.visibleBounds.height() >= 48 * context.resources.displayMetrics.density - 2)
        assertTrue("Save stays in the display while typing", save.visibleBounds.bottom <= device.displayHeight)
        val hierarchy = ByteArrayOutputStream().also { device.dumpWindowHierarchy(it) }
        val roots = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(hierarchy.toByteArray().inputStream()).documentElement.childNodes
        val keyboardTop = (0 until roots.length).mapNotNull { roots.item(it) as? Element }
            .filter { it.getAttribute("package") in listOf("com.google.android.inputmethod.latin", "com.android.inputmethod.latin") }
            .mapNotNull { Regex("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]").matchEntire(it.getAttribute("bounds"))?.groupValues?.get(2)?.toInt() }.minOrNull()
        assertNotNull("Keyboard must actually be visible", keyboardTop)
        if(save.visibleBounds.bottom > keyboardTop!! + 2) capture("budget-keyboard-failure")
        assertTrue("Budget save remains above the keyboard", save.visibleBounds.bottom <= keyboardTop!! + 2)
        capture("budget-keyboard")
        save.click()
        assertTrue(device.wait(Until.gone(By.res("save_monthly_budget")), 10000)); device.waitForIdle()
    }
    private fun swipeExpense() {
        val bounds = find(By.res("swipe_row_budget_ui_expense")).visibleBounds
        // Start inside the bill, beyond Android's reserved screen-edge Back gesture.
        device.swipe(bounds.right - bounds.width()/5, bounds.centerY(), bounds.left + bounds.width()/5, bounds.centerY(), 35)
        val revealed = device.wait(Until.hasObject(By.res("swipe_delete_budget_ui_expense")), 5000)
        if(!revealed) capture("swipe-failure")
        assertTrue("Left swipe reveals a button without deleting the row", revealed)
        SystemClock.sleep(500)
        val action = find(By.res("swipe_delete_budget_ui_expense")).visibleBounds
        val gap = find(By.res("swipe_gap_budget_ui_expense")).visibleBounds
        val amount = find(By.res("ledger_amount_budget_ui_expense")).visibleBounds
        assertTrue("Leave 16dp of empty space", kotlin.math.abs(gap.width() - 16 * density) <= 2)
        assertTrue("The amount must not touch the delete action", action.left - amount.right >= 16 * density - 2)
        assertTrue("Delete needs an accessible touch target", action.width() >= 76 * density - 2 && action.height() >= 48 * density - 2)
    }
    @Test fun settingBudgetOverspendingPersistenceAndSwipeDeletionStayConsistent() {
        runBlocking {
            val now = System.currentTimeMillis()
            repository.save(LedgerEntry("budget_ui_expense", "expense", 1500, "CNY", "food", "BudgetUI-Expense", today(), "", "", "manual", "", "", now, now, 0))
            repository.save(LedgerEntry("budget_ui_income", "income", 60000, "CNY", "salary", "BudgetUI-Income", today(), "", "", "manual", "", "", now-1, now-1, 0))
        }
        launch(); navigate(2)
        assertEquals("设置月预算", find(By.res("budget_state"), true).text)
        find(By.res("monthly_budget_ring"), true, minHeightDp = 208)
        capture("unset")
        setupBudget("20")
        assertEquals("剩余可用", find(By.res("budget_state"), true).text)
        assertEquals("¥ 5.00", find(By.res("budget_remaining"), true).text)
        checkRingText(); capture("available")
        navigate(1); swipeExpense()
        assertEquals(2, runBlocking { repository.snapshot().entries.size })
        capture("swipe-reveal")
        val incomeBounds = find(By.res("swipe_row_budget_ui_income")).visibleBounds
        device.swipe(incomeBounds.right-incomeBounds.width()/5, incomeBounds.centerY(), incomeBounds.left+incomeBounds.width()/5, incomeBounds.centerY(), 35)
        assertTrue(device.wait(Until.hasObject(By.res("swipe_delete_budget_ui_income")), 5000))
        assertTrue("Opening another row closes the previous action", device.wait(Until.gone(By.res("swipe_delete_budget_ui_expense")), 5000))
        swipeExpense()
        assertTrue("Only one delete action is expanded", device.wait(Until.gone(By.res("swipe_delete_budget_ui_income")), 5000))
        find(By.res("swipe_delete_budget_ui_expense")).click()
        assertTrue(device.wait(Until.hasObject(By.text("删除“BudgetUI-Expense”？")), 5000))
        find(By.text("取消")).click()
        assertEquals(2, runBlocking { repository.snapshot().entries.size })
        swipeExpense()
        val amount = find(By.res("ledger_amount_budget_ui_expense")).visibleBounds
        device.click(amount.centerX(), amount.centerY())
        assertTrue("Tapping a revealed bill closes the action", device.wait(Until.gone(By.res("swipe_delete_budget_ui_expense")), 5000))
        swipeExpense()
        val row = find(By.res("swipe_row_budget_ui_expense")).visibleBounds
        device.swipe(row.centerX(), row.centerY(), row.centerX(), row.centerY() - (80 * density).toInt(), 30)
        assertTrue("Vertical browsing closes the action", device.wait(Until.gone(By.res("swipe_delete_budget_ui_expense")), 5000))
        navigate(2); setupBudget("15")
        assertEquals("额度已用完", find(By.res("budget_state"), true).text)
        assertEquals("¥ 0.00", find(By.res("budget_remaining"), true).text)
        checkRingText(); capture("reached")
        setupBudget("10")
        assertEquals("已超支", find(By.res("budget_state"), true).text)
        assertEquals("¥ 5.00", find(By.res("budget_remaining"), true).text)
        checkRingText(); capture("over-budget")
        setupBudget("5")
        assertEquals("¥ 10.00", find(By.res("budget_remaining"), true).text)
        assertEquals("已用 300%", find(By.res("budget_used_percent"), true).text)
        checkRingText(); capture("multiple-laps")
        setupBudget("10")
        launch(); navigate(2)
        assertEquals("¥ 10.00", find(By.res("budget_limit"), true).text)
        assertEquals("已超支", find(By.res("budget_state"), true).text)
        navigate(1); swipeExpense(); find(By.res("swipe_delete_budget_ui_expense")).click()
        assertTrue(device.wait(Until.hasObject(By.text("删除“BudgetUI-Expense”？")), 5000))
        find(By.text("删除")).click()
        assertTrue(device.wait(Until.gone(By.text("BudgetUI-Expense")), 10000))
        assertEquals("budget_ui_income", runBlocking { repository.snapshot().entries.single().id })
        navigate(2)
        assertEquals("¥ 10.00", find(By.res("budget_remaining"), true).text)
        assertEquals("¥ 0.00", find(By.res("budget_spent"), true).text)
        checkRingText(); capture("deleted-budget-refreshed")
    }
    @Test fun extremeAmountsAndRefundsFitTheTwoColumnSummaryAndRing() {
        runBlocking {
            val now = System.currentTimeMillis()
            val entry = LedgerEntry("extreme_budget_expense", "expense", MAX_AMOUNT, "CNY", "food", "预算长金额测试", today(), "", "", "manual", "", "", now, now, 0)
            repository.save(entry)
            repository.save(entry.copy(id = "extreme_budget_refund", kind = "refund", amountMinor = 100, title = "退款测试"))
            repository.save(entry.copy(id = "extreme_budget_income", kind = "income", categoryId = "salary", amountMinor = 32100, title = "收入测试"))
        }
        launch(); navigate(2)
        val expense = find(By.res("stats_expense_amount"), true).visibleBounds
        val income = find(By.res("stats_income_amount"), true).visibleBounds
        assertTrue("Net expense and income stay side by side", expense.right < income.left && kotlin.math.abs(expense.top - income.top) <= 2 * density)
        checkTextFits("stats_expense_amount", formatMoney(MAX_AMOUNT - 100))
        checkTextFits("stats_income_amount", "321.00")
        capture("two-column-extreme")
        setupBudget("0.01")
        assertEquals("¥ ${formatMoney(MAX_AMOUNT - 100)}", find(By.res("budget_spent"), true).text)
        assertEquals("¥ ${formatMoney(MAX_AMOUNT - 101)}", find(By.res("budget_remaining"), true).text)
        checkRingText(); capture("extreme-amount")
    }
}

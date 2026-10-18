package com.yiyu.ledger

import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.yiyu.ledger.backup.BackupDocument
import com.yiyu.ledger.domain.*
import com.yiyu.ledger.ui.LedgerWheelPicker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/** All fixture writes are restricted to disposable development emulators. */
@RunWith(AndroidJUnit4::class)
class LedgerBrowseUiTest {
    companion object {
        @BeforeClass @JvmStatic fun clearCaptures() {
            require(Build.HARDWARE in listOf("ranchu", "goldfish"))
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.filesDir, "ledger-browse-ui").listFiles()?.filter { it.extension in listOf("png", "xml") }?.forEach { it.delete() }
        }
    }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val repository get() = (context.applicationContext as LedgerApplication).repository
    private val day = today()
    private val density get() = context.resources.displayMetrics.density
    private val folder get() = File(context.filesDir, "ledger-browse-ui").apply { mkdirs() }
    private fun entry(id: String, kind: String = "expense", amount: Long = 900, date: String = day, category: String = "food") =
        LedgerEntry(id, kind, amount, "CNY", category, "BROWSE-$id", date, "", "", "manual", "", "", 1000, 1000, 0)

    @Before fun prepare() {
        require(Build.HARDWARE in listOf("ranchu", "goldfish")) { "Synthetic book writes require a development emulator" }
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
        runBlocking {
            val previous = repository.snapshot()
            previous.entries.forEach { repository.delete(it.id) }
            previous.pending.forEach { repository.discardPending(it.id) }
            repository.importBackup(BackupDocument("yiyu-ledger", 3, entries = listOf(
                entry("FOOD"), entry("PRINT", amount = 500, category = "other"), entry("SALARY", "income", 60000, category = "salary"),
                entry("REFUND", "refund", 300), entry("TRANSFER", "transfer", 20000, category = "transfer"), entry("DEBT", "debt", 20000, category = "debt"),
                entry("YESTERDAY", amount = 300, date = shiftDays(day, -1)), entry("DAY-BEFORE", amount = 200, date = shiftDays(day, -2)),
                entry("EARLY-MONTH", amount = 1700, date = day.take(7) + "-01"), entry("HISTORY-FOOD", amount = 2000, date = "2025-08-01"),
                entry("HISTORY-SALARY", "income", 50000, "2025-08-01", "salary"), entry("FUTURE", date = shiftDays(day, 1))
            ), monthlyBudgets = listOf(MonthlyBudget(day.take(7), 20000))))
        }
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        find("nav_1").click(); find("ledger_search")
    }
    private fun find(id: String, scroll: Boolean = false): UiObject2 {
        val control = id == "ledger_search" || id == "ledger_scope" || id == "ledger_date_button" || id.startsWith("ledger_kind_") || id == "ledger_search_button" || id == "ledger_clear_search"
        val inList = control || id.startsWith("ledger_daily_expense_") || id.startsWith("ledger_row_") || id == "ledger_empty_day" || id == "ledger_return"
        val canScroll = scroll || inList
        val minimum = when {
            id == "ledger_return" || id.startsWith("ledger_row_") -> 48
            control && id != "ledger_scope" -> 44
            else -> 10
        }
        repeat(if (canScroll) 45 else 4) {
            try {
                device.waitForIdle()
                device.wait(Until.findObject(By.res(id)), 400)?.let { node ->
                    val box = node.visibleBounds
                    if (box.height() >= minimum * density - 2) {
                        SystemClock.sleep(180)
                        device.findObject(By.res(id))?.let { stable -> if (stable.visibleBounds == box) return stable }
                    }
                }
                if (canScroll) {
                    val viewport = find(if (id.startsWith("stats_")) "stats_page" else "ledger_page").visibleBounds
                    device.swipe(viewport.centerX(), viewport.top + viewport.height() * (if (control) 1 else 4) / 5,
                        viewport.centerX(), viewport.top + viewport.height() * (if (control) 4 else 1) / 5, 35)
                    SystemClock.sleep(160)
                }
            } catch (_: StaleObjectException) { SystemClock.sleep(200) }
        }
        capture("failure")
        error("Missing or clipped UI node: $id")
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync(); device.waitForIdle(); SystemClock.sleep(450)
        device.takeScreenshot(File(folder, "$name.png")); device.dumpWindowHierarchy(File(folder, "$name.xml"))
    }
    private fun picker(): LedgerWheelPicker {
        fun search(view: View): LedgerWheelPicker? {
            if (view is LedgerWheelPicker) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) search(view.getChildAt(i))?.let { return it }
            return null
        }
        var found: LedgerWheelPicker? = null
        val lookup = Runnable { found = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::search) }
        if (Looper.myLooper() == Looper.getMainLooper()) lookup.run() else instrumentation.runOnMainSync(lookup)
        return checkNotNull(found)
    }
    private fun selectDate(date: String) {
        val dateValue = LocalDate.parse(date)
        instrumentation.runOnMainSync { picker().updateDate(dateValue) }
        assertEquals(ledgerWeekday(date), find("ledger_weekday").text)
    }
    private fun openPicker() { find("ledger_date_button").click(); find("ledger_date_dialog"); SystemClock.sleep(500) }
    private fun checkCenteredPicker(name: String) {
        capture(name)
        val card = find("ledger_date_dialog").visibleBounds
        val title = find("ledger_weekday").visibleBounds
        var safe = Rect()
        instrumentation.runOnMainSync {
            val root = WindowInspector.getGlobalWindowViews().first { it.findViewById<View>(R.id.ledger_native_date_picker) != null }
            val insets = root.rootWindowInsets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            safe = Rect(insets.left, insets.top, device.displayWidth - insets.right, device.displayHeight - insets.bottom)
        }
        assertEquals("Date card must stay horizontally centered", safe.exactCenterX(), card.exactCenterX(), 3 * density)
        assertEquals("Date card must stay vertically centered", safe.exactCenterY(), card.exactCenterY(), 3 * density)
        assertTrue("Card must fit safe screen", safe.contains(card))
        assertEquals("Weekday title center", card.exactCenterX(), title.exactCenterX(), 2 * density)
        val close = find("ledger_date_close").visibleBounds
        assertTrue(close.width() >= 44 * density - 2 && close.height() >= 44 * density - 2)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()!!
        // Check painted glyphs directly: cached accessibility text-layout extras
        // may be absent just after a native wheel update or activity recreation.
        var firstPixel = title.right; var lastPixel = title.left
        for (x in title.left until title.right) for (y in title.top until title.bottom) {
            val pixel = bitmap.getPixel(x, y)
            if (android.graphics.Color.red(pixel) < 90 && android.graphics.Color.green(pixel) < 115 && android.graphics.Color.blue(pixel) < 90) {
                firstPixel = minOf(firstPixel, x); lastPixel = maxOf(lastPixel, x)
            }
        }
        assertTrue("Weekday glyphs must be painted", firstPixel < lastPixel)
        assertEquals("Painted weekday must also be centered", card.exactCenterX(), (firstPixel + lastPixel) / 2f, 3 * density)
        for (x in listOf(close.left + (3 * density).toInt(), close.right - (3 * density).toInt()))
            for (y in listOf(close.top + (3 * density).toInt(), close.bottom - (3 * density).toInt()))
                assertEquals("Close icon must not have a colored circular background", 0xFFF5F5EF.toInt(), bitmap.getPixel(x, y))
        val wheel = find("ledger_year_wheel").visibleBounds
        val rowHeight = wheel.height() / 5f
        assertTrue("Five date rows must stay compact", rowHeight in (36 * density - 2)..(48 * density + 2))
        var year = 0
        instrumentation.runOnMainSync { year = picker().date.year }
        (-2..2).forEach { delta ->
            val row = checkNotNull(device.wait(Until.findObject(By.res("ledger_year_value_${year + delta}")), 1000))
            assertEquals(delta == 0, row.isSelected || row.isChecked)
        }
        // Android can cache accessibility item bounds across a short-window
        // remeasure. Check the painted date rows, as we do for the weekday.
        val painted = mutableListOf<Rect>()
        for (y in wheel.top until wheel.bottom) {
            var count = 0
            for (x in wheel.left until wheel.right) {
                val pixel = bitmap.getPixel(x, y)
                if (android.graphics.Color.red(pixel) < 230 && android.graphics.Color.green(pixel) < 230) count++
            }
            if (count > 3) {
                if (painted.isEmpty() || y - painted.last().bottom > 2)
                    painted += Rect(wheel.left, y, wheel.right, y + 1)
                else painted.last().bottom = y + 1
            }
        }
        assertEquals("Exactly five date rows must be painted", 5, painted.size)
        painted.forEachIndexed { index, row ->
            assertEquals("Painted rows must stay evenly spaced and centered",
                wheel.top + (index + .5f) * rowHeight, row.exactCenterY(), 4 * density)
        }
        fun darkestGreen(rect: Rect): Int {
            var darkest = 255
            for (x in rect.left until rect.right) for (y in rect.top until rect.bottom)
                darkest = minOf(darkest, android.graphics.Color.green(bitmap.getPixel(x, y)))
            return darkest
        }
        assertTrue("Top date row must fade toward the edge", darkestGreen(painted[0]) > darkestGreen(painted[1]) + 20)
        assertTrue("Bottom date row must fade toward the edge", darkestGreen(painted[4]) > darkestGreen(painted[3]) + 20)
        bitmap.recycle()
        val cancel = find("ledger_date_cancel").visibleBounds; val accept = find("ledger_date_accept").visibleBounds
        assertEquals(cancel.top, accept.top); assertEquals(cancel.bottom, accept.bottom)
        assertTrue(cancel.height() >= 48 * density - 2 && accept.height() >= 48 * density - 2)
    }
    private fun search(query: String) {
        val field = find("ledger_search"); field.click(); field.text = query
        find("ledger_search_button").click(); device.waitForIdle(); SystemClock.sleep(400)
    }
    private fun awaitPickerDate(expected: String) {
        repeat(40) {
            var actual = ""
            instrumentation.runOnMainSync { actual = picker().date.toString() }
            if (actual == expected) { device.waitForIdle(); return }
            SystemClock.sleep(75)
        }
        assertEquals(expected, picker().date.toString())
    }
    private fun checkReturnAfter(id: String) {
        val row = find("ledger_row_$id", true).visibleBounds
        val button = find("ledger_return", true).visibleBounds
        val refreshed = device.findObject(By.res("ledger_row_$id"))?.visibleBounds ?: row
        assertTrue("Return belongs after the last bill", button.top >= refreshed.bottom)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()!!
        assertEquals("Return uses the save-button forest color", 0xFF234236.toInt(), bitmap.getPixel(button.centerX(), button.top + (5 * density).toInt()))
        bitmap.recycle()
    }

    @Test fun calendarSearchAndReturnMatchHarmony() {
        assertTrue(find("ledger_scope").text.startsWith("最近三天"))
        assertEquals("支出：14.00元", find("ledger_daily_expense_$day").text)
        assertFalse(device.hasObject(By.res("ledger_return")))
        capture("recent-days")
        find("ledger_kind_income").click()
        assertEquals("支出：14.00元", find("ledger_daily_expense_$day").text)
        find("ledger_row_SALARY")
        openPicker(); checkCenteredPicker("centered-picker")
        val oldWeekday = find("ledger_weekday").text
        val bounds = find("ledger_day_wheel").visibleBounds
        device.swipe(bounds.centerX(), bounds.centerY() + bounds.height() / 4, bounds.centerX(), bounds.centerY() - bounds.height() / 4, 60)
        device.waitForIdle(); SystemClock.sleep(350)
        var selected = ""
        instrumentation.runOnMainSync { selected = picker().date.toString() }
        assertNotEquals("Wheel gesture must change the selected day", day, selected)
        assertEquals(ledgerWeekday(selected), find("ledger_weekday").text)
        selectDate("2024-03-31")
        find("ledger_month_value_2").click(); awaitPickerDate("2024-02-29")
        find("ledger_year_value_2025").click(); awaitPickerDate("2025-02-28")
        find("ledger_day_value_28").let { assertTrue(it.isSelected || it.isChecked) }
        checkCenteredPicker("picker-month-boundary")
        find("ledger_date_close").click()
        assertTrue(find("ledger_scope").text.startsWith("最近三天"))
        assertTrue(find("ledger_kind_income").isChecked)
        openPicker(); selectDate("2025-08-01"); checkCenteredPicker("picker-cross-year")
        find("ledger_date_accept").click()
        assertEquals("查看：2025-08-01", find("ledger_scope").text)
        assertEquals("支出：20.00元", find("ledger_daily_expense_2025-08-01").text)
        find("ledger_row_HISTORY-SALARY"); checkReturnAfter("HISTORY-SALARY"); capture("selected-day-return")
        find("ledger_return").click()
        assertTrue(find("ledger_scope").text.startsWith("最近三天")); assertTrue(find("ledger_kind_income").isChecked)
        search("BROWSE-HISTORY")
        assertEquals("全部日期 · 搜索结果", find("ledger_scope").text)
        find("ledger_row_HISTORY-SALARY"); checkReturnAfter("HISTORY-SALARY"); capture("history-search-return")
        find("ledger_return").click()
        assertEquals("", find("ledger_search").text); assertTrue(find("ledger_kind_income").isChecked)
        openPicker(); selectDate("2024-02-29"); checkCenteredPicker("picker-leap-day")
        find("ledger_date_accept").click()
        assertEquals("支出：0.00元", find("ledger_daily_expense_2024-02-29").text)
        assertEquals("当天没有收入账单", find("ledger_empty_day").text)
        find("ledger_return", true); capture("empty-day-return")
        find("ledger_return").click()
        find("ledger_kind_expense").click(); find("ledger_row_FOOD").let { row ->
            val box = row.visibleBounds
            device.swipe(box.right - 4, box.centerY(), box.left + box.width() / 4, box.centerY(), 25)
        }
        find("swipe_delete_FOOD").click()
        assertTrue(device.wait(Until.hasObject(By.text("删除")), 5000)); device.findObject(By.text("删除")).click()
        assertTrue(device.wait(Until.gone(By.res("ledger_row_FOOD")), 8000))
        assertEquals("支出：5.00元", find("ledger_daily_expense_$day").text)
        capture("deleted-daily-total")
        find("nav_2").click(); find("stats_expense_amount", true).click()
        assertTrue(find("ledger_scope").text.startsWith("范围："))
        find("ledger_row_EARLY-MONTH", true)
        find("ledger_return", true).click()
        assertTrue(find("ledger_kind_expense").isChecked); assertTrue(find("ledger_scope").text.startsWith("最近三天"))
        capture("statistics-return")
        assertNotEquals("", oldWeekday)
    }

    @Test fun pagedHistoryAndReservedIdsRemainBrowsable() {
        val profile = InstrumentationRegistry.getArguments().getString("profile", "standard")
        if (profile != "standard") return // Pagination/rotation are independent of the width matrix.
        val reserved = listOf("controls", "return", "day_2025-07-02").map { entry(it, amount = 1, date = "2025-07-02").copy(title = "BROWSE-RESERVED-$it") }
        runBlocking { repository.importBackup(BackupDocument("yiyu-ledger", 3, entries = reserved)) }
        search("BROWSE-RESERVED")
        reserved.forEach { find("ledger_row_${it.id}", true) }
        assertEquals("支出：0.03元", find("ledger_daily_expense_2025-07-02").text)
        capture("reserved-row-keys")
        find("ledger_return", true).click()
        val many = (1..82).map { entry("PAGE-$it", amount = 1, date = "2025-07-01").copy(createdAt = it.toLong()) }
        runBlocking { repository.importBackup(BackupDocument("yiyu-ledger", 3, entries = many)) }
        search("BROWSE-PAGE")
        assertEquals("支出：0.82元", find("ledger_daily_expense_2025-07-01").text)
        assertFalse(device.hasObject(By.res("ledger_return")))
        find("ledger_row_PAGE-1", true); checkReturnAfter("PAGE-1"); capture("paged-all-results-return")
        find("ledger_return").click()
    }

    @Test fun selectedDateSurvivesPickerReopenAndRotation() {
        device.setOrientationNatural(); device.waitForIdle(); SystemClock.sleep(500)
        openPicker(); selectDate("2024-02-29"); capture("picker-before-rotation")
        assertEquals("2024-02-29", picker().date.toString())
        find("ledger_date_accept").click()
        openPicker(); capture("picker-reopened")
        assertEquals("2024-02-29", picker().date.toString())
        find("ledger_date_close").click()
        device.setOrientationLeft(); device.waitForIdle(); SystemClock.sleep(800)
        assertEquals("查看：2024-02-29", find("ledger_scope").text)
        openPicker(); checkCenteredPicker("rotated-centered-picker")
        assertEquals("2024-02-29", picker().date.toString())
        assertEquals("星期四", find("ledger_weekday").text)
        selectDate("2027-01-01"); find("ledger_date_cancel").click()
        assertEquals("查看：2024-02-29", find("ledger_scope").text)
        device.setOrientationNatural(); device.waitForIdle(); SystemClock.sleep(800)
        assertEquals("查看：2024-02-29", find("ledger_scope").text)
        find("ledger_return", true).click()
    }
}

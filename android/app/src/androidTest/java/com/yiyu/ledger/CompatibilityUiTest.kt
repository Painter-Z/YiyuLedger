package com.yiyu.ledger

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.graphics.Rect
import android.graphics.RectF
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.*
import com.yiyu.ledger.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Runs only on disposable development emulators; all entries here are synthetic. */
@RunWith(AndroidJUnit4::class)
class CompatibilityUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val repository get() = (context.applicationContext as LedgerApplication).repository
    private val profile = InstrumentationRegistry.getArguments().getString("profile", "standard")
    private val expectedRotation = if (profile.startsWith("landscape")) 1 else 0
    private val directory get() = File(context.filesDir, "compatibility/$profile").apply { mkdirs() }
    @get:Rule val screenshotsOnFailure = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) { capture("failure") }
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        // Accessibility may expose the next page before its first frame is drawn.
        SystemClock.sleep(500)
        device.waitForIdle()
        if (name != "failure" && name != "persistence-failure") {
            assertEquals("Capture must use the requested screen orientation", expectedRotation, device.displayRotation)
            assertEquals("Display dimensions must agree with orientation", expectedRotation == 1, device.displayWidth > device.displayHeight)
        }
        device.takeScreenshot(File(directory, "$name.png"))
        device.dumpWindowHierarchy(File(directory, "$name.xml"))
    }
    private fun find(selector: BySelector, scroll: Boolean = false): UiObject2 {
        repeat(if (scroll) 16 else 1) {
            device.waitForIdle()
            val view = device.findObject(By.res("editor_scroll"))
                ?: device.findObject(By.res("ledger_page"))
                ?: device.findObject(By.res("home_page"))
                ?: device.findObject(By.res("stats_page"))
                ?: device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() }
            var upwards = false
            device.wait(Until.findObject(selector), 700)?.let { candidate ->
                val viewport = view?.visibleBounds
                val box = candidate.visibleBounds
                if (!scroll) return candidate
                if (viewport == null || (box.height() > 0 && box.centerY() in (viewport.top + 4)..(viewport.bottom - 4))) {
                    // A fast swipe may still be flinging after accessibility is
                    // idle. Wait for stable coordinates before clicking.
                    SystemClock.sleep(250)
                    val settled = device.findObject(selector)
                    if (settled != null && settled.visibleBounds == box) return settled
                    return@repeat
                }
                upwards = box.height() > 0 && box.top < viewport.top
            }
            if (scroll) view?.let {
                    val bounds = it.visibleBounds
                    // Scroll from the padding gutter. A drag that starts on the
                    // voice button intentionally belongs to recording/cancel.
                    val x = bounds.left + (8 * context.resources.displayMetrics.density).toInt()
                    val top = bounds.top + bounds.height() / 7
                    val bottom = bounds.bottom - bounds.height() / 7
                    device.swipe(x, if (upwards) top else bottom, x, if (upwards) bottom else top, 30)
                }
        }
        capture("failure")
        error("Missing UI node: $selector")
    }
    private fun navigate(index: Int) {
        find(By.res("nav_$index")).click()
        val page = listOf("home_page", "ledger_page", "stats_page")[index]
        assertTrue("Navigation must finish before inspecting $page", device.wait(Until.hasObject(By.res(page)), 10000))
        device.waitForIdle()
    }
    private fun accessibilityNode(id: String, node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.viewIdResourceName == id) return node
        for (index in 0 until node.childCount) accessibilityNode(id, node.getChild(index))?.let { return it }
        return null
    }
    @Suppress("DEPRECATION")
    private fun checkRenderedAmount(id: String, amount: Long) {
        find(By.res(id), scroll = true)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500)
        val expected = formatMoney(amount)
        val key = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
        var node = accessibilityNode(id) ?: error("Missing amount accessibility node: $id")
        var glyphs: List<RectF?>? = null
        val deadline = SystemClock.uptimeMillis() + 4000
        while (SystemClock.uptimeMillis() < deadline && glyphs == null) {
            node = accessibilityNode(id) ?: error("Missing amount accessibility node: $id")
            val text = node.text?.toString().orEmpty()
            val start = text.indexOf(expected)
            assertTrue("Amount semantics must contain the full value: $text / $expected", start >= 0)
            val arguments = Bundle().apply {
                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, start)
                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, expected.length)
            }
            assertTrue("The renderer must supply character positions for $id", node.refreshWithExtraData(key, arguments))
            glyphs = node.extras.getParcelableArray(key)?.map { it as? RectF }
            if (glyphs == null) SystemClock.sleep(300)
        }
        val locations = glyphs ?: error("No rendered character positions for $id")
        val text = node.text?.toString().orEmpty()
        val bounds = Rect().also(node::getBoundsInScreen)
        File(directory, "$id-bounds.txt").writeText("amount=$expected\ntext=$text\nnode=$bounds\nglyphs=$locations\n")
        assertEquals("Every amount character needs a rendered position", expected.length, locations.size)
        locations.forEachIndexed { index, glyph ->
            assertNotNull("Amount character $index (${expected[index]}) must be visible", glyph)
            assertTrue("Amount character $index is clipped: $glyph / $bounds", glyph!!.left >= bounds.left - 2 && glyph.right <= bounds.right + 2)
        }
    }
    private fun checkSaveAboveKeyboard() {
        val keyboardPackages = listOf("com.google.android.inputmethod.latin", "com.android.inputmethod.latin")
        val ready = keyboardPackages.any { device.wait(Until.hasObject(By.pkg(it)), 8000) }
        assertTrue("The soft keyboard must actually be visible for this check", ready)
        device.waitForIdle()
        val save = find(By.res("editor_save")).visibleBounds
        val density = context.resources.displayMetrics.density
        assertTrue("Save button must have a full touch target: $save", save.height() >= (48 * density - 2).toInt())
        assertTrue("Save button stays inside the display: $save", save.left >= 0 && save.right <= device.displayWidth && save.bottom <= device.displayHeight)
        // Read the outer IME content roots from the hierarchy. Some Gboard nodes
        // include a shadow above the clipped content (23px on the zoom profile).
        // That shadow is not the visible keyboard's top edge.
        val dump = ByteArrayOutputStream().also { device.dumpWindowHierarchy(it) }
        val roots = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(dump.toByteArray().inputStream()).documentElement.childNodes
        val keyboard = (0 until roots.length).mapNotNull { roots.item(it) as? Element }
            .filter { it.getAttribute("package") in keyboardPackages }
            .mapNotNull { element ->
                Regex("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]").matchEntire(element.getAttribute("bounds"))?.groupValues?.let { parts ->
                    android.graphics.Rect(parts[1].toInt(), parts[2].toInt(), parts[3].toInt(), parts[4].toInt())
                }
            }.filter { it.height() > 0 }
        assertTrue("The soft keyboard must actually be visible for this check", keyboard.isNotEmpty())
        val top = keyboard.minOf { it.top }
        File(directory, "keyboard-bounds.txt").writeText("save=$save\nkeyboardContent=$keyboard\n")
        assertTrue("Save cannot overlap the keyboard: button=$save keyboardTop=$top", save.bottom <= top + 2)
        val title = android.graphics.Rect(find(By.res("editor_title")).visibleBounds)
        assertTrue(title.intersect(find(By.res("editor_scroll")).visibleBounds))
        assertTrue("Keep a readable input area while the keyboard is open: $title", title.height() >= (32 * density).toInt())
    }
    private fun model(): LedgerViewModel {
        lateinit var value: LedgerViewModel
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().single()
            value = ViewModelProvider(activity)[LedgerViewModel::class.java]
        }
        return value
    }
    private fun waitForBook(predicate: (List<LedgerEntry>) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            if (runBlocking { predicate(repository.snapshot().entries) }) return
            SystemClock.sleep(100)
        }
        capture("persistence-failure")
        fail("Ledger did not reach the expected persisted state")
    }

    @Test fun screensKeyboardCategoriesAndPersistence() {
        require(Build.HARDWARE in listOf("ranchu", "goldfish")) { "Compatibility tests may only run on development emulators" }
        directory.listFiles()?.forEach { it.delete() }
        if (expectedRotation == 1) device.setOrientationLeft() else device.setOrientationNatural()
        val rotationDeadline = SystemClock.uptimeMillis() + 10000
        while (device.displayRotation != expectedRotation && SystemClock.uptimeMillis() < rotationDeadline) SystemClock.sleep(100)
        assertEquals("Emulator must actually rotate before testing", expectedRotation, device.displayRotation)
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
        val now = System.currentTimeMillis()
        runBlocking {
            val old = repository.snapshot()
            old.entries.forEach { repository.delete(it.id) }
            old.pending.forEach { repository.discardPending(it.id) }
            old.categories.forEach { repository.deleteCategory(it.id) }
            repository.addCategory(CustomCategory("custom_compat", "家庭宠物食品健康用品", "expense"))
            val entry = LedgerEntry("compat_extreme", "expense", MAX_AMOUNT, "CNY", "custom_compat",
                "家庭宠物食品与日常健康用品的长名称适配测试", today(), "", "大金额、长名称及自定义分类测试", "manual", "", "", now, now, 0)
            repository.save(entry)
            repository.save(entry.copy(id = "compat_food", amountMinor = 900, categoryId = "food", title = "米线", createdAt = now - 1))
            repository.save(entry.copy(id = "compat_income", kind = "income", amountMinor = 32100, categoryId = "salary", title = "测试收入", createdAt = now - 2))
        }
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.res("nav_0")), 15000))
        checkRenderedAmount("home_balance_amount", 32100 - MAX_AMOUNT - 900)
        capture("home")
        find(By.res("new_manual"), scroll = true).click()
        assertTrue(device.wait(Until.hasObject(By.res("editor_save")), 5000))
        capture("editor")
        find(By.res("editor_amount"), scroll = true).apply { click(); text = "9" }
        find(By.res("editor_title"), scroll = true).apply { click(); text = "UITest-$profile" }
        find(By.res("editor_title")).click()
        device.waitForIdle()
        assertEquals("9", model().editor.value!!.amount)
        assertEquals("UITest-$profile", model().editor.value!!.entry.title)
        checkSaveAboveKeyboard()
        capture("editor-keyboard")
        device.pressBack()
        find(By.res("editor_categories_more"), scroll = true).click()
        find(By.text("自定义"), scroll = true).click()
        assertTrue(device.wait(Until.hasObject(By.textStartsWith("添加支出分类")), 5000))
        find(By.clazz("android.widget.EditText")).text = "适配测试分类"
        find(By.text("添加")).click()
        assertTrue(device.wait(Until.gone(By.textStartsWith("添加支出分类")), 10000))
        if (listOf("com.google.android.inputmethod.latin", "com.android.inputmethod.latin").any { device.hasObject(By.pkg(it)) }) device.pressBack()
        assertNotNull("Category creation must keep the editor open", model().editor.value)
        capture("editor-custom-category")
        find(By.res("editor_save")).click()
        assertTrue(device.wait(Until.gone(By.res("editor_panel")), 10000))
        waitForBook { it.any { row -> row.title == "UITest-$profile" && row.amountMinor == 900L } }
        navigate(1)
        find(By.text("UITest-$profile"), scroll = true)
        capture("ledger")
        // Inspect the large amount row as well as the normal saved row.
        val largeTitle = find(By.res("ledger_title_compat_extreme"), scroll = true).visibleBounds
        assertTrue("The long bill name must retain readable width", largeTitle.width() > (80 * context.resources.displayMetrics.density).toInt())
        navigate(2)
        checkRenderedAmount("stats_expense_amount", MAX_AMOUNT + 1800)
        checkRenderedAmount("stats_income_amount", 32100)
        capture("statistics-month")
        listOf("week", "year").forEach { mode ->
            find(By.res("stats_mode_$mode"), scroll = true).click()
            assertTrue(device.wait(Until.hasObject(By.res("stats_mode_$mode").checked(true)), 5000))
            capture("statistics-$mode")
            val period = find(By.res("stats_period")).visibleBounds
            assertTrue("Cycle label must stay between its navigation buttons", period.left > 0 && period.right < device.displayWidth)
        }
        find(By.res("stats_mode_month"), scroll = true).click()
        assertTrue(device.wait(Until.hasObject(By.res("stats_mode_month").checked(true)), 5000))
        find(By.res("stats_trend"), scroll = true)
        capture("statistics-chart")
        navigate(1)
        find(By.text("UITest-$profile"), scroll = true).click()
        assertTrue(device.wait(Until.hasObject(By.res("editor_panel")), 5000))
        find(By.res("editor_title"), scroll = true).text = "UITest-Edited-$profile"
        find(By.res("editor_save")).click()
        assertTrue(device.wait(Until.gone(By.res("editor_panel")), 10000))
        waitForBook { it.any { row -> row.title == "UITest-Edited-$profile" } && it.none { row -> row.title == "UITest-$profile" } }
        find(By.text("UITest-Edited-$profile"), scroll = true).click()
        find(By.desc("删除账单")).click()
        find(By.text("删除")).click()
        assertTrue(device.wait(Until.gone(By.res("editor_panel")), 10000))
        waitForBook { it.none { row -> row.title.startsWith("UITest") } }
        navigate(0)
        capture("home-after-delete")
    }
}

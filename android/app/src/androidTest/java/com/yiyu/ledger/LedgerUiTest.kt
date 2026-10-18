package com.yiyu.ledger

import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import androidx.lifecycle.ViewModelProvider
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage

@RunWith(AndroidJUnit4::class)
class LedgerUiTest {
    @Test fun manualSaveEditAndDeleteRefreshesLedger() {
        require(Build.HARDWARE in listOf("ranchu", "goldfish")) { "UI tests may only run on development emulators" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
        instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("手动记账")), 10000))
        device.findObject(By.text("手动记账")).click()
        assertTrue(device.wait(Until.hasObject(By.text("确认这一笔")), 5000))
        var fields = device.findObjects(By.clazz("android.widget.EditText"))
        assertTrue(fields.size >= 2)
        fields[0].click(); device.waitForIdle(); device.executeShellCommand("input text 9")
        fields = device.findObjects(By.clazz("android.widget.EditText"))
        fields[1].click(); device.waitForIdle(); device.executeShellCommand("input text UITest-Rice")
        device.waitForIdle()
        device.takeScreenshot(File(instrumentation.targetContext.filesDir, "ui-before-save.png"))
        device.dumpWindowHierarchy(File(instrumentation.targetContext.filesDir, "ui-before-save.xml"))
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().single()
            val editor = ViewModelProvider(activity)[LedgerViewModel::class.java].editor.value!!
            assertEquals("9", editor.amount)
            assertEquals("UITest-Rice", editor.entry.title)
        }
        val save = device.findObject(By.text("确认保存"))
        assertNotNull("Save remains on screen while editing", save)
        save.click()
        val saved = device.wait(Until.gone(By.text("确认这一笔")), 10000)
        if (!saved) {
            device.takeScreenshot(File(instrumentation.targetContext.filesDir, "ui-save-failure.png"))
            device.dumpWindowHierarchy(File(instrumentation.targetContext.filesDir, "ui-save-failure.xml"))
        }
        assertTrue("Save must dismiss the editor", saved)
        device.findObject(By.res("nav_1")).click()
        assertTrue(device.wait(Until.hasObject(By.text("UITest-Rice")), 5000))
        device.findObject(By.text("UITest-Rice")).click()
        assertTrue(device.wait(Until.hasObject(By.desc("删除账单")), 5000))
        device.findObject(By.desc("删除账单")).click()
        assertTrue(device.wait(Until.hasObject(By.text("删除")), 5000))
        device.findObject(By.text("删除")).click()
        assertTrue(device.wait(Until.gone(By.text("UITest-Rice")), 10000))
        device.findObject(By.res("nav_2")).click()
        assertTrue(device.wait(Until.hasObject(By.res("monthly_budget_card")), 5000))
    }
}

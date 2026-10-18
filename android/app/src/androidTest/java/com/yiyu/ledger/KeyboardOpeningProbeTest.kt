package com.yiyu.ledger

import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inspector.WindowInspector
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** The same test APK can measure older APKs: do not reference the app's models or activity. */
@RunWith(AndroidJUnit4::class)
class KeyboardOpeningProbeTest {
    @Test fun repeatedAmountKeyboardOpening() {
        require(Build.HARDWARE in listOf("ranchu", "goldfish") && Build.VERSION.SDK_INT >= 29) {
            "Performance probes may only run on disposable development emulators"
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val output = context.filesDir
        val device = UiDevice.getInstance(instrumentation)
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        assertTrue(device.wait(Until.hasObject(By.text("手动记账")), 15000))
        val worker = HandlerThread("keyboard-frame-metrics").apply { start() }
        val handler = Handler(worker.looper)
        val trials = JSONArray()
        try {
            repeat(8) { index ->
                device.findObject(By.text("手动记账")).click()
                assertTrue(device.wait(Until.hasObject(By.text("确认这一笔")), 5000))
                SystemClock.sleep(300)
                var window: Window? = null
                instrumentation.runOnMainSync {
                    window = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::findDialogWindow)
                }
                val dialog = checkNotNull(window) { "No Compose dialog window" }
                val collecting = AtomicBoolean(false)
                val frames = CopyOnWriteArrayList<JSONObject>()
                val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
                    if (collecting.get()) frames.add(JSONObject().apply {
                        put("totalMs", metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1000000.0)
                        put("layoutMs", metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) / 1000000.0)
                        put("drawMs", metrics.getMetric(FrameMetrics.DRAW_DURATION) / 1000000.0)
                        put("droppedReports", dropped)
                    })
                }
                instrumentation.runOnMainSync { dialog.addOnFrameMetricsAvailableListener(listener, handler) }
                val field = device.findObject(By.res("editor_amount"))
                    ?: device.findObjects(By.clazz("android.widget.EditText")).first()
                val bounds = field.visibleBounds
                collecting.set(true)
                val started = SystemClock.uptimeMillis()
                device.click(bounds.centerX(), bounds.centerY())
                var shown: Long? = null
                val timeout = started + 5000
                while (SystemClock.uptimeMillis() < timeout) {
                    if (imeVisible()) { shown = SystemClock.uptimeMillis(); break }
                    SystemClock.sleep(10)
                }
                assertTrue("Numeric IME must open, trial $index", shown != null)
                SystemClock.sleep(800)
                collecting.set(false)
                instrumentation.runOnMainSync { dialog.removeOnFrameMetricsAvailableListener(listener) }
                val total = frames.map { it.getDouble("totalMs") }.sorted()
                val layout = frames.map { it.getDouble("layoutMs") }.sorted()
                trials.put(JSONObject().apply {
                    put("trial", index)
                    put("imeWindowAppearedMs", shown!! - started)
                    put("frameCount", total.size)
                    put("p95FrameMs", percentile(total, .95))
                    put("maxFrameMs", total.lastOrNull() ?: 0.0)
                    put("p95LayoutMs", percentile(layout, .95))
                    put("framesOver16_67Ms", total.count { it > 16.67 })
                    put("frames", JSONArray(frames.toList()))
                })
                if (index == 0) {
                    device.takeScreenshot(File(output, "keyboard-probe.png"))
                    device.dumpWindowHierarchy(File(output, "keyboard-probe.xml"))
                }
                device.pressBack()
                val hideDeadline = SystemClock.uptimeMillis() + 5000
                while (imeVisible() && SystemClock.uptimeMillis() < hideDeadline) SystemClock.sleep(20)
                SystemClock.sleep(300)
                device.findObject(By.desc("关闭确认页面")).click()
                assertTrue(device.wait(Until.gone(By.text("确认这一笔")), 5000))
            }
        } finally {
            worker.quitSafely()
            worker.join(2000)
            @Suppress("DEPRECATION")
            val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
            val result = JSONObject().apply {
                put("version", version)
                put("sdk", Build.VERSION.SDK_INT)
                put("hardware", Build.HARDWARE)
                put("method", "IME accessibility window visibility and dialog native FrameMetrics; 8 empty-book opens; emulator comparison only")
                put("trials", trials)
            }
            File(output, "keyboard-probe.json").writeText(result.toString(2))
        }
    }

    private fun findDialogWindow(view: View): Window? {
        if (view is DialogWindowProvider) return view.window
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            findDialogWindow(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun imeVisible(): Boolean = InstrumentationRegistry.getInstrumentation().uiAutomation.windows.any {
        val bounds = Rect()
        it.getBoundsInScreen(bounds)
        it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && bounds.height() > 100
    }

    private fun percentile(values: List<Double>, fraction: Double): Double =
        if (values.isEmpty()) 0.0 else values[((values.size - 1) * fraction).toInt()]
}

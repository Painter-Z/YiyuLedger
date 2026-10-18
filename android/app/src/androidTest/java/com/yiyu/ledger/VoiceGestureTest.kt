package com.yiyu.ledger

import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.yiyu.ledger.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class VoiceGestureTest {
    @Test fun slideUpCancelsWithoutMovingParentScroll() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        var voice by mutableStateOf(VoiceUi())
        val canceled = AtomicBoolean(false)
        lateinit var scroll: ScrollState
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { YiyuTheme {
                scroll = rememberScrollState()
                Column(Modifier.fillMaxSize().verticalScroll(scroll, enabled = !voice.capturing)) {
                    Spacer(Modifier.height(160.dp))
                    VoiceButton(voice, !voice.active, onStart = { voice = VoiceUi("recording") }, onEnd = { canceled.set(it); voice = VoiceUi() })
                    Spacer(Modifier.height(1400.dp))
                }
            } } }
            assertTrue(device.wait(Until.hasObject(By.desc("按住说话，上滑取消，最多30秒")), 10000))
            val bounds = device.findObject(By.desc("按住说话，上滑取消，最多30秒")).visibleBounds
            val x = bounds.exactCenterX(); val y = bounds.exactCenterY()
            val upY = y - instrumentation.targetContext.resources.displayMetrics.density * 110
            val downTime = SystemClock.uptimeMillis()
            fun inject(action: Int, targetY: Float) {
                MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, targetY, 0).also {
                    instrumentation.uiAutomation.injectInputEvent(it, true); it.recycle()
                }
            }
            inject(MotionEvent.ACTION_DOWN, y); SystemClock.sleep(150)
            inject(MotionEvent.ACTION_MOVE, upY); SystemClock.sleep(150)
            inject(MotionEvent.ACTION_UP, upY)
            device.waitForIdle()
            instrumentation.runOnMainSync { assertTrue(canceled.get()); assertEquals(0, scroll.value); assertFalse(voice.active) }
        }
    }
}

package com.yiyu.ledger

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yiyu.ledger.audio.VoiceRecorder
import com.yiyu.ledger.cloud.DirectCloudClient
import com.yiyu.ledger.domain.today
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in network probe, emulator only. No credential is compiled into either APK. */
@RunWith(AndroidJUnit4::class)
class CloudConnectivityTest {
    @Test fun personalKeyReachesTextAndVoiceProviders() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LedgerApplication>()
        val keyFile = File(app.filesDir, "cloud-probe-key")
        val audioFile = File(app.filesDir, "cloud-probe.wav")
        assumeTrue("Opt-in emulator probe not provisioned", keyFile.exists() && audioFile.exists())
        val key = keyFile.readText().trim()
        keyFile.delete()
        try {
            val cloud = DirectCloudClient(key, app.cloudPrompt)
            val text = cloud.parse("中午吃了辣椒炒肉盖饭，花了15元", today(), emptyList())
            assertEquals(1, text.drafts.size)
            assertEquals(1500L, text.drafts.single().amount_minor)
            assertEquals("food", text.drafts.single().category_id)
            val session = cloud.startVoice(today(), emptyList()) {}
            try {
                VoiceRecorder.replay(audioFile, session::push)
                val transcript = session.finish()
                assertTrue("ASR must return text", transcript.isNotBlank())
                val voice = cloud.parse(transcript, today(), emptyList())
                assertEquals(1, voice.drafts.size)
                assertEquals(1500L, voice.drafts.single().amount_minor)
                assertEquals("food", voice.drafts.single().category_id)
            } finally { session.cancel() }
        } finally { keyFile.delete(); audioFile.delete() }
    }
}

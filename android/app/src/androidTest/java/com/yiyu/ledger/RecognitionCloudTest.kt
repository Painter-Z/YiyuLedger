package com.yiyu.ledger

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yiyu.ledger.audio.VoiceRecorder
import com.yiyu.ledger.backup.ledgerJson
import com.yiyu.ledger.cloud.DirectCloudClient
import com.yiyu.ledger.domain.RecognitionResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Explicitly provisioned network check. Uses synthetic examples and never writes bills. */
@RunWith(AndroidJUnit4::class)
class RecognitionCloudTest {
    private data class Case(val name: String, val text: String, val expected: List<Pair<String, Long?>>)

    @Test fun realAndroidClientHandlesTheHarmony070RegressionExamples() = runBlocking {
        assumeTrue("Emulator-only probe", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("gphone"))
        val app = ApplicationProvider.getApplicationContext<LedgerApplication>()
        val keyFile = File(app.filesDir, "recognition-probe-key")
        val multiple = File(app.filesDir, "recognition-probe-multiple.wav")
        val cents = File(app.filesDir, "recognition-probe-cents.wav")
        assumeTrue("Opt-in probe not provisioned", keyFile.exists() && multiple.exists() && cents.exists())
        val key = keyFile.readText().trim()
        keyFile.delete()
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS).addInterceptor { chain ->
                if (chain.request().method == "POST") calls.incrementAndGet()
                chain.proceed(chain.request())
            }.build()
        val cloud = DirectCloudClient(key, app.cloudPrompt, client)
        val reference = "2026-10-18"
        val cases = listOf(
            Case("reported-meal-and-porridge", "今天我吃了保障餐，花了八块。又喝了一碗粥，花了两块五", listOf("保障餐" to 800L, "粥" to 250L)),
            Case("colloquial-cents", "中午吃了一碗牛肉面，花了十一块八毛九", listOf("牛肉面" to 1189L)),
            Case("same-name-real-purchases", "早上吃保障餐八块，晚上又吃保障餐八块", listOf("保障餐" to 800L, "保障餐" to 800L)),
            Case("same-price-different-items", "喝了一碗粥两块五，又买了豆浆两块五", listOf("粥" to 250L, "豆浆" to 250L)),
            Case("oral-correction", "保障餐花了八块，不对应该是七块五", listOf("保障餐" to 750L)),
            Case("summary-not-duplicated", "牛肉面十五元，地铁两元，总共十七元", listOf("牛肉面" to 1500L, "地铁" to 200L)),
            Case("fractional-only", "买了一个包子花了八毛九", listOf("包子" to 89L)),
            Case("combined-total-not-split", "保障餐和粥一共花了十块五", listOf("保障餐和粥" to 1050L)),
            Case("missing-price-not-guessed", "帮我记一笔，今天买了一碗粥，价格忘了", listOf("粥" to null)),
            Case("non-ledger-content", "今天天气不错，下午准备去散步", emptyList())
        )
        val rows = mutableListOf<JsonObject>()
        suspend fun checkCase(case: Case, audio: File? = null) {
            calls.set(0)
            var transcript = case.text
            var observed: RecognitionResult? = null
            var passed = false
            var errorType = ""
            try {
                if (audio != null) {
                    val session = cloud.startVoice(reference, emptyList()) {}
                    try {
                        VoiceRecorder.replay(audio, session::push)
                        transcript = session.finish()
                    } finally { session.cancel() }
                }
                observed = cloud.parse(transcript, reference, emptyList())
                passed = observed.drafts.map { it.title to it.amount_minor } == case.expected &&
                    observed.drafts.all { it.kind == "expense" && !it.review_required } && calls.get() in 1..2
            } catch (error: Exception) { errorType = error.javaClass.simpleName }
            rows += buildJsonObject {
                put("name", case.name + if (audio != null) "-speech" else "")
                put("passed", passed); put("calls", calls.get()); put("transcript", transcript)
                put("speech", audio != null)
                if (errorType.isNotEmpty()) put("error_type", errorType)
                if (observed != null) put("result", ledgerJson.encodeToJsonElement(RecognitionResult.serializer(), observed))
            }
        }
        try {
            cases.forEach { checkCase(it) }
            checkCase(cases[0], multiple)
            checkCase(cases[1], cents)
            File(app.filesDir, "recognition-v070-cloud-results.json").writeText(buildJsonObject {
                put("reference_date", reference); put("model", "qwen-plus"); put("asr_model", "fun-asr-realtime")
                put("bill_writes", 0); put("synthetic_only", true); put("cases", JsonArray(rows))
            }.toString())
            assertTrue("Cloud recognition regression failed; inspect synthetic result JSON", rows.size == 12 && rows.all { it.getValue("passed").jsonPrimitive.boolean })
        } finally {
            keyFile.delete(); multiple.delete(); cents.delete()
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }
}

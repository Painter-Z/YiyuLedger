package com.yiyu.ledger

import com.yiyu.ledger.backup.ledgerJson
import com.yiyu.ledger.cloud.DirectCloudClient
import com.yiyu.ledger.cloud.parseProposals
import com.yiyu.ledger.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class RecognitionV2Test {
    private val reference = "2026-10-18"
    private val original = "今天我吃了保障餐，花了八块。又喝了一碗粥，花了两块五"
    private val first = CloudProposal("expense", "保障餐", "CNY", "八块", "8.00", "food", source_text = "今天我吃了保障餐，花了八块", title_text = "保障餐")
    private val second = first.copy(title = "粥", title_text = "粥", source_text = "又喝了一碗粥，花了两块五", amount_text = "两块五", amount_yuan = "2.50")
    private val valid get() = CloudProposalResult(listOf(first, second))
    private val wrong get() = CloudProposalResult(listOf(first, second.copy(title = "保障餐", title_text = "保障餐")))

    @Test fun allSpokenAmountsMatchShippedHarmony070() {
        val contract = ledgerJson.parseToJsonElement(javaClass.classLoader!!.getResource("recognition-v2.json")!!.readText()).jsonObject
        contract.getValue("evidence").jsonArray.forEach { raw ->
            val item = raw.jsonObject
            val text = item.getValue("text").jsonPrimitive.content
            assertEquals(text, item.getValue("expected").jsonPrimitive.longOrNull, runCatching { evidenceMinor(text) }.getOrNull())
        }
        assertEquals(1189L, evidenceMinor("十一块八毛九"))
        assertEquals(1189L, evidenceMinor("11块8毛9"))
        assertEquals(89L, evidenceMinor("八毛九"))
    }

    @Test fun reportedNamesRemainDistinctAndAmountsAreGrounded() {
        val result = normalizeCloudResult(valid, original, reference)
        assertEquals(listOf("保障餐", "粥"), result.drafts.map { it.title })
        assertEquals(listOf(800L, 250L), result.drafts.map { it.amount_minor })
        assertTrue(result.drafts.none { it.review_required })
        assertEquals(second.source_text, result.drafts[1].source_text)
    }

    @Test fun duplicatedNameIsCorrectedOnlyFromItsOwnAnchor() {
        val fixed = normalizeCloudResult(CloudProposalResult(listOf(first, second.copy(title = "保障餐"))), original, reference)
        assertEquals("粥", fixed.drafts[1].title)
        val blocked = normalizeCloudResult(wrong, original, reference)
        assertEquals("", blocked.drafts[1].title)
        assertEquals(250L, blocked.drafts[1].amount_minor)
        assertTrue(blocked.drafts[1].review_required)
    }

    @Test fun genuineSameNameSamePricePurchasesAreNotMerged() {
        val text = "早上保障餐八块，晚上保障餐八块"
        val result = normalizeCloudResult(CloudProposalResult(listOf(first.copy(source_text = text), first.copy(source_text = text))), text, reference)
        assertEquals(2, result.drafts.size)
        assertTrue(result.drafts.all { it.title == "保障餐" && it.amount_minor == 800L && !it.review_required })
    }

    @Test fun healthyResultUsesOneRequest() = runBlocking {
        var calls = 0
        val result = recognizeGrounded(original, reference, emptyList()) { review -> calls++; assertNull(review); valid }
        assertEquals(1, calls); assertEquals(2, result.drafts.size)
    }

    @Test fun oneReviewRepairsCopiedNameWithoutDroppingBills() = runBlocking {
        var calls = 0
        val result = recognizeGrounded(original, reference, emptyList()) { review ->
            calls++
            if (review == null) wrong else { assertTrue(review.problems.isNotEmpty()); valid }
        }
        assertEquals(2, calls); assertEquals(listOf("保障餐", "粥"), result.drafts.map { it.title })
    }

    @Test fun failedUnchangedAndTruncatingReviewsKeepOriginalPendingBills() = runBlocking {
        listOf("network", "same", "drop").forEach { mode ->
            var calls = 0
            val result = recognizeGrounded(original, reference, emptyList()) { review ->
                calls++
                if (review == null) wrong else when (mode) {
                    "network" -> throw IOException("test timeout")
                    "same" -> wrong
                    else -> CloudProposalResult(listOf(first))
                }
            }
            assertEquals(2, calls); assertEquals(2, result.drafts.size)
            assertEquals("", result.drafts[1].title); assertTrue(result.drafts[1].review_required)
        }
    }

    @Test fun invalidStructureCanBeRepairedOnce() = runBlocking {
        var calls = 0
        val result = recognizeGrounded(original, reference, emptyList()) { review ->
            calls++; if (review == null) CloudProposalResult(listOf(first.copy(kind = "invalid"))) else valid
        }
        assertEquals(2, calls); assertEquals(2, result.drafts.size)
    }

    @Test fun clearPaymentWithEmptyFirstResultGetsOneReview() = runBlocking {
        val text = "保障餐和粥一共花了十块五"
        var calls = 0
        val result = recognizeGrounded(text, reference, emptyList()) { review ->
            calls++; if (review == null) CloudProposalResult(emptyList()) else CloudProposalResult(listOf(first.copy(
                title = "保障餐和粥", title_text = "保障餐和粥", source_text = text, amount_text = "十块五", amount_yuan = "10.50")))
        }
        assertEquals(2, calls); assertEquals(1050L, result.drafts.single().amount_minor)
    }

    @Test fun nonLedgerContentDoesNotTriggerExtraRequests() = runBlocking {
        var calls = 0
        val result = recognizeGrounded("今天天气不错", reference, emptyList()) { calls++; CloudProposalResult(emptyList()) }
        assertEquals(1, calls); assertTrue(result.drafts.isEmpty())
    }

    @Test fun cancellationIsNotConvertedIntoAnEditableResult() = runBlocking {
        var calls = 0; var cancelled = false
        try {
            recognizeGrounded(original, reference, emptyList()) { review ->
                calls++; if (review == null) wrong else throw CancellationException("test cancellation")
            }
        } catch (_: CancellationException) { cancelled = true }
        assertEquals(2, calls); assertTrue(cancelled)
    }

    @Test fun oldPendingJsonWithoutNewFieldsStillDecodes() {
        val old = """{"transcript":"米线9元","drafts":[{"kind":"expense","title":"米线","amount_minor":900,"category_id":"food","occurred_on":"2026-10-18","time_hint":"","note":"","issues":[]}],"issues":[]}"""
        val draft = ledgerJson.decodeFromString<RecognitionResult>(old).drafts.single()
        assertEquals("米线", draft.title); assertEquals("", draft.source_text); assertFalse(draft.review_required)
    }

    private fun response(result: CloudProposalResult) = MockResponse().setBody(buildJsonObject {
        putJsonArray("choices") { add(buildJsonObject { put("finish_reason", "stop"); put("message", buildJsonObject {
            put("role", "assistant"); put("content", ledgerJson.encodeToString(CloudProposalResult.serializer(), result))
        }) }) }
    }.toString()).setHeader("Content-Type", "application/json")

    @Test fun httpReviewCarriesOriginalTextAndStructuredDiagnostics() = runBlocking {
        val server = MockWebServer().apply { enqueue(response(wrong)); enqueue(response(valid)); start() }
        val http = OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url("/chat")).build()) }.build()
        try {
            val result = DirectCloudClient("test-placeholder", "contract-test-prompt", http).parse(original, reference, emptyList())
            assertEquals(listOf("保障餐", "粥"), result.drafts.map { it.title })
            assertEquals(2, server.requestCount)
            val firstRequest = ledgerJson.parseToJsonElement(server.takeRequest(3, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
            val secondRequest = ledgerJson.parseToJsonElement(server.takeRequest(3, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
            fun user(body: JsonObject) = ledgerJson.parseToJsonElement(body.getValue("messages").jsonArray[1].jsonObject.getValue("content").jsonPrimitive.content).jsonObject
            assertFalse(user(firstRequest).containsKey("review"))
            assertEquals(original, user(secondRequest).getValue("text").jsonPrimitive.content)
            assertTrue(user(secondRequest).getValue("review").jsonObject.getValue("problems").jsonArray.isNotEmpty())
            assertEquals("contract-test-prompt", firstRequest.getValue("messages").jsonArray[0].jsonObject.getValue("content").jsonPrimitive.content)
        } finally { http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll(); server.shutdown() }
    }

    @Test fun httpAuthenticationFailureIsNotRetried() = runBlocking {
        val server = MockWebServer().apply { enqueue(MockResponse().setResponseCode(401)); start() }
        val http = OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url("/chat")).build()) }.build()
        try {
            var failed = false
            try { DirectCloudClient("test-placeholder", "prompt", http).parse(original, reference, emptyList()) }
            catch (_: Exception) { failed = true }
            assertTrue(failed); assertEquals(1, server.requestCount)
        } finally { http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll(); server.shutdown() }
    }

    @Test fun parserDoesNotAcceptNumericJsonMoneyAsQuotedText() {
        val raw = parseProposals("""{"entries":[{"kind":"expense","title":"粥","source_text":"粥两块五","title_text":"粥","amount_text":"两块五","amount_yuan":2.5,"category_id":"food","currency":"CNY"}]}""")
        assertNull(normalizeCloudResult(raw, "粥两块五", reference).drafts.single().amount_minor)
    }
}

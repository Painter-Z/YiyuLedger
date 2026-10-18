package com.yiyu.ledger

import com.yiyu.ledger.backup.*
import com.yiyu.ledger.cloud.parseProposals
import com.yiyu.ledger.data.*
import com.yiyu.ledger.domain.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LedgerContractTest {
    private val golden = ledgerJson.parseToJsonElement(javaClass.classLoader!!.getResource("ledger-golden.json")!!.readText()).jsonObject
    private val entries = ledgerJson.decodeFromJsonElement<List<LedgerEntry>>(golden.getValue("entries"))
    private val custom = ledgerJson.decodeFromJsonElement<List<CustomCategory>>(golden.getValue("custom"))
    @Test fun exactAmountsMatchHarmony() {
        golden.getValue("amounts").jsonArray.forEach { case -> val item = case.jsonObject; assertEquals(item.toString(), item.getValue("expected").jsonPrimitive.long, parseMinor(item.getValue("text").jsonPrimitive.content)) }
        assertEquals("-0.01", formatMoney(-1)); assertEquals("1,286.50", formatMoney(128650))
    }
    @Test fun chineseEvidenceMatchesHarmony() {
        golden.getValue("evidence").jsonArray.forEach { case ->
            val item = case.jsonObject; val text = item.getValue("text").jsonPrimitive.content
            val result = runCatching { evidenceMinor(text) }.getOrNull()
            assertEquals(text, item.getValue("expected").jsonPrimitive.longOrNull, result)
        }
    }
    @Test fun calendarPeriodsMatchHarmony() {
        golden.getValue("periods").jsonArray.forEach { case ->
            val item = case.jsonObject
            assertEquals(item.getValue("expected"), ledgerJson.encodeToJsonElement(periodFor(item.getValue("mode").jsonPrimitive.content, item.getValue("reference").jsonPrimitive.content, item.getValue("offset").jsonPrimitive.int)))
        }
        listOf("2026-02-29", "2026-04-31", "2026-1-01", "1899-12-31").forEach { assertFalse(isDateKey(it)) }
        assertTrue(isDateKey("2024-02-29"))
    }
    @Test fun summariesAndEveryChartBucketMatchHarmony() {
        golden.getValue("statistics").jsonArray.forEach { case ->
            val item = case.jsonObject
            val period = ledgerJson.decodeFromJsonElement<Period>(item.getValue("period"))
            assertEquals(item.getValue("summary"), ledgerJson.encodeToJsonElement(summarize(entries, period)))
            assertEquals(item.getValue("trends"), ledgerJson.encodeToJsonElement(trendPoints(entries, period)))
            val expected = ledgerJson.decodeFromJsonElement<List<CategoryTotal>>(item.getValue("rankings"))
            assertEquals(expected, categoryTotals(entries, period, "expense", custom))
            assertEquals(summarize(entries, period).expense, trendPoints(entries, period).sumOf { it.expense })
        }
    }
    @Test fun recognitionLocalChecksMatchHarmony() {
        val recognition = ledgerJson.parseToJsonElement(javaClass.classLoader!!.getResource("recognition-v2.json")!!.readText()).jsonObject
        recognition.getValue("cases").jsonArray.forEach { case ->
            val item = case.jsonObject
            val raw = parseProposals(item.getValue("raw").toString())
            val result = normalizeCloudResult(raw, item.getValue("text").jsonPrimitive.content, item.getValue("reference").jsonPrimitive.content, custom)
            assertEquals(item.getValue("name").jsonPrimitive.content, item.getValue("expected"), ledgerJson.encodeToJsonElement(result))
        }
        val numeric = parseProposals("""{"entries":[{"kind":"expense","title":"午餐","amount_text":"15元","amount_yuan":15,"category_id":"food"}]}""")
        assertNull(normalizeCloudResult(numeric, "午餐15元", today()).drafts.first().amount_minor)
        assertTrue(normalizeCloudResult(CloudProposalResult(emptyList()), "你好", today()).drafts.isEmpty())
    }
    @Test fun backupAndCsvAreBidirectionallyCompatible() {
        val content = golden.getValue("backup").jsonPrimitive.content
        val document = parseBackup(content)
        assertEquals(entries.filter { it.deletedAt == 0L }, document.entries)
        assertEquals(custom, document.categories)
        assertEquals(ledgerJson.parseToJsonElement(content), ledgerJson.parseToJsonElement(exportBackup(entries, custom, "2026-10-11T00:00:00.000Z")))
        assertEquals(golden.getValue("csv").jsonPrimitive.content, exportCsv(entries, custom))
        val csv = exportCsv(listOf(entries.first().copy(title = "=HYPERLINK(\"x\")", note = "+123")), emptyList())
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"x\"\")\"")); assertTrue(csv.contains("\"'+123\""))
        val v1 = """{"format":"yiyu-ledger","version":1,"entries":[${ledgerJson.encodeToString(LedgerEntry.serializer(), entries.first().copy(deletedAt = 2000))}]}"""
        assertTrue(parseBackup(v1).entries.isEmpty())
    }
    @Test fun corruptBackupAndCategoryCollisionsAreRejected() {
        val first = entries.first()
        val duplicate = exportBackup(listOf(first, first), emptyList())
        assertThrows(IllegalArgumentException::class.java) { parseBackup(duplicate) }
        assertThrows(IllegalArgumentException::class.java) { parseBackup(exportBackup(listOf(first.copy(amountMinor = 0)), emptyList())) }
        assertThrows(IllegalArgumentException::class.java) { parseBackup(exportBackup(listOf(first), emptyList()).replace("\"amountMinor\": 1500", "\"amountMinor\": \"1500\"")) }
        assertThrows(IllegalArgumentException::class.java) { mergeCategoryCatalog(custom, listOf(custom.first().copy(name = "另一种"))) }
        assertThrows(IllegalArgumentException::class.java) { mergeCategoryCatalog(custom, listOf(custom.first().copy(id = "custom_other"))) }
        assertTrue(validateCategoryCatalog(listOf(custom.first().copy(name = "餐饮"))).isNotEmpty())
        assertEquals(golden.getValue("suggestions").jsonArray.map { it.jsonPrimitive.content }, suggestedCategories("expense", "custom_pet", "买猫粮15元", "猫粮", entries, custom).map { it.id })
    }
    @Test fun pendingCompactionKeepsStableIndicesAndNoSavedCopy() {
        val draft = RecognizedDraft("expense", "米线", 900, "food", today(), "", "", emptyList())
        val result = RecognitionResult("米线9元，地铁3元", listOf(draft, draft.copy(title = "地铁", amount_minor = 300, category_id = "transport")), emptyList())
        val pending = PendingRecording("voice_test", "", today(), 1000, ledgerJson.encodeToString(RecognitionResult.serializer(), result), 1, 0, "voice")
        assertEquals("地铁", pendingResult(pending).drafts.single().title)
        val compact = pending.copy(resultJson = ledgerJson.encodeToString(RecognitionResult.serializer(), pendingResult(pending).copy(transcript = "")), resultOffset = 1)
        assertEquals("", pendingResult(compact).transcript)
        assertEquals(1, pendingResult(compact).drafts.size)
        assertThrows(IllegalArgumentException::class.java) { pendingResult(compact.copy(savedCount = 0)) }
    }
    @Test fun sentenceCorrectionsReplaceEarlierAsrText() {
        val accumulator = SpeechAccumulator()
        accumulator.accept(1000, "地铁3元。", true)
        accumulator.accept(0, "午饭15元。", true)
        accumulator.accept(0, "午饭13元。", true)
        assertEquals("午饭13元。地铁3元。", accumulator.finish())
    }
}

package com.yiyu.ledger

import com.yiyu.ledger.domain.*
import com.yiyu.ledger.backup.ledgerJson
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LedgerBrowseTest {
    private val fixture = ledgerJson.parseToJsonElement(javaClass.classLoader!!.getResource("ledger-browse.json")!!.readText()).jsonObject
    private val entries = ledgerJson.decodeFromJsonElement<List<LedgerEntry>>(fixture.getValue("entries"))
    private fun options(today: String = "2026-10-14") = LedgerBrowseOptions(today)

    @Test fun twentyBrowsingCasesMatchHarmonyIncludingDateLabelsAndFullTotals() {
        fixture.getValue("cases").jsonArray.forEach { case ->
            val item = case.jsonObject; val input = item.getValue("options").jsonObject
            fun value(key: String) = input.getValue(key).jsonPrimitive.content
            val options = LedgerBrowseOptions(value("today"), value("search"), value("kind"), value("categoryId"), value("dateFilter"), value("rangeStart"), value("rangeEnd"))
            val result = browseLedger(entries, options)
            val actual = buildJsonObject {
                put("scope", buildJsonObject { put("mode", result.scope.mode); put("start", result.scope.start); put("end", result.scope.end); put("label", result.scope.label) })
                put("entryIds", JsonArray(result.entries.map { JsonPrimitive(it.id) }))
                put("expenseByDay", buildJsonObject { result.expenseByDay.forEach { (day, total) -> put(day, total) } })
                put("emptyDay", result.emptyDay)
                put("days", JsonArray(ledgerDayPage(result).map { day -> buildJsonObject {
                    put("date", day.date); put("expenseMinor", day.expenseMinor); put("label", ledgerDayLabel(day.date, options.today))
                    put("entryIds", JsonArray(day.entries.map { JsonPrimitive(it.id) }))
                } }))
            }
            assertEquals(item.getValue("name").jsonPrimitive.content, item.getValue("expected"), actual)
        }
    }
    @Test fun pagingDoesNotTruncateDayExpensesOrMutateTheBook() {
        val base = entries.first()
        val many = (1..240).map { base.copy(id = "page_$it", amountMinor = 1, createdAt = it.toLong()) }
        val result = browseLedger(many, options())
        assertEquals(240, result.entries.size)
        assertEquals(80, ledgerDayPage(result).single().entries.size)
        assertEquals(240L, ledgerDayPage(result).single().expenseMinor)
        assertEquals(160, ledgerDayPage(result, 160).single().entries.size)
        assertEquals(240, ledgerDayPage(result, 320).single().entries.size)
        assertEquals(240, many.size)
    }
    @Test fun emptyTodayIsKeptWhenOnlyYesterdayHasMatchingBills() {
        val result = browseLedger(entries.filter { it.id == "yesterday" }, options())
        assertEquals(listOf("2026-10-14", "2026-10-13"), ledgerDayPage(result).map { it.date })
        assertTrue(ledgerDayPage(result).first().entries.isEmpty())
    }
    @Test fun editsAndDeletionRecomputeCompleteExpenseWithAnIncomeFilter() {
        val income = options().copy(kind = "income")
        assertEquals(1250L, browseLedger(entries, income).expenseByDay["2026-10-14"])
        val edited = entries.map { if (it.id == "today_print") it.copy(amountMinor = 900) else it }
        assertEquals(1650L, browseLedger(edited, income).expenseByDay["2026-10-14"])
        assertEquals(750L, browseLedger(edited.filter { it.id != "today_print" }, income).expenseByDay["2026-10-14"])
    }
    @Test fun selectedDayReplacesStatisticsCategoryButKeepsQueryAndKind() {
        val before = options().copy(search = "工资", kind = "income", categoryId = "food", rangeStart = "2026-10-01", rangeEnd = "2026-10-31")
        val after = before.selectDate("2026-10-14")
        assertEquals("", after.rangeStart); assertEquals("", after.rangeEnd); assertEquals("", after.categoryId)
        assertEquals("工资", after.search); assertEquals("income", after.kind)
        assertEquals(listOf("today_income"), browseLedger(entries, after).entries.map { it.id })
    }
    @Test fun returnClearsAllScopeAndQueryConditionsButPreservesKind() {
        val after = options().copy(kind = "income", search = "工资", categoryId = "salary", dateFilter = "2025-08-01", rangeStart = "2025-01-01", rangeEnd = "2025-12-31").resetScope()
        assertEquals(options().copy(kind = "income"), after)
        assertEquals("recent", browseLedger(entries, after).scope.mode)
    }
    @Test fun invalidDaysAndHalfRangesAreRejected() {
        listOf(options().copy(dateFilter = "2026-02-30"), options().copy(dateFilter = "2026-13"), options().copy(rangeStart = "2026-10-14"),
            options().copy(rangeStart = "2026-10-15", rangeEnd = "2026-10-14"), options("1899-01-01")).forEach {
            assertThrows(IllegalArgumentException::class.java) { ledgerDateScope(it) }
        }
    }
    @Test fun weekdayUsesTheChosenLocalDateAcrossYearAndLeapBoundaries() {
        assertEquals("星期三", ledgerWeekday("2026-10-14")); assertEquals("星期日", ledgerWeekday("2026-10-11"))
        assertEquals("星期四", ledgerWeekday("2024-02-29")); assertEquals("星期五", ledgerWeekday("2027-01-01"))
    }
}

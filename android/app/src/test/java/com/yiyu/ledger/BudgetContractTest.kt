package com.yiyu.ledger

import com.yiyu.ledger.backup.*
import com.yiyu.ledger.domain.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BudgetContractTest {
    private fun entry(id: String = "budget_test", kind: String = "expense", amount: Long = 900) =
        LedgerEntry(id, kind, amount, "CNY", if(kind == "income") "salary" else "food", "米线", "2026-10-12", "", "", "manual", "", "", 1000, 1000, 0)
    @Test fun sharedCasesMatchHarmonyAtExactLimitOneCentAndMultipleLaps() {
        val cases = ledgerJson.parseToJsonElement(javaClass.classLoader!!.getResource("monthly-budget.json")!!.readText()).jsonObject.getValue("cases").jsonArray
        cases.forEach { value ->
            val item = value.jsonObject
            fun amount(key: String) = item.getValue(key).jsonPrimitive.long
            val entries = listOfNotNull(amount("expense").takeIf { it > 0 }?.let { entry(amount = it) },
                amount("refund").takeIf { it > 0 }?.let { entry("refund", "refund", it) })
            val result = monthlyBudgetProgress(entries, listOf(MonthlyBudget("2026-10", amount("limit"))), "2026-10")
            assertEquals(item.getValue("name").toString(), item.getValue("state").jsonPrimitive.content, result.state)
            assertEquals(amount("spent"), result.spent); assertEquals(amount("remaining"), result.remaining); assertEquals(amount("overspent"), result.overspent)
            assertEquals(item.getValue("baseRatio").jsonPrimitive.double, result.baseRatio, 1e-10)
            assertEquals(item.getValue("overRatio").jsonPrimitive.double, result.overRatio, 1e-10)
        }
    }
    @Test fun calendarInheritanceAndDisabledMonthsDoNotRewriteHistory() {
        val budgets = listOf(MonthlyBudget("2026-10", 20000), MonthlyBudget("2026-12", 0), MonthlyBudget("2027-02", 35000))
        assertEquals(0L, budgetLimit(budgets, "2026-09")); assertEquals(20000L, budgetLimit(budgets, "2026-11"))
        assertEquals(0L, budgetLimit(budgets, "2027-01")); assertEquals(35000L, budgetLimit(budgets, "2027-03"))
        val period = periodFor("month", "2026-10-12", -1)
        assertEquals("2026-09", budgetMonthFor("month", period, "2026-10-12"))
        for(mode in listOf("week", "year")) assertEquals("2026-10", budgetMonthFor(mode, period, "2026-10-12"))
    }
    @Test fun excludedKindsDatesAndDeletedBillsNeverConsumeBudget() {
        val entries = listOf(entry(), entry("income", "income"), entry("transfer", "transfer"), entry("debt", "debt"),
            entry("deleted").copy(deletedAt = 1001), entry("previous").copy(occurredOn = "2026-09-12"))
        val budgets = listOf(MonthlyBudget("2026-10", 10000))
        assertEquals(900L, monthlyBudgetProgress(entries, budgets, "2026-10").spent)
        assertEquals(0L, monthlyBudgetProgress(entries.filter { it.id != "budget_test" }, budgets, "2026-10").spent)
    }
    @Test fun v3BackupRoundtripAndLegacyFilesKeepBudgetsValid() {
        val budgets = listOf(MonthlyBudget("2026-10", 10000), MonthlyBudget("2026-11", 0))
        val result = parseBackup(exportBackup(listOf(entry()), emptyList(), monthlyBudgets = budgets))
        assertEquals(3, result.version); assertEquals(budgets, result.monthlyBudgets)
        for(version in listOf(1, 2)) assertTrue(parseBackup("""{"format":"yiyu-ledger","version":$version,"entries":[],"categories":[]} """).monthlyBudgets.isEmpty())
        val combined = mergeMonthlyBudgets(budgets, listOf(MonthlyBudget("2026-10", 50), MonthlyBudget("2026-12", 20000)))
        assertEquals(budgets + MonthlyBudget("2026-12", 20000), combined)
        for(catalog in listOf(listOf(MonthlyBudget("2026-13", 1)), listOf(MonthlyBudget("2026-10", -1)), budgets + budgets.first())) {
            assertTrue(validateMonthlyBudgets(catalog).isNotEmpty())
            assertThrows(IllegalArgumentException::class.java) { parseBackup(exportBackup(emptyList(), emptyList(), monthlyBudgets = catalog)) }
        }
        assertThrows(IllegalArgumentException::class.java) { parseBackup("""{"format":"yiyu-ledger","version":3,"entries":[],"categories":[],"monthlyBudgets":[{"month":"2026-10","amountMinor":"10"}]}""") }
    }
}

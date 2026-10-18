package com.yiyu.ledger.domain

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "monthly_budgets")
data class MonthlyBudget(@PrimaryKey val month: String, val amountMinor: Long)

data class BudgetProgress(val month: String, val limit: Long, val spent: Long, val remaining: Long,
    val overspent: Long, val usedRatio: Double, val baseRatio: Double, val overRatio: Double, val state: String)

fun validateMonthlyBudgets(budgets: List<MonthlyBudget>): String {
    if (budgets.size > 3600) return "月预算数量不正确"
    val months = mutableSetOf<String>()
    budgets.forEach {
        if (!Regex("^\\d{4}-\\d{2}$").matches(it.month) || !isDateKey(it.month + "-01")) return "预算月份不正确"
        if (it.amountMinor !in 0..MAX_AMOUNT) return "预算金额不正确"
        if (!months.add(it.month)) return "预算含重复月份"
    }
    return ""
}
fun mergeMonthlyBudgets(existing: List<MonthlyBudget>, incoming: List<MonthlyBudget>): List<MonthlyBudget> {
    requireValid(validateMonthlyBudgets(existing)); requireValid(validateMonthlyBudgets(incoming))
    val merged = (existing + incoming.filter { added -> existing.none { it.month == added.month } }).sortedBy { it.month }
    requireValid(validateMonthlyBudgets(merged))
    return merged
}
fun budgetLimit(budgets: List<MonthlyBudget>, month: String): Long {
    requireValid(validateMonthlyBudgets(budgets))
    return budgets.filter { it.month <= month }.maxByOrNull { it.month }?.amountMinor ?: 0L
}
fun monthlyBudgetProgress(entries: List<LedgerEntry>, budgets: List<MonthlyBudget>, month: String): BudgetProgress {
    requireValid(validateMonthlyBudgets(listOf(MonthlyBudget(month, 0))))
    val spent = summarize(entries, periodFor("month", month + "-01")).netExpense.coerceAtLeast(0)
    val limit = budgetLimit(budgets, month)
    val ratio = if (limit > 0) spent.toDouble() / limit else 0.0
    return BudgetProgress(month, limit, spent, (limit - spent).coerceAtLeast(0),
        if (limit > 0) (spent - limit).coerceAtLeast(0) else 0, ratio, ratio.coerceAtMost(1.0), (ratio - 1).coerceIn(0.0, 1.0),
        when { limit == 0L -> "unset"; spent < limit -> "available"; spent == limit -> "reached"; else -> "over" })
}
fun budgetMonthFor(mode: String, period: Period, today: String) = (if (mode == "month") period.start else today).take(7)

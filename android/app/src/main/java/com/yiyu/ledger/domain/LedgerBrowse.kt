package com.yiyu.ledger.domain

import java.time.LocalDate

data class LedgerBrowseOptions(
    val today: String, val search: String = "", val kind: String = "all", val categoryId: String = "",
    val dateFilter: String = "", val rangeStart: String = "", val rangeEnd: String = ""
)
data class LedgerDateScope(val mode: String, val start: String, val end: String, val label: String)
data class LedgerBrowseResult(
    val scope: LedgerDateScope, val entries: List<LedgerEntry>, val expenseByDay: Map<String, Long>, val emptyDay: String
)
data class LedgerDay(val date: String, val expenseMinor: Long, val entries: List<LedgerEntry>)

fun ledgerDateScope(options: LedgerBrowseOptions): LedgerDateScope {
    require(isDateKey(options.today)) { "当前日期不正确" }
    if (options.dateFilter.isNotEmpty()) {
        if (isDateKey(options.dateFilter)) return LedgerDateScope("day", options.dateFilter, options.dateFilter, "查看：${options.dateFilter}")
        if (Regex("^\\d{4}-\\d{2}$").matches(options.dateFilter) && isDateKey(options.dateFilter + "-01")) {
            val month = periodFor("month", options.dateFilter + "-01")
            return LedgerDateScope("month", month.start, month.end, "查看：${month.label}")
        }
        throw IllegalArgumentException("请选择有效日期")
    }
    if (options.rangeStart.isNotEmpty() || options.rangeEnd.isNotEmpty()) {
        require(isDateKey(options.rangeStart) && isDateKey(options.rangeEnd) && options.rangeStart <= options.rangeEnd) { "日期范围不正确" }
        return LedgerDateScope("period", options.rangeStart, options.rangeEnd, "范围：${options.rangeStart} 至 ${options.rangeEnd}")
    }
    if (options.search.isNotBlank()) return LedgerDateScope("all", "", "", "全部日期 · 搜索结果")
    val start = shiftDays(options.today, -2)
    return LedgerDateScope("recent", start, options.today, "最近三天 · ${start.drop(5).replace('-', '.')}—${options.today.drop(5).replace('-', '.')}")
}

/** Totals cover every active CNY expense in the scope, independently of the visible filters. */
fun browseLedger(entries: List<LedgerEntry>, options: LedgerBrowseOptions, custom: List<CustomCategory> = emptyList()): LedgerBrowseResult {
    val scope = ledgerDateScope(options)
    val eligible = ArrayList<LedgerEntry>()
    val expenses = mutableMapOf<String, Long>()
    for (entry in entries) {
        if (entry.deletedAt != 0L || entry.currency != "CNY" ||
            (scope.start.isNotEmpty() && entry.occurredOn !in scope.start..scope.end)) continue
        eligible += entry
        if (entry.kind == "expense") expenses[entry.occurredOn] = (expenses[entry.occurredOn] ?: 0L) + entry.amountMinor
    }
    return LedgerBrowseResult(scope, filterEntries(eligible, options.search, options.kind, options.categoryId, custom), expenses,
        if (scope.mode == "day") scope.start else if (scope.mode == "recent") options.today else "")
}

/** Rendering is batched; displayed daily totals always include the complete day. */
fun ledgerDayPage(result: LedgerBrowseResult, limit: Int = 80): List<LedgerDay> {
    val groups = result.entries.take(limit.coerceAtLeast(1)).groupByTo(linkedMapOf()) { it.occurredOn }
    if (result.emptyDay.isNotEmpty()) groups.putIfAbsent(result.emptyDay, mutableListOf())
    return groups.entries.sortedByDescending { it.key }.map { (date, entries) -> LedgerDay(date, result.expenseByDay[date] ?: 0L, entries) }
}
fun ledgerDayLabel(day: String, today: String): String = when (day) {
    today -> "今天 · ${day.drop(5)}"
    shiftDays(today, -1) -> "昨天 · ${day.drop(5)}"
    shiftDays(today, -2) -> "前天 · ${day.drop(5)}"
    else -> day
}
fun ledgerWeekday(date: String) = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")[LocalDate.parse(date).dayOfWeek.value - 1]
fun LedgerBrowseOptions.resetScope() = copy(search = "", dateFilter = "", rangeStart = "", rangeEnd = "", categoryId = "")
fun LedgerBrowseOptions.selectDate(date: String): LedgerBrowseOptions {
    require(isDateKey(date)) { "请选择有效日期" }
    return copy(dateFilter = date, rangeStart = "", rangeEnd = "", categoryId = "")
}

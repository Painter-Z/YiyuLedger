package com.yiyu.ledger.domain

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import java.util.Locale

const val MAX_AMOUNT = 999_999_999_999L
const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
val KINDS = listOf("expense", "income", "refund", "transfer", "debt")
fun kindName(kind: String) = mapOf("expense" to "支出", "income" to "收入", "refund" to "退款", "transfer" to "转账", "debt" to "往来")[kind] ?: "支出"
fun categoryScope(kind: String) = if (kind == "refund") "expense" else kind

@Serializable
@Entity(tableName = "entries")
data class LedgerEntry(
    @PrimaryKey val id: String, val kind: String, val amountMinor: Long, val currency: String,
    val categoryId: String, val title: String, val occurredOn: String, val timeHint: String,
    val note: String, val source: String, val groupId: String, val linkedEntryId: String,
    val createdAt: Long, val updatedAt: Long, val deletedAt: Long
)

@Serializable
@Entity(tableName = "categories")
data class CustomCategory(@PrimaryKey val id: String, val name: String, val kind: String)

@Serializable data class Category(val id: String, val name: String, val icon: String, val color: String, val background: String)
val CATEGORIES = listOf(
    Category("food", "餐饮", "food", "#547347", "#EAF0DD"),
    Category("transport", "交通", "car", "#587D8D", "#E7EFF2"),
    Category("shopping", "购物", "bag", "#9C775B", "#F2EADF"),
    Category("daily", "日用", "basket", "#8B8670", "#ECEBE3"),
    Category("housing", "居住", "home", "#688477", "#E6EDE7"),
    Category("entertainment", "娱乐", "play", "#9581A7", "#EEE8F2"),
    Category("health", "医疗", "heart", "#AD7971", "#F5E7E2"),
    Category("education", "学习", "book", "#7A86A0", "#E9EDF5"),
    Category("travel", "旅行", "plane", "#638D89", "#E3EFEC"),
    Category("social", "人情", "gift", "#B08762", "#F4EDE0"),
    Category("salary", "工资", "wallet", "#AE8252", "#F5ECD9"),
    Category("bonus", "奖金", "gift", "#AE8252", "#F5ECD9"),
    Category("freelance", "兼职", "briefcase", "#AE8252", "#F5ECD9"),
    Category("investment", "利息", "trend", "#AE8252", "#F5ECD9"),
    Category("transfer", "转账", "swap", "#7F8B88", "#E9EDEA"),
    Category("debt", "往来", "swap", "#7F8B88", "#E9EDEA"),
    Category("other", "其他", "dots", "#8C9183", "#ECEEE6")
)
fun categoryCatalog(custom: List<CustomCategory> = emptyList()) = CATEGORIES + custom.map {
    Category(it.id, it.name, "dots", "#63806A", "#E5EEE4")
}
fun categoryById(id: String, custom: List<CustomCategory> = emptyList()) = categoryCatalog(custom).find { it.id == id } ?: CATEGORIES.last()
fun categoriesForKind(kind: String, custom: List<CustomCategory> = emptyList()): List<Category> {
    val base = CATEGORIES.filter {
        when (kind) {
            "income" -> it.id in listOf("salary", "bonus", "freelance", "investment", "social", "other")
            "transfer" -> it.id == "transfer"
            "debt" -> it.id == "debt"
            else -> it.id !in listOf("salary", "bonus", "freelance", "investment", "transfer", "debt")
        }
    }
    return base + categoryCatalog(custom.filter { it.kind == categoryScope(kind) }).drop(CATEGORIES.size)
}
fun validateCategoryCatalog(custom: List<CustomCategory>): String {
    if (custom.size > 100) return "自定义分类最多100个"
    val ids = mutableSetOf<String>()
    val names = mutableSetOf<String>()
    for (item in custom) {
        if (!Regex("^custom_[a-zA-Z0-9_-]{1,64}$").matches(item.id)) return "自定义分类编号不正确"
        if (item.kind !in listOf("expense", "income", "transfer", "debt")) return "自定义分类类型不正确"
        if (item.name.isBlank() || item.name != item.name.trim() || item.name.length > 12 || item.name.any { it.code < 32 || it.code == 127 }) return "分类名称请填写1至12个字"
        if (item.name in listOf("更多", "收起", "自定义")) return "请使用具体的分类名称"
        val key = item.kind + "_" + item.name.lowercase(Locale.ROOT)
        if (!ids.add(item.id) || !names.add(key) || categoriesForKind(item.kind).any { it.name == item.name }) return "该类型下已有同名分类"
    }
    return ""
}
fun mergeCategoryCatalog(existing: List<CustomCategory>, incoming: List<CustomCategory>): List<CustomCategory> {
    val combined = existing.toMutableList()
    incoming.forEach { item ->
        val found = existing.find { it.id == item.id }
        require(found == null || found == item) { "备份分类编号冲突，原账本已保留" }
        if (found == null) combined += item
    }
    requireValid(validateCategoryCatalog(combined))
    return combined
}
fun requireValid(error: String) { require(error.isEmpty()) { error } }
fun parseMinor(value: String): Long {
    val text = value.trim()
    if (!Regex("^\\d{1,10}(\\.\\d{1,2})?$").matches(text)) return -1
    val parts = text.split('.')
    val result = parts[0].toLong() * 100 + (parts.getOrElse(1) { "" } + "00").take(2).toLong()
    return if (result in 1..MAX_AMOUNT) result else -1
}
fun decimalAmount(minor: Long): String {
    val sign = if (minor < 0) "-" else ""
    val amount = kotlin.math.abs(minor)
    return sign + (amount / 100) + "." + (amount % 100).toString().padStart(2, '0')
}
fun formatMoney(minor: Long): String {
    val parts = decimalAmount(minor).split('.')
    return parts[0].replace(Regex("\\B(?=(\\d{3})+(?!\\d))"), ",") + "." + parts[1]
}
fun today() = LocalDate.now().toString()
fun isDateKey(value: String): Boolean = Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(value) &&
    value in "1900-01-01".."2199-12-31" && runCatching { LocalDate.parse(value).toString() == value }.getOrDefault(false)
fun shiftDays(value: String, delta: Long) = LocalDate.parse(value).plusDays(delta).toString()
@Serializable data class Period(val start: String, val end: String, val label: String, val mode: String)
@Serializable data class Summary(val expense: Long, val income: Long, val refund: Long, val netExpense: Long, val balance: Long, val count: Int)
@Serializable data class CategoryTotal(val category: Category, val amount: Long, val ratio: Double, val count: Int)
@Serializable data class TrendPoint(val date: String, val label: String, val expense: Long, val income: Long, val refund: Long)
fun periodFor(mode: String, reference: String, offset: Int = 0): Period {
    val date = LocalDate.parse(reference)
    return when (mode) {
        "week" -> {
            val first = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(offset.toLong())
            val last = first.plusDays(6)
            Period(first.toString(), last.toString(), "${first.monthValue}.${first.dayOfMonth} — ${last.monthValue}.${last.dayOfMonth}", mode)
        }
        "year" -> {
            val year = date.year + offset
            Period("$year-01-01", "$year-12-31", "${year}年", mode)
        }
        else -> {
            val first = date.withDayOfMonth(1).plusMonths(offset.toLong())
            Period(first.toString(), first.with(TemporalAdjusters.lastDayOfMonth()).toString(), "${first.year}年${first.monthValue}月", "month")
        }
    }
}
fun inPeriod(entry: LedgerEntry, period: Period) = entry.deletedAt == 0L && entry.currency == "CNY" && entry.occurredOn in period.start..period.end
fun summarize(entries: List<LedgerEntry>, period: Period): Summary {
    val active = entries.filter { inPeriod(it, period) }
    fun total(kind: String) = active.filter { it.kind == kind }.sumOf { it.amountMinor }
    val expense = total("expense"); val income = total("income"); val refund = total("refund")
    return Summary(expense, income, refund, expense - refund, income - expense + refund, active.size)
}
fun categoryTotals(entries: List<LedgerEntry>, period: Period, kind: String = "expense", custom: List<CustomCategory> = emptyList()): List<CategoryTotal> {
    val relevant = entries.filter { inPeriod(it, period) && it.kind == kind }
    val sum = relevant.sumOf { it.amountMinor }
    return categoryCatalog(custom).map { category ->
        val selected = relevant.filter { it.categoryId == category.id }
        val amount = selected.sumOf { it.amountMinor }
        CategoryTotal(category, amount, if (sum == 0L) 0.0 else amount.toDouble() / sum, selected.size)
    }.filter { it.amount > 0 }.sortedByDescending { it.amount }
}
fun trendPoints(entries: List<LedgerEntry>, period: Period): List<TrendPoint> {
    if (period.mode == "year") return (1..12).map { month ->
        val prefix = period.start.take(4) + "-" + month.toString().padStart(2, '0')
        val summary = summarize(entries.filter { it.occurredOn.startsWith(prefix) }, period)
        TrendPoint("$prefix-01", "${month}月", summary.expense, summary.income, summary.refund)
    }
    val points = mutableListOf<TrendPoint>()
    var date = period.start
    while (date <= period.end && points.size < 32) {
        val summary = summarize(entries, Period(date, date, date, "day"))
        points += TrendPoint(date, if (period.mode == "week") listOf("一", "二", "三", "四", "五", "六", "日")[points.size] else date.takeLast(2).toInt().toString(), summary.expense, summary.income, summary.refund)
        date = shiftDays(date, 1)
    }
    return points
}
fun filterEntries(entries: List<LedgerEntry>, search: String = "", kind: String = "all", categoryId: String = "", custom: List<CustomCategory> = emptyList()): List<LedgerEntry> {
    val query = search.trim().lowercase(Locale.ROOT)
    return entries.filter { it.deletedAt == 0L && (kind == "all" || it.kind == kind) && (categoryId.isEmpty() || it.categoryId == categoryId) &&
        (query.isEmpty() || (it.title + it.note + categoryById(it.categoryId, custom).name + decimalAmount(it.amountMinor) + it.occurredOn).lowercase(Locale.ROOT).contains(query))
    }.sortedWith(compareByDescending<LedgerEntry> { it.occurredOn }.thenByDescending { it.createdAt })
}
fun validateEntry(entry: LedgerEntry, custom: List<CustomCategory> = emptyList()): String = when {
    !Regex("^[a-zA-Z0-9_-]{1,80}$").matches(entry.id) -> "账目编号不正确"
    entry.kind !in KINDS -> "请选择收支类型"
    entry.amountMinor !in 1..MAX_AMOUNT -> "请输入有效金额，最多两位小数"
    entry.currency != "CNY" -> "当前版本支持人民币记账"
    entry.title.isBlank() || entry.title.length > 80 -> "请输入80字以内的账目名称"
    categoriesForKind(entry.kind, custom).none { it.id == entry.categoryId } -> "该分类不适用于当前收支类型"
    !isDateKey(entry.occurredOn) -> "请选择有效日期"
    entry.note.length > 500 -> "备注最多500字"
    entry.timeHint.length > 40 -> "时间描述格式不正确"
    entry.source !in listOf("voice", "manual", "text", "sample", "import") -> "账目来源不正确"
    entry.createdAt !in 1..MAX_SAFE_INTEGER || entry.updatedAt !in 1..MAX_SAFE_INTEGER || entry.deletedAt !in 0..MAX_SAFE_INTEGER -> "账目时间格式不正确"
    else -> ""
}
private val keywords = mapOf(
    "food" to "饭,餐,面,粉,菜,奶茶,咖啡,水果,外卖", "transport" to "打车,地铁,公交,加油,停车,出租车,火车",
    "shopping" to "衣服,鞋,包,买,网购,淘宝", "daily" to "超市,日用品,纸巾,洗发,牙膏,生活用品",
    "housing" to "房租,水费,电费,物业,燃气,宽带", "entertainment" to "电影,游戏,唱歌,会员,演出",
    "health" to "医院,看病,药,体检,牙医", "education" to "书,学费,课程,培训,考试",
    "travel" to "旅游,酒店,机票,景点,旅行", "social" to "红包,礼物,份子,随礼,请客",
    "salary" to "工资,薪水,发薪", "bonus" to "奖金,年终奖", "freelance" to "兼职,稿费,接单", "investment" to "利息,理财,分红"
)
fun suggestedCategories(kind: String, selected: String, description: String, title: String, entries: List<LedgerEntry>, custom: List<CustomCategory> = emptyList(), limit: Int = 3): List<Category> {
    val available = categoriesForKind(kind, custom)
    val current = available.find { it.id == selected } ?: available.first()
    val text = "$title $description".lowercase(Locale.ROOT)
    fun score(category: Category): Int {
        var points = if (text.contains(category.name.lowercase(Locale.ROOT))) 30 else 0
        points += keywords[category.id]?.split(',')?.count { text.contains(it) }?.times(6) ?: 0
        val history = entries.filter { it.deletedAt == 0L && categoryScope(it.kind) == categoryScope(kind) && it.categoryId == category.id }
        points += history.size.coerceAtMost(6)
        if (title.isNotBlank() && history.any { it.title.trim().equals(title.trim(), true) }) points += 40
        return points
    }
    return (listOf(current) + available.filter { it.id != current.id }.sortedByDescending { score(it) }).take(limit.coerceAtLeast(1))
}

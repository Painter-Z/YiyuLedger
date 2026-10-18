package com.yiyu.ledger.ui

import android.graphics.Paint
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yiyu.ledger.data.Book
import com.yiyu.ledger.domain.*

@Composable fun StatsPage(book: Book, disabled: Boolean, onBudget: (MonthlyBudget, () -> Unit) -> Unit, onFilter: (LedgerFilter) -> Unit) {
    var mode by rememberSaveable { mutableStateOf("month") }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var kind by rememberSaveable { mutableStateOf("expense") }
    var editingBudgetMonth by rememberSaveable { mutableStateOf<String?>(null) }
    val period = periodFor(mode, today(), offset)
    val summary = summarize(book.entries, period)
    val points = trendPoints(book.entries, period)
    val rankings = categoryTotals(book.entries, period, kind, book.categories)
    val budgetMonth = budgetMonthFor(mode, period, today())
    Column(Modifier.fillMaxSize().testTag("stats_page").verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth().background(Field, RoundedCornerShape(14.dp)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("week" to "周", "month" to "月", "year" to "年").forEach { (value, text) ->
                FilterChip(selected = mode == value, onClick = { mode = value; offset = 0 }, label = { Text(text, Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }, shape = RoundedCornerShape(11.dp), border = null,
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Card, selectedLabelColor = Forest, containerColor = Color.Transparent, labelColor = Muted), modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("stats_mode_$value"))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = { if (periodFor(mode, today(), offset - 1).start >= "1900-01-01") offset-- }) { Icon(Icons.Outlined.ChevronLeft, "上一周期") }
            TextButton(onClick = { offset = 0 }, modifier = Modifier.weight(1f).testTag("stats_period")) { Text(period.label, textAlign = TextAlign.Center, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
            IconButton(onClick = { if (periodFor(mode, today(), offset + 1).end <= "2199-12-31") offset++ }) { Icon(Icons.Outlined.ChevronRight, "下一周期") }
        }
        Panel(Modifier.testTag("stats_summary_card").border(1.dp, Outline, RoundedCornerShape(24.dp))) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatsAmount("净支出", summary.netExpense, Forest, "expense", Modifier.weight(1f)) { onFilter(LedgerFilter("expense", period = period)) }
                StatsAmount("收入", summary.income, IncomeColor, "income", Modifier.weight(1f)) { onFilter(LedgerFilter("income", period = period)) }
            }
            HorizontalDivider(color = Outline)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("收支结余", color = Muted, fontSize = 12.sp)
                Text("¥ ${formatMoney(summary.balance)}", Modifier.weight(1f).testTag("stats_balance_amount"), color = Ink,
                    autoSize = TextAutoSize.StepBased(minFontSize = with(LocalDensity.current) { 8.dp.toSp() }, maxFontSize = 16.sp, stepSize = .5.sp),
                    maxLines = 1, textAlign = TextAlign.End)
            }
            Text("支出 ${formatMoney(summary.expense)} · 退款 ${formatMoney(summary.refund)} · 共${summary.count}笔", color = Muted, fontSize = 11.sp)
        }
        MonthlyBudgetCard(monthlyBudgetProgress(book.entries, book.monthlyBudgets, budgetMonth)) { if(!disabled) editingBudgetMonth = budgetMonth }
        Panel {
            SectionTitle("收支趋势")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Legend("支出", ExpenseColor); Legend("收入", IncomeColor); Legend("退款", RefundColor) }
            if (summary.expense + summary.income + summary.refund == 0L) EmptyState("这一周期还没有收支", "记录后会自动生成趋势图", Icons.Outlined.BarChart)
            else {
                TrendChart(points) { point ->
                    val bucket = if (mode == "year") periodFor("month", point.date) else Period(point.date, point.date, point.date, "day")
                    onFilter(LedgerFilter(period = bucket, dateFilter = if (mode == "year") point.date.take(7) else point.date))
                }
                Text("点按图表查看对应账单", color = Muted, fontSize = 11.sp)
            }
        }
        Panel {
            SectionTitle("分类分析")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("expense", "income", "refund").forEach { value -> FilterChip(selected = kind == value, onClick = { kind = value }, label = { Text(kindName(value)) }, border = null, shape = RoundedCornerShape(18.dp),
                    colors = FilterChipDefaults.filterChipColors(containerColor = Field, selectedContainerColor = Sage), modifier = Modifier.heightIn(min = 48.dp)) }
            }
            if (rankings.isEmpty()) Text("暂无${kindName(kind)}记录", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 16.dp))
            rankings.forEach { total ->
                Column(Modifier.fillMaxWidth().clickable { onFilter(LedgerFilter(kind, total.category.id, period)) }.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val stacked = maxWidth.value / LocalDensity.current.fontScale < 280 || formatMoney(total.amount).length > 12
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(categoryIcon(total.category.icon), null, tint = hexColor(total.category.color), modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                        Text(total.category.name, Modifier.weight(1f), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${(total.ratio * 100).toInt()}%", color = Muted, fontSize = 12.sp)
                        if (!stacked) {
                            Spacer(Modifier.width(12.dp))
                            MoneyText(total.amount, Modifier.weight(.85f), prefix = "¥ ", textAlign = TextAlign.End)
                        }
                    }
                    if (stacked) MoneyText(total.amount, Modifier.fillMaxWidth(), prefix = "¥ ", textAlign = TextAlign.End)
                    }
                    }
                    LinearProgressIndicator(progress = { total.ratio.toFloat() }, modifier = Modifier.fillMaxWidth().height(6.dp), color = hexColor(total.category.color), trackColor = Field, drawStopIndicator = {})
                }
            }
            Text("退款按发生日期单列；转账、往来不计入普通收支。", color = Muted, fontSize = 11.sp)
        }
    }
    editingBudgetMonth?.let { month -> BudgetDialog(month, budgetLimit(book.monthlyBudgets, month), disabled, onClose = { editingBudgetMonth = null }, onSave = onBudget) }
}

@Composable private fun StatsAmount(label: String, amount: Long, color: Color, id: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.fillMaxWidth().clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(formatMoney(amount), Modifier.fillMaxWidth().testTag("stats_${id}_amount"), color = color,
            autoSize = TextAutoSize.StepBased(minFontSize = with(LocalDensity.current) { 8.dp.toSp() }, maxFontSize = if (id == "expense") 29.sp else 24.sp, stepSize = .5.sp),
            fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
@Composable private fun Legend(label: String, color: Color) { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) { Box(Modifier.size(7.dp).background(color, RoundedCornerShape(3.dp))); Text(label, color = Muted, fontSize = 11.sp) } }
@Composable private fun TrendChart(points: List<TrendPoint>, onPoint: (TrendPoint) -> Unit) {
    val maximum = points.maxOf { maxOf(it.expense, it.income, it.refund) }.coerceAtLeast(1).toFloat()
    val description = points.joinToString("；") { "${it.label}：支出${decimalAmount(it.expense)}元，收入${decimalAmount(it.income)}元，退款${decimalAmount(it.refund)}元" }
    Canvas(Modifier.fillMaxWidth().height(200.dp).testTag("stats_trend").semantics { contentDescription = description }.pointerInput(points) {
        detectTapGestures { position -> val index = (position.x / (size.width.toFloat() / points.size)).toInt().coerceIn(points.indices); onPoint(points[index]) }
    }) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Muted.toArgb(); textSize = 10.sp.toPx(); textAlign = Paint.Align.CENTER }
        val bottom = size.height - (paint.fontMetrics.bottom - paint.fontMetrics.top) - 12.dp.toPx()
        val top = 16.dp.toPx()
        val step = size.width / points.size
        val width = (step * .18f).coerceAtMost(12.dp.toPx())
        val widestLabel = points.maxOf { paint.measureText(it.label) }
        val labelStride = kotlin.math.ceil(points.size * (widestLabel + 10.dp.toPx()) / size.width).toInt().coerceAtLeast(1)
        val lastHalf = paint.measureText(points.last().label) / 2
        val lastCenter = (step * (points.lastIndex + .5f)).coerceIn(lastHalf, size.width - lastHalf)
        repeat(4) { index -> val y = top + (bottom - top) * index / 3; drawLine(Field, Offset(0f, y), Offset(size.width, y), 1.dp.toPx()) }
        points.forEachIndexed { index, point ->
            val center = step * (index + .5f)
            listOf(point.expense to ExpenseColor, point.income to IncomeColor, point.refund to RefundColor).forEachIndexed { series, (amount, color) ->
                if (amount > 0) { val height = (amount / maximum) * (bottom - top); drawRoundRect(color, Offset(center + (series - 1) * (width + 1.dp.toPx()) - width / 2, bottom - height), Size(width, height.coerceAtLeast(2.dp.toPx())), CornerRadius(3.dp.toPx())) }
            }
            val half = paint.measureText(point.label) / 2
            val labelCenter = center.coerceIn(half, size.width - half)
            if (index == points.lastIndex || index % labelStride == 0 && labelCenter + half + 6.dp.toPx() <= lastCenter - lastHalf)
                drawIntoCanvas { it.nativeCanvas.drawText(point.label, labelCenter, size.height - 4.dp.toPx() - paint.fontMetrics.bottom, paint) }
        }
    }
}

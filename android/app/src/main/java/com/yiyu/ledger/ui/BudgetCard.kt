package com.yiyu.ledger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.RoundedCornerShape
import com.yiyu.ledger.domain.*
import java.util.Locale

@Composable fun MonthlyBudgetCard(progress: BudgetProgress, onSetup: () -> Unit) {
    Panel(Modifier.testTag("monthly_budget_card").border(1.dp, Outline, RoundedCornerShape(24.dp))) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${progress.month.take(4)}年${progress.month.takeLast(2).toInt()}月预算", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onSetup, shape = RoundedCornerShape(28.dp), colors = ButtonDefaults.textButtonColors(containerColor = Field, contentColor = Forest),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), modifier = Modifier.heightIn(min = 48.dp).testTag("setup_monthly_budget")) { Text(if (progress.limit > 0) "调整" else "设置", fontSize = 12.sp) }
        }
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val diameter = minOf(208.dp, maxWidth)
            val strokeWidth = minOf(22.dp, diameter * .11f)
            val minimumAmountSize = with(LocalDensity.current) { 8.dp.toSp() }
            val minimumLabelSize = with(LocalDensity.current) { 10.dp.toSp() }
            val labelSize = minOf(12f, with(LocalDensity.current) { 16.dp.toSp().value }).sp
            val percentSize = minOf(11f, with(LocalDensity.current) { 14.dp.toSp().value }).sp
            Box(Modifier.size(diameter).testTag("monthly_budget_ring"), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = Stroke(strokeWidth.toPx(), cap = StrokeCap.Round)
                    val inset = 6.dp.toPx() + stroke.width / 2
                    val origin = Offset(inset, inset)
                    val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                    drawArc(Sage, -90f, 360f, false, topLeft = origin, size = arcSize, style = stroke)
                    if (progress.baseRatio > 0) drawArc(ExpenseColor, -90f, (progress.baseRatio * 360).toFloat(), false, topLeft = origin, size = arcSize, style = stroke)
                    if (progress.overRatio > 0) drawArc(ErrorColor, -90f, (progress.overRatio * 360).toFloat(), false, topLeft = origin, size = arcSize, style = stroke)
                }
                Column(Modifier.width(diameter - strokeWidth * 2 - 20.dp).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val label = when(progress.state) { "over" -> "已超支"; "reached" -> "额度已用完"; "available" -> "剩余可用"; else -> "设置月预算" }
                    Text(label, color = if(progress.state == "over") ErrorColor else Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag("budget_state"),
                        autoSize = TextAutoSize.StepBased(minFontSize = minimumLabelSize, maxFontSize = labelSize, stepSize = .5.sp), maxLines = 1)
                    if (progress.limit > 0) {
                        Text("¥ " + formatMoney(if(progress.state == "over") progress.overspent else progress.remaining),
                            Modifier.fillMaxWidth().testTag("budget_remaining"), color = if(progress.state == "over") ErrorColor else Forest,
                            autoSize = TextAutoSize.StepBased(minFontSize = minimumAmountSize, maxFontSize = 26.sp, stepSize = .5.sp), maxLines = 1, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
                        Text(if(progress.usedRatio > 99.99) "已用超过9999%" else "已用 " + String.format(Locale.ROOT, "%.1f", progress.usedRatio * 100).removeSuffix(".0") + "%",
                            color = Muted, modifier = Modifier.fillMaxWidth().testTag("budget_used_percent"), textAlign = TextAlign.Center,
                            autoSize = TextAutoSize.StepBased(minFontSize = minimumLabelSize, maxFontSize = percentSize, stepSize = .5.sp), maxLines = 1)
                    } else Text("让花费心中有数", color = Muted, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                        autoSize = TextAutoSize.StepBased(minFontSize = minimumLabelSize, maxFontSize = percentSize, stepSize = .5.sp), maxLines = 1)
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stacked = maxWidth.value / LocalDensity.current.fontScale < 275 || maxOf(formatMoney(progress.spent).length, formatMoney(progress.limit).length) > 12
            if (stacked) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BudgetMetric("每月预算", progress.limit.takeIf { it > 0 }, "budget_limit"); BudgetMetric("已消费", progress.spent, "budget_spent")
            } else Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BudgetMetric("每月预算", progress.limit.takeIf { it > 0 }, "budget_limit", Modifier.weight(1f))
                BudgetMetric("已消费", progress.spent, "budget_spent", Modifier.weight(1f))
            }
        }
        Text("当月退款抵减消费；收入、转账和往来不占预算。", color = Muted, fontSize = 11.sp)
    }
}
@Composable private fun BudgetMetric(label: String, amount: Long?, tag: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Muted, fontSize = 12.sp)
        if(amount == null) Text("尚未设置", fontSize = 18.sp, modifier = Modifier.testTag(tag))
        else MoneyText(amount, Modifier.fillMaxWidth().testTag(tag), prefix = "¥ ")
    }
}
@Composable fun BudgetDialog(month: String, limit: Long, disabled: Boolean, onClose: () -> Unit, onSave: (MonthlyBudget, () -> Unit) -> Unit) {
    var input by rememberSaveable(month) { mutableStateOf(if(limit > 0) decimalAmount(limit) else "") }
    var error by rememberSaveable(month) { mutableStateOf("") }
    Dialog(onDismissRequest = { if(!disabled) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val focus = LocalFocusManager.current
        fun save(amount: Long) { focus.clearFocus(); onSave(MonthlyBudget(month, amount), onClose) }
        BoxWithConstraints(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.windowInsetsPadding(WindowInsets.safeDrawing).imePadding(), contentAlignment = Alignment.BottomCenter) {
            Surface(Modifier.widthIn(max = 600.dp).fillMaxWidth().heightIn(max = maxHeight), color = Cream, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("设置月预算", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = onClose, enabled = !disabled) { Icon(Icons.Outlined.Close, "关闭预算设置") }
                    }
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("${month.take(4)}年${month.takeLast(2).toInt()}月起生效", color = Forest)
                        OutlinedTextField(input, { if(it.length <= 13) { input = it; error = "" } }, Modifier.fillMaxWidth().testTag("monthly_budget_input"),
                            label = { Text("每月消费额度（元）") }, prefix = { Text("¥") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = !disabled,
                            isError = error.isNotEmpty(), supportingText = { if(error.isNotEmpty()) Text(error, color = ErrorColor) })
                        Text("后续月份自动沿用，直到你再次调整。已单独设置过的月份保持自己的额度。", color = Muted, fontSize = 13.sp)
                    }
                    Button(onClick = {
                        val amount = parseMinor(input)
                        if(amount <= 0) error = "请输入大于0且最多两位小数的金额" else save(amount)
                    }, enabled = !disabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("save_monthly_budget")) { Text(if(disabled) "正在保存…" else "保存预算") }
                    if(limit > 0) TextButton(onClick = { save(0) }, enabled = !disabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("从该月起关闭预算", color = Muted) }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

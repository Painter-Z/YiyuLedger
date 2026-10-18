package com.yiyu.ledger.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import com.yiyu.ledger.VoiceUi
import com.yiyu.ledger.domain.*
import kotlin.math.abs

fun categoryIcon(id: String): ImageVector = when(id) {
    "food" -> Icons.Outlined.Restaurant; "car" -> Icons.Outlined.DirectionsCar; "bag" -> Icons.Outlined.ShoppingBag
    "basket" -> Icons.Outlined.ShoppingBasket; "home" -> Icons.Outlined.Home; "play" -> Icons.Outlined.LiveTv
    "heart" -> Icons.Outlined.FavoriteBorder; "book" -> Icons.AutoMirrored.Outlined.MenuBook; "plane" -> Icons.Outlined.Flight
    "gift" -> Icons.Outlined.CardGiftcard; "wallet" -> Icons.Outlined.AccountBalanceWallet; "briefcase" -> Icons.Outlined.WorkOutline
    "trend" -> Icons.AutoMirrored.Outlined.TrendingUp; "swap" -> Icons.Outlined.SwapHoriz; else -> Icons.Outlined.MoreHoriz
}
fun hexColor(text: String) = Color(text.toColorInt())
@Composable fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = Card) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}
@Composable fun SectionTitle(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        if (action != null) TextButton(onClick = onAction) { Text(action, color = Muted) }
    }
}
@Composable fun EmptyState(title: String, subtitle: String, icon: ImageVector = Icons.AutoMirrored.Outlined.ReceiptLong) {
    Column(Modifier.fillMaxWidth().padding(vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = Muted, modifier = Modifier.size(36.dp))
        Text(title, color = Ink, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}
@Composable fun MoneyText(amount: Long, modifier: Modifier = Modifier, color: Color = Ink, large: Boolean = false,
    prefix: String = "", textAlign: TextAlign = TextAlign.Start) {
    Text(prefix + formatMoney(amount), modifier = modifier, color = color, textAlign = textAlign,
        autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = if (large) 44.sp else 18.sp, stepSize = 1.sp),
        fontWeight = FontWeight.SemiBold, maxLines = 1)
}
@Composable fun LedgerRow(entry: LedgerEntry, custom: List<CustomCategory>, onClick: () -> Unit) {
    val category = categoryById(entry.categoryId, custom)
    val sign = when(entry.kind) { "income", "refund" -> "+"; "expense" -> "−"; else -> "" }
    val amountColor = when(entry.kind) { "income" -> IncomeColor; "refund" -> RefundColor; else -> Ink }
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("ledger_row_${entry.id}").clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 12.dp)) {
        val stacked = maxWidth.value / LocalDensity.current.fontScale < 280 || formatMoney(entry.amountMinor).length > 12
        Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(16.dp)).background(hexColor(category.background)), contentAlignment = Alignment.Center) {
            Icon(categoryIcon(category.icon), null, tint = hexColor(category.color), modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, modifier = Modifier.testTag("ledger_title_${entry.id}"), fontSize = 16.sp, maxLines = if (stacked) 2 else 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(category.name + " · " + entry.occurredOn + if (entry.note.isNotBlank()) " · " + entry.note else "", fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (stacked) MoneyText(entry.amountMinor, Modifier.fillMaxWidth().padding(top = 6.dp).testTag("ledger_amount_${entry.id}"), amountColor, prefix = sign, textAlign = TextAlign.End)
        }
        if (!stacked) {
            Spacer(Modifier.width(8.dp))
            MoneyText(entry.amountMinor, Modifier.weight(.65f).testTag("ledger_amount_${entry.id}"), amountColor, prefix = sign, textAlign = TextAlign.End)
        }
        }
    }
}
@Composable fun CategoryChip(category: Category, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, modifier = modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(16.dp), color = if (selected) Sage else Field) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(categoryIcon(category.icon), null, Modifier.size(18.dp), tint = if (selected) Forest else Muted)
            Text(category.name, color = if (selected) Forest else Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
@Composable fun KindSelector(kind: String, onSelect: (String) -> Unit, includeAll: Boolean = false, tagPrefix: String = "") {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (if (includeAll) listOf("all") + KINDS else KINDS).forEach { value ->
            FilterChip(selected = value == kind, onClick = { onSelect(value) }, label = { Text(if(value == "all") "全部" else kindName(value)) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Forest, selectedLabelColor = OnForest, containerColor = Field),
                border = null, shape = RoundedCornerShape(24.dp), modifier = Modifier.heightIn(min = 48.dp).then(if (tagPrefix.isEmpty()) Modifier else Modifier.testTag("${tagPrefix}_$value")))
        }
    }
}
@Composable fun VoiceButton(voice: VoiceUi, enabled: Boolean, onStart: () -> Unit, onEnd: (Boolean) -> Unit) {
    var cancel by remember { mutableStateOf(false) }
    val currentStart by rememberUpdatedState(onStart)
    val currentEnd by rememberUpdatedState(onEnd)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentVoice by rememberUpdatedState(voice)
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val active = voice.capturing
    val background = if (active && cancel) ErrorColor else if (active || voice.active) Forest else Sage
    val foreground = if (active || voice.active) OnForest else Forest
    Surface(shape = RoundedCornerShape(32.dp), color = background, modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp)
        .semantics {
            contentDescription = if (active) "正在录音，松手识别，上滑取消" else "按住说话，上滑取消，最多30秒"
            role = Role.Button
            onClick(label = if (active) "结束录音" else "开始录音") {
                if (currentVoice.capturing) currentEnd(false) else if (currentEnabled) currentStart()
                true
            }
        }.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                if (!currentEnabled) return@awaitEachGesture
                cancel = false
                currentStart()
                var released = false
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id }
                        event.changes.forEach { it.consume() }
                        if (pointer != null) cancel = pointer.position.y - down.position.y < -threshold
                        if (pointer == null || !pointer.pressed) { released = true; currentEnd(cancel); break }
                    }
                } finally { if (!released) currentEnd(true); cancel = false }
            }
        }) {
        Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).background(if (active || voice.active) OnForest.copy(alpha = .12f) else Forest, RoundedCornerShape(26.dp)), contentAlignment = Alignment.Center) {
                Icon(if (active && cancel) Icons.Outlined.Close else Icons.Outlined.MicNone, null, Modifier.size(28.dp), tint = OnForest)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f, fill = false), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (active) if (cancel) "松手取消" else "正在聆听 · ${voice.seconds}s" else if (voice.active) "正在识别…" else "按住说话", color = foreground, fontSize = 19.sp, fontWeight = FontWeight.Medium)
                Text(if (active) "松手识别，上滑取消" else "把每一笔，轻轻说出来", color = if (active || voice.active) OnForestMuted else Muted, fontSize = 12.sp)
            }
        }
    }
}

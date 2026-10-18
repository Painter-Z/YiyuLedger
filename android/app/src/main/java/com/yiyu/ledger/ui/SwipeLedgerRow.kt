package com.yiyu.ledger.ui

import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yiyu.ledger.domain.CustomCategory
import com.yiyu.ledger.domain.LedgerEntry
import com.yiyu.ledger.domain.decimalAmount
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

/** Two anchors reveal a button; dragging never dismisses or deletes a financial record. */
@Composable fun SwipeLedgerRow(entry: LedgerEntry, custom: List<CustomCategory>, openId: String?, disabled: Boolean,
    onOpen: () -> Unit, onClose: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val density = LocalDensity.current
    val actionWidth = (65 * density.fontScale).coerceIn(76f, 120f).dp
    val gap = 16.dp
    val actionPixels = with(density) { (actionWidth + gap).toPx() }
    val state = remember(entry.id) { AnchoredDraggableState(false) }
    val anchors = remember(actionPixels) { DraggableAnchors { false at 0f; true at -actionPixels } }
    SideEffect { state.updateAnchors(anchors) }
    val currentOpen by rememberUpdatedState(onOpen)
    LaunchedEffect(state) {
        snapshotFlow { state.offset.isFinite() && state.offset < -1f }.distinctUntilChanged().collect { if (it) currentOpen() }
    }
    LaunchedEffect(openId, disabled) { if (openId != entry.id || disabled) state.animateTo(false) }
    val shifted by remember(state) { derivedStateOf { state.offset.isFinite() && state.offset < -1f } }
    Box(Modifier.fillMaxWidth().clip(RectangleShape).testTag("swipe_row_${entry.id}")
        .semantics { customActions = listOf(CustomAccessibilityAction("删除这笔账单") { if (!disabled) onDelete(); !disabled }) }) {
        if (shifted) {
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                Row(Modifier.width(actionWidth + gap).fillMaxHeight()) {
                    Spacer(Modifier.width(gap).fillMaxHeight().testTag("swipe_gap_${entry.id}"))
                    Surface(onClick = { onClose(); onDelete() }, enabled = !disabled, color = ErrorColor, shape = RectangleShape,
                        modifier = Modifier.width(actionWidth).fillMaxHeight().testTag("swipe_delete_${entry.id}")
                            .semantics { contentDescription = "删除${entry.title}，${decimalAmount(entry.amountMinor)}元" }) {
                        Box(contentAlignment = Alignment.Center) { Text("删除", color = OnForest, fontSize = 14.sp) }
                    }
                }
            }
        }
        Surface(shape = RoundedCornerShape(18.dp), color = Card,
            modifier = Modifier.fillMaxWidth().offset { IntOffset((state.offset.takeIf { it.isFinite() } ?: 0f).roundToInt(), 0) }
                .anchoredDraggable(state, Orientation.Horizontal, enabled = !disabled)) {
            Box(Modifier.padding(horizontal = 14.dp)) {
                LedgerRow(entry, custom) { if (shifted) onClose() else if (!disabled) onEdit() }
            }
        }
    }
}

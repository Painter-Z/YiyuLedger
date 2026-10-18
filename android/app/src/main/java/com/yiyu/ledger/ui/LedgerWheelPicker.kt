package com.yiyu.ledger.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.abs

/** Five visible rows with snapping and fading edges, independent of the vendor date widget. */
class LedgerWheelPicker(context: Context, initialRowHeight: Dp = 44.dp) : AbstractComposeView(context) {
    private var selectedDate by mutableStateOf(LocalDate.now())
    var rowHeight by mutableStateOf(initialRowHeight)
    var onDateChanged: (String) -> Unit = {}
    val date: LocalDate get() = selectedDate

    fun updateDate(value: LocalDate) {
        require(value.year in 1900..2199)
        if (value == selectedDate) return
        selectedDate = value
        onDateChanged(value.toString())
    }

    @Composable override fun Content() {
        val shownDate = selectedDate
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                Box(Modifier.fillMaxWidth().height(rowHeight).align(Alignment.Center)
                    .background(Field, RoundedCornerShape(12.dp)))
                key(rowHeight) {
                    Row(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(Brush.verticalGradient(
                                0f to Color.Transparent, .14f to Color.Black.copy(alpha = .55f),
                                .28f to Color.Black, .72f to Color.Black,
                                .86f to Color.Black.copy(alpha = .55f), 1f to Color.Transparent
                            ), blendMode = BlendMode.DstIn)
                        }) {
                        DateWheel(shownDate.year, 1900..2199, "年", "year", rowHeight,
                            Modifier.weight(1f)) { updateDate(date.withYear(it)) }
                        DateWheel(shownDate.monthValue, 1..12, "月", "month", rowHeight,
                            Modifier.weight(1f)) { updateDate(date.withMonth(it)) }
                        DateWheel(shownDate.dayOfMonth, 1..shownDate.lengthOfMonth(), "日", "day", rowHeight,
                            Modifier.weight(1f)) { updateDate(date.withDayOfMonth(it.coerceAtMost(date.lengthOfMonth()))) }
                    }
                }
            }
        }
    }
}

@Composable private fun DateWheel(
    value: Int,
    range: IntRange,
    suffix: String,
    tag: String,
    rowHeight: Dp,
    modifier: Modifier,
    onSelected: (Int) -> Unit
) {
    val values = remember(range) { range.toList() }
    // The dialog saves the date. A separately restored list position could
    // otherwise replace that date when the picker is reopened or rotated.
    // A changed row height invalidates pixel scroll offsets as well.
    val state = remember(rowHeight) { LazyListState(firstVisibleItemIndex = value - range.first) }
    val scope = rememberCoroutineScope()
    val currentOnSelected by rememberUpdatedState(onSelected)
    var lastReported by remember(state) { mutableIntStateOf(value) }
    var synchronizing by remember(state) { mutableStateOf(false) }
    val rowPixels = with(LocalDensity.current) { rowHeight.roundToPx() }
    val centeredIndex by remember(state, rowPixels) {
        derivedStateOf {
            val layout = state.layoutInfo
            val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2f
            // During a resize LazyColumn can briefly contain measurements from
            // both row heights. Never turn that intermediate layout into a date.
            if (abs(layout.viewportSize.height - rowPixels * 5) > 2 ||
                layout.visibleItemsInfo.any { it.size != rowPixels }) null
            else layout.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2f - center) }?.index
        }
    }
    // External date changes (including a shorter month) move the wheel. Values
    // reported by this wheel never restart its ongoing drag or fling.
    LaunchedEffect(state, value, range) {
        if (value != lastReported) {
            synchronizing = true
            lastReported = value
            state.scrollToItem((value - range.first).coerceIn(values.indices))
            synchronizing = false
        }
    }
    LaunchedEffect(state, range) {
        var wasScrolling = false
        snapshotFlow {
            val index = centeredIndex
            val layout = state.layoutInfo
            val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
            if (synchronizing || index == null || item == null) null else Triple(
                (index + range.first).coerceIn(range), state.isScrollInProgress,
                item.offset + item.size / 2f - (layout.viewportStartOffset + layout.viewportEndOffset) / 2f
            )
        }.filterNotNull().distinctUntilChanged().collect { (centered, scrolling, offset) ->
            if (scrolling || wasScrolling) {
                if (centered != lastReported) {
                    lastReported = centered
                    currentOnSelected(centered)
                }
            } else if (centered != lastReported || abs(offset) > 1f) {
                // Remeasuring a short window may shift a resting list. Keep
                // the chosen date and restore its position, rather than
                // accepting a layout movement as a user's date change.
                synchronizing = true
                state.scrollToItem((lastReported - range.first).coerceIn(values.indices))
                synchronizing = false
            }
            wasScrolling = scrolling
        }
    }
    LazyColumn(
        modifier.fillMaxHeight().testTag("ledger_${tag}_wheel").semantics {
            contentDescription = suffix
            stateDescription = "$value$suffix"
        },
        state = state,
        contentPadding = PaddingValues(vertical = rowHeight * 2),
        horizontalAlignment = Alignment.CenterHorizontally,
        flingBehavior = rememberSnapFlingBehavior(state),
        overscrollEffect = null
    ) {
        items(values, key = { it }) { item ->
            val isSelected = item - range.first == centeredIndex
            Box(Modifier.fillMaxWidth().height(rowHeight).testTag("ledger_${tag}_value_$item")
                .semantics { selected = isSelected }
                .clickable(indication = null, interactionSource = null) {
                    scope.launch { state.animateScrollToItem(item - range.first) }
                }, contentAlignment = Alignment.Center) {
                Text("$item$suffix", fontSize = 18.sp,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    color = if (isSelected) Forest else Muted, maxLines = 1)
            }
        }
    }
}

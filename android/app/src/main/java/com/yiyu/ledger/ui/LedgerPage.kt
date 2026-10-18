package com.yiyu.ledger.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yiyu.ledger.data.Book
import com.yiyu.ledger.domain.*
import kotlinx.coroutines.flow.distinctUntilChanged

val LedgerBrowseSaver = listSaver<LedgerBrowseOptions, String>(
    save = { listOf(it.today, it.search, it.kind, it.categoryId, it.dateFilter, it.rangeStart, it.rangeEnd) },
    restore = { LedgerBrowseOptions(it[0], it[1], it[2], it[3], it[4], it[5], it[6]) }
)

@Composable fun LedgerPage(book: Book, options: LedgerBrowseOptions, onOptions: (LedgerBrowseOptions) -> Unit,
    onEdit: (LedgerEntry) -> Unit, disabled: Boolean, onDelete: (LedgerEntry) -> Unit) {
    var openId by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<LedgerEntry?>(null) }
    var pickerDate by rememberSaveable { mutableStateOf<String?>(null) }
    var limit by remember(options) { mutableIntStateOf(80) }
    val listState = rememberLazyListState()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val result = remember(book.entries, book.categories, options) { browseLedger(book.entries, options, book.categories) }
    val days = remember(result, limit) { ledgerDayPage(result, limit) }
    fun finishSearch() { focus.clearFocus(); keyboard?.hide(); openId = null }
    fun change(value: LedgerBrowseOptions) { openId = null; onOptions(value) }
    LaunchedEffect(options) { openId = null; listState.scrollToItem(0) }
    LaunchedEffect(book.entries) { openId = null }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.distinctUntilChanged().collect { if (it) openId = null }
    }
    LaunchedEffect(listState, result, limit) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.key == "load_more" }.distinctUntilChanged().collect {
            if (it && limit < result.entries.size) limit += 80
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val padding = if (maxWidth < 360.dp) 16.dp else 24.dp
        val innerWidth = maxWidth - padding * 2
        val narrowSearch = maxWidth < 330.dp
        LazyColumn(Modifier.fillMaxSize().testTag("ledger_page"), state = listState,
            contentPadding = PaddingValues(start = padding, end = padding, top = 12.dp, bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "controls") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(color = Field, shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth().testTag("ledger_search_bar")) {
                        Row(Modifier.padding(horizontal = 8.dp).heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                finishSearch()
                                pickerDate = options.dateFilter.takeIf { it.length == 10 }
                                    ?: options.dateFilter.takeIf { it.length == 7 }?.let { "$it-01" }
                                    ?: options.rangeStart.takeIf { it.isNotEmpty() } ?: options.today
                            }, modifier = Modifier.testTag("ledger_date_button").size(44.dp)) {
                                Icon(Icons.Outlined.CalendarMonth, "选择日期查看账单", tint = if (options.dateFilter.isNotEmpty() || options.rangeStart.isNotEmpty()) Forest else Muted, modifier = Modifier.size(19.dp))
                            }
                            BasicTextField(options.search, { change(options.copy(search = it.take(200))) },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("ledger_search"), singleLine = true,
                                textStyle = TextStyle(color = Ink, fontSize = 13.sp), cursorBrush = SolidColor(Forest),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { finishSearch() }),
                                decorationBox = { field -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                                    if (options.search.isEmpty()) Text(if (narrowSearch) "搜索账单" else "搜索名称、分类、金额", color = Muted, fontSize = 13.sp)
                                    field()
                                } })
                            if (options.search.isNotEmpty()) IconButton(onClick = { change(options.copy(search = "")) }, modifier = Modifier.size(32.dp).testTag("ledger_clear_search")) {
                                Icon(Icons.Outlined.Close, "清空搜索", tint = Muted, modifier = Modifier.size(16.dp))
                            }
                            IconButton(onClick = { finishSearch() }, modifier = Modifier.size(44.dp).background(Sage, CircleShape).testTag("ledger_search_button")) {
                                Icon(Icons.Outlined.Search, "搜索账单", tint = Forest, modifier = Modifier.size(19.dp))
                            }
                        }
                    }
                    KindSelector(options.kind, { change(options.copy(kind = it)) }, includeAll = true, tagPrefix = "ledger_kind")
                    Text(result.scope.label + options.categoryId.takeIf { it.isNotEmpty() }?.let { " · ${categoryById(it, book.categories).name}" }.orEmpty(),
                        color = Muted, fontSize = 11.sp, maxLines = 2, modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp).testTag("ledger_scope"))
                }
            }
            if (days.isEmpty()) item(key = "empty") { EmptyState("没有找到匹配账单", "试试更换关键词，或点击日历查看其他日期。") }
            days.forEach { day ->
                item(key = "day_${day.date}") { LedgerDayHeader(day, options.today, innerWidth < 260.dp) }
                if (day.entries.isEmpty()) item(key = "empty_${day.date}") {
                    Text(if (options.search.isNotEmpty() || options.categoryId.isNotEmpty()) "当天没有符合筛选条件的账单"
                        else if (options.kind == "all") "当天还没有账单" else "当天没有${kindName(options.kind)}账单",
                        color = Muted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp).testTag("ledger_empty_day"))
                }
                items(day.entries, key = { "bill_${it.id}" }) { entry -> SwipeLedgerRow(entry, book.categories, openId, disabled,
                    onOpen = { openId = entry.id }, onClose = { openId = null }, onEdit = { onEdit(entry) }, onDelete = { openId = null; deleteTarget = entry }) }
            }
            if (limit < result.entries.size) item(key = "load_more") {
                TextButton(onClick = { limit += 80 }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ledger_load_more")) {
                    Text("已显示${minOf(limit, result.entries.size)}/${result.entries.size}笔 · 继续加载", color = Muted, fontSize = 12.sp)
                }
            }
            if ((result.scope.mode != "recent" || options.categoryId.isNotEmpty()) && limit >= result.entries.size) item(key = "return") {
                Button(onClick = { finishSearch(); change(options.resetScope()) }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp).heightIn(min = 54.dp).testTag("ledger_return"),
                    shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Forest, contentColor = OnForest)) { Text("返回", fontSize = 15.sp) }
            }
        }
    }
    pickerDate?.let { initial -> LedgerDateDialog(initial, onClose = { pickerDate = null }, onAccept = { date -> change(options.selectDate(date)); pickerDate = null }) }
    deleteTarget?.let { entry -> AlertDialog(onDismissRequest = { if (!disabled) deleteTarget = null }, title = { Text("删除“${entry.title}”？") },
        text = { Text("将同步更新账单、统计和月预算进度，并清理相关识别缓存。删除后无法撤销，已导出文件保留。") },
        confirmButton = { TextButton(onClick = { onDelete(entry); deleteTarget = null }, enabled = !disabled) { Text("删除", color = ErrorColor) } },
        dismissButton = { TextButton(onClick = { deleteTarget = null }, enabled = !disabled) { Text("取消") } }) }
}

@Composable private fun LedgerDayHeader(day: LedgerDay, today: String, stacked: Boolean) {
    val modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp).testTag("ledger_day_${day.date}")
    val expense = "支出：${formatMoney(day.expenseMinor)}元"
    if (stacked) Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(ledgerDayLabel(day.date, today), color = Forest, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(expense, Modifier.fillMaxWidth().testTag("ledger_daily_expense_${day.date}"), color = Forest, fontSize = 12.sp, maxLines = 1)
    } else Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(ledgerDayLabel(day.date, today), Modifier.weight(1f), color = Forest, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Text(expense, Modifier.weight(1f).testTag("ledger_daily_expense_${day.date}"), color = Forest, fontWeight = FontWeight.Medium,
            autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 12.sp, stepSize = .5.sp), maxLines = 1, textAlign = TextAlign.End)
    }
}

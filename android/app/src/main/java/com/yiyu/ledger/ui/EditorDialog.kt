package com.yiyu.ledger.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yiyu.ledger.*
import com.yiyu.ledger.data.Book
import com.yiyu.ledger.data.pendingResult
import com.yiyu.ledger.domain.*
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalLayoutApi::class)
@Composable fun EditorDialog(current: EditorState, book: Book, disabled: Boolean, model: LedgerViewModel) {
    var expanded by remember(current.entry.id) { mutableStateOf(false) }
    var sourceExpanded by remember(current.entry.id) { mutableStateOf(false) }
    var customDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val fieldColors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Field, focusedContainerColor = Field,
        unfocusedBorderColor = Color.Transparent, focusedBorderColor = Muted)
    fun changeEntry(transform: (LedgerEntry) -> LedgerEntry) = model.changeEditor { it.copy(entry = transform(it.entry)) }
    Dialog(onDismissRequest = model::closeEditor, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val focus = LocalFocusManager.current
        BoxWithConstraints(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.BottomCenter) {
            val compact = maxHeight < 360.dp
            val tight = maxHeight < 180.dp
            val sidePadding = if (maxWidth < 360.dp) 16.dp else 24.dp
            Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight(if (compact) 1f else .97f).testTag("editor_panel"), color = Cream, shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)) {
                Column {
                    if (!tight) Row(Modifier.fillMaxWidth().padding(start = sidePadding, end = 8.dp, top = if (compact) 0.dp else 12.dp, bottom = if (compact) 0.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (current.editing) "这一笔的详情" else "确认这一笔", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = model::closeEditor, enabled = !disabled) { Icon(Icons.Outlined.Close, "关闭确认页面", tint = Muted) }
                    }
                    Column(Modifier.weight(1f).testTag("editor_scroll").verticalScroll(rememberScrollState()).padding(horizontal = sidePadding).padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (current.transcript.isNotBlank()) Surface(onClick = { sourceExpanded = !sourceExpanded }, color = Field, shape = RoundedCornerShape(16.dp)) {
                            Text("“${current.transcript}”", Modifier.padding(14.dp).testTag("editor_source"), color = Muted, fontSize = 13.sp,
                                maxLines = if (sourceExpanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (current.proposalTotal > 1) Text("第${current.proposalNumber}笔 / 共${current.proposalTotal}笔",
                            Modifier.testTag("editor_progress"), color = IncomeColor, fontSize = 12.sp)
                        if (current.issues.isNotEmpty()) Text(current.issues.joinToString("；"), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        KindSelector(current.entry.kind, { kind ->
                            if (!disabled) changeEntry { entry -> entry.copy(kind = kind, categoryId = entry.categoryId.takeIf { id -> categoriesForKind(kind, book.categories).any { it.id == id } } ?: categoriesForKind(kind, book.categories).first().id) }
                        })
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("金额 · 人民币", color = Muted, fontSize = 12.sp)
                            OutlinedTextField(current.amount, { value ->
                                if (value.length <= 13 && Regex("^\\d*(\\.\\d{0,2})?$").matches(value)) model.changeEditor { it.copy(amount = value) }
                            }, Modifier.fillMaxWidth().testTag("editor_amount"), prefix = { Text("¥", color = Muted, fontSize = if (tight) 18.sp else 25.sp) },
                                placeholder = { Text("0.00", color = Muted.copy(alpha = .5f), fontSize = if (tight) 22.sp else 40.sp) },
                                textStyle = TextStyle(color = Forest, fontSize = if (tight) 22.sp else if (current.amount.length > 10) 30.sp else 40.sp, fontWeight = FontWeight.SemiBold),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, shape = RoundedCornerShape(18.dp), colors = fieldColors, enabled = !disabled)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("名称", color = Muted, fontSize = 12.sp)
                            OutlinedTextField(current.entry.title, { value -> changeEntry { it.copy(title = value.take(80)) } }, Modifier.fillMaxWidth().testTag("editor_title"), placeholder = { Text("这笔收支是什么？") }, singleLine = true, shape = RoundedCornerShape(16.dp), colors = fieldColors, enabled = !disabled)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("分类", color = Muted, fontSize = 12.sp)
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                val fontScale = LocalDensity.current.fontScale
                                val count = if (maxWidth.value / fontScale < 250) 1 else if (maxWidth.value / fontScale < 330) 2 else 3
                                val suggestions = suggestedCategories(current.entry.kind, current.entry.categoryId, current.transcript, current.entry.title, book.entries, book.categories, count)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    suggestions.forEach { category -> CategoryChip(category, current.entry.categoryId == category.id, { if(!disabled) changeEntry { it.copy(categoryId = category.id) } }, Modifier.weight(1f)) }
                                    Surface(onClick = { expanded = !expanded; focus.clearFocus() }, enabled = !disabled, color = Field, shape = RoundedCornerShape(16.dp), modifier = Modifier.heightIn(min = 48.dp).testTag("editor_categories_more")) {
                                        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.MoreHoriz, null, Modifier.size(18.dp), tint = Muted)
                                            Text(if(expanded) "收起" else "更多", fontSize = 10.sp, color = Muted)
                                        }
                                    }
                                }
                            }
                            if (expanded) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                categoriesForKind(current.entry.kind, book.categories).forEach { category ->
                                    CategoryChip(category, current.entry.categoryId == category.id, { if(!disabled) { changeEntry { it.copy(categoryId = category.id) }; expanded = false } })
                                }
                                OutlinedButton(onClick = { customDialog = true }, enabled = !disabled, shape = RoundedCornerShape(16.dp), modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Text("自定义", fontSize = 13.sp) }
                            }
                        }
                        Surface(onClick = {
                            focus.clearFocus()
                            val date = LocalDate.parse(current.entry.occurredOn)
                            DatePickerDialog(context, { _, year, month, day -> changeEntry { it.copy(occurredOn = LocalDate.of(year, month + 1, day).toString()) } }, date.year, date.monthValue - 1, date.dayOfMonth).apply {
                                datePicker.minDate = LocalDate.of(1900, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                datePicker.maxDate = LocalDate.of(2199, 12, 31).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                show()
                            }
                        }, enabled = !disabled, shape = RoundedCornerShape(16.dp), color = Field) {
                            BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 12.dp).testTag("editor_date")) {
                            val stackedDate = maxWidth.value / LocalDensity.current.fontScale < 250
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.CalendarMonth, null, tint = Muted, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                                if (stackedDate) Column(Modifier.weight(1f)) {
                                    Text("日期", color = Muted, fontSize = 12.sp)
                                    Text(current.entry.occurredOn, fontSize = 14.sp)
                                } else {
                                    Text("日期", color = Muted, fontSize = 14.sp); Spacer(Modifier.weight(1f)); Text(current.entry.occurredOn, fontSize = 14.sp)
                                }
                                Icon(Icons.Outlined.ChevronRight, null, tint = Muted, modifier = Modifier.size(18.dp))
                            }
                            }
                        }
                        OutlinedTextField(current.entry.note, { value -> changeEntry { it.copy(note = value.take(500)) } }, Modifier.fillMaxWidth(), placeholder = { Text("备注（选填）") }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Notes, null, tint = Muted, modifier = Modifier.size(20.dp)) }, minLines = 1, maxLines = 4, shape = RoundedCornerShape(16.dp), colors = fieldColors, enabled = !disabled)
                        if (current.entry.kind == "transfer" || current.entry.kind == "debt") Text("转账及借还本金单独记录，不计入普通收入和支出。", color = Muted, fontSize = 12.sp)
                    }
                    Surface(color = Cream, shadowElevation = 3.dp) {
                        Column(Modifier.fillMaxWidth().testTag("editor_footer").padding(horizontal = sidePadding, vertical = if (tight) 4.dp else if (compact) 8.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (current.formError.isNotBlank()) Text(current.formError, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (tight) IconButton(onClick = model::closeEditor, enabled = !disabled, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Close, "关闭确认页面", tint = Muted) }
                                if (current.editing) OutlinedButton(onClick = { deleteDialog = true }, enabled = !disabled, modifier = Modifier.heightIn(min = if (compact) 48.dp else 54.dp), shape = RoundedCornerShape(18.dp)) { Icon(Icons.Outlined.DeleteOutline, "删除账单", tint = MaterialTheme.colorScheme.error) }
                                Button(onClick = { focus.clearFocus(); model.saveEditor() }, enabled = !disabled, modifier = Modifier.weight(1f).heightIn(min = if (compact) 48.dp else 54.dp).testTag("editor_save"), shape = RoundedCornerShape(18.dp)) {
                                    Icon(Icons.Outlined.Check, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(if (disabled) "保存中…" else if (current.editing) "保存修改" else if (current.proposalNumber < current.proposalTotal) "保存，确认下一笔" else "确认保存", fontSize = 16.sp)
                                }
                            }
                            current.pendingId?.takeIf { !compact }?.let { id ->
                                val remaining = book.pending.find { it.id == id }?.let { runCatching { pendingResult(it).drafts.size }.getOrDefault(1) } ?: 1
                                Text(if (remaining > 1) "本次还有${remaining}笔待确认 · 保存后继续下一笔" else "确认后才计入账本", color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                            }
                        }
                    }
                }
            }
        }
    }
    if (customDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { if(!disabled) customDialog = false }, title = { Text("添加${kindName(categoryScope(current.entry.kind))}分类") }, text = {
            OutlinedTextField(name, { name = it.take(12) }, label = { Text("分类名称，最多12个字") }, singleLine = true, enabled = !disabled)
        }, confirmButton = { TextButton(onClick = { model.addCategory(name, current.entry.kind) { customDialog = false; expanded = false } }, enabled = !disabled && name.isNotBlank()) { Text("添加") } }, dismissButton = { TextButton(onClick = { customDialog = false }, enabled = !disabled) { Text("取消") } })
    }
    if (deleteDialog) AlertDialog(onDismissRequest = { deleteDialog = false }, title = { Text("删除这笔账单？") }, text = { Text("本机记录和统计会同步更新。已经导出的文件不受影响。") },
        confirmButton = { TextButton(onClick = { model.delete(current.entry); deleteDialog = false }, enabled = !disabled) { Text("删除", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("取消") } })
}

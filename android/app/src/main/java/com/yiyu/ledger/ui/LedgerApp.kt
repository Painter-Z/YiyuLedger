package com.yiyu.ledger.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yiyu.ledger.*
import com.yiyu.ledger.data.*
import com.yiyu.ledger.domain.*

data class LedgerFilter(val kind: String = "all", val category: String = "", val period: Period? = null, val dateFilter: String = "")
@OptIn(ExperimentalLayoutApi::class)
@Composable fun LedgerApp(model: LedgerViewModel) = YiyuTheme {
    val book by model.book.collectAsStateWithLifecycle()
    val loaded by model.loaded.collectAsStateWithLifecycle()
    val voice by model.voice.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val editor by model.editor.collectAsStateWithLifecycle()
    val hasKey by model.hasKey.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableIntStateOf(0) }
    var browsing by rememberSaveable(stateSaver = LedgerBrowseSaver) { mutableStateOf(LedgerBrowseOptions(today())) }
    val localDay = rememberLocalDay()
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val shortWindow by remember(windowInfo, density) { derivedStateOf { windowInfo.containerSize.height < with(density) { 500.dp.roundToPx() } } }
    val compactSearchHeader = page == 1 && editor == null && shortWindow && WindowInsets.isImeVisible
    var showKey by remember { mutableStateOf(false) }
    var showText by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf<PendingRecording?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        model.notify(if (granted) "麦克风权限已开启，请重新按住说话" else "可在系统设置中开启麦克风权限，也可手动记账")
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let { model.export(it, true) } }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { model.export(it, false) } }
    val importJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::import) }
    LaunchedEffect(model) { model.messages.collect { snackbar.showSnackbar(it, duration = SnackbarDuration.Short) } }
    BackHandler(enabled = page != 0) { page = 0 }
    Scaffold(modifier = Modifier.semantics { testTagsAsResourceId = true }, containerColor = Cream,
        contentWindowInsets = WindowInsets.safeDrawing, snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = if (compactSearchHeader) 4.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (page == 3) IconButton(onClick = { page = 0 }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                else Box(Modifier.size(38.dp).background(Forest, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.GraphicEq, null, tint = Sage, modifier = Modifier.size(26.dp)) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(when (page) { 1 -> "账单"; 2 -> "收支统计"; 3 -> "设置"; else -> "一语账" }, fontSize = if (compactSearchHeader) 20.sp else 25.sp, fontWeight = FontWeight.SemiBold)
                    if (page != 3 && !compactSearchHeader) Text(when (page) { 1 -> "每一笔生活，都有迹可循"; 2 -> "看见数字，也看见生活"; else -> "让每一笔都有迹可循" }, fontSize = 11.sp, color = Muted)
                }
                if (page != 3) IconButton(onClick = { if (!voice.active && busy.isEmpty()) page = 3 }) { Icon(Icons.Outlined.Settings, "设置", tint = Forest) }
            }
            if (busy.isNotEmpty() || (voice.active && !voice.capturing)) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Forest, trackColor = Sage)
        }
    }, bottomBar = {
        if (page != 3) NavigationBar(containerColor = Card, tonalElevation = 0.dp,
            windowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)) {
            listOf("记一笔", "账单", "统计").forEachIndexed { index, label ->
                NavigationBarItem(selected = page == index, onClick = { if (!voice.active) page = index }, icon = {
                    Icon(listOf(Icons.Outlined.MicNone, Icons.AutoMirrored.Outlined.ReceiptLong, Icons.Outlined.BarChart)[index], null)
                }, label = { Text(label) }, modifier = Modifier.testTag("nav_$index"), colors = NavigationBarItemDefaults.colors(indicatorColor = Sage, selectedIconColor = Forest, selectedTextColor = Forest, unselectedIconColor = Muted, unselectedTextColor = Muted))
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 900.dp).fillMaxSize()) {
                if (!loaded) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Forest) }
                else when(page) {
                    0 -> HomePage(book, voice, busy.isEmpty(), hasKey, onStart = {
                        if (!hasKey) { showKey = true; return@HomePage }
                        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) model.beginRecording()
                        else permission.launch(Manifest.permission.RECORD_AUDIO)
                    }, onEnd = model::endRecording, onManual = model::newManual, onText = { showText = true },
                        onAll = { page = 1 }, onEdit = model::edit, onPending = { if(it.resultJson.isEmpty()) model.retry(it) else model.openPending(it) }, onDiscard = { discard = it })
                    1 -> LedgerPage(book, browsing.copy(today = localDay), onOptions = { browsing = it }, onEdit = model::edit, disabled = busy.isNotEmpty(), onDelete = model::delete)
                    2 -> StatsPage(book, disabled = busy.isNotEmpty(), onBudget = { budget, done -> model.setBudget(budget, done) }) { selected ->
                        browsing = LedgerBrowseOptions(localDay, kind = selected.kind, categoryId = selected.category, dateFilter = selected.dateFilter,
                            rangeStart = selected.period?.start.orEmpty(), rangeEnd = selected.period?.end.orEmpty())
                        page = 1
                    }
                    3 -> SettingsPage(book, hasKey, busy.isNotEmpty(), onKey = { showKey = true },
                        onCsv = { exportCsv.launch("一语账-${today()}.csv") }, onBackup = { exportJson.launch("一语账备份-${today()}.json") },
                        onImport = { importJson.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, onDeleteCategory = model::deleteCategory)
                }
            }
        }
    }
    editor?.let { EditorDialog(it, book, busy.isNotEmpty(), model) }
    if (showKey) KeyDialog(hasKey, busy.isNotEmpty(), onClose = { showKey = false }, onSave = { model.saveKey(it) { showKey = false } }, onClear = { model.clearKey(); showKey = false })
    if (showText) TextDialog(busy.isNotEmpty(), onClose = { showText = false }, onParse = { model.parseText(it) { showText = false } })
    discard?.let { recording -> AlertDialog(onDismissRequest = { discard = null }, title = { Text("清理这次未完成记账？") }, text = { Text("会删除未确认内容及暂存录音，已保存的账单会保留。") },
        confirmButton = { TextButton(onClick = { model.discard(recording); discard = null }) { Text("清理") } }, dismissButton = { TextButton(onClick = { discard = null }) { Text("保留") } }) }
}

@Composable private fun HomePage(book: Book, voice: VoiceUi, available: Boolean, hasKey: Boolean, onStart: () -> Unit, onEnd: (Boolean) -> Unit,
    onManual: () -> Unit, onText: () -> Unit, onAll: () -> Unit, onEdit: (LedgerEntry) -> Unit, onPending: (PendingRecording) -> Unit, onDiscard: (PendingRecording) -> Unit) {
    val period = periodFor("month", today())
    val summary = summarize(book.entries, period)
    Column(Modifier.fillMaxSize().testTag("home_page").verticalScroll(rememberScrollState(), enabled = !voice.capturing).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Surface(shape = RoundedCornerShape(28.dp), color = Forest, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("本月支出", Modifier.weight(1f), color = OnForestMuted, fontSize = 14.sp)
                    Text(period.label, color = OnForestMuted, fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("¥", color = OnForestMuted, fontSize = 24.sp); Spacer(Modifier.width(8.dp)); MoneyText(summary.expense, Modifier.weight(1f), OnForest, large = true) }
                HorizontalDivider(color = HeroLine)
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val stackBalances = maxWidth.value / LocalDensity.current.fontScale < 230 ||
                        maxOf(formatMoney(summary.income).length, formatMoney(summary.balance).length) > 12
                    if (stackBalances) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        HomeBalance("收入", summary.income, "income"); HomeBalance("结余", summary.balance, "balance")
                    } else Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        HomeBalance("收入", summary.income, "income", Modifier.weight(1f)); HomeBalance("结余", summary.balance, "balance", Modifier.weight(1f))
                    }
                }
                if (summary.refund > 0) Text("本月退款 ¥ ${formatMoney(summary.refund)} · 净支出 ¥ ${formatMoney(summary.netExpense)}", color = Sage, fontSize = 11.sp)
            }
        }
        Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("说一句，记一笔", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Icon(Icons.Outlined.AutoAwesome, null, tint = Muted, modifier = Modifier.size(20.dp))
            }
            Text(if (voice.transcript.isNotBlank()) "“${voice.transcript}”" else "例如：中午吃了米线，花了9元", color = Muted, fontSize = 14.sp, modifier = Modifier.heightIn(min = 34.dp))
            VoiceButton(voice, available && !voice.active, onStart, onEnd)
            if (!hasKey) Text("首次使用，在设置中添加个人 API Key", color = Muted, fontSize = 12.sp)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = onManual, enabled = available && !voice.active, modifier = Modifier.testTag("new_manual")) { Icon(Icons.Outlined.Edit, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("手动记账") }
                TextButton(onClick = onText, enabled = available && !voice.active) { Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("文字记账") }
            }
        }
        if (!voice.active) book.pending.forEach { recording ->
            Panel {
                Text(if (recording.resultJson.isEmpty()) "上次录音未完成识别" else "还有待确认的账目", fontWeight = FontWeight.Medium)
                Text(if (recording.resultJson.isEmpty()) "可重试识别，录音最多暂存24小时" else "保存后计入账本，关闭页面也不会丢失", color = Muted, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { onPending(recording) }, enabled = available, modifier = Modifier.weight(1f)) { Text(if (recording.resultJson.isEmpty()) "重试识别" else "继续确认") }
                    IconButton(onClick = { onDiscard(recording) }, enabled = available) { Icon(Icons.Outlined.DeleteOutline, "清理未完成记录", tint = Muted) }
                }
            }
        }
        Panel {
            SectionTitle("最近记录", "查看账单", onAll)
            val recent = filterEntries(book.entries).take(4)
            if (recent.isEmpty()) EmptyState("从第一笔开始", "长按麦克风，记下今天的收支")
            recent.forEach { LedgerRow(it, book.categories) { onEdit(it) } }
        }
    }
}
@Composable private fun HomeBalance(label: String, amount: Long, id: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(label, color = OnForestMuted, fontSize = 12.sp)
        MoneyText(amount, Modifier.fillMaxWidth().testTag("home_${id}_amount"), color = OnForest, prefix = "¥ ")
    }
}
@Composable private fun SettingsPage(book: Book, hasKey: Boolean, disabled: Boolean, onKey: () -> Unit, onCsv: () -> Unit, onBackup: () -> Unit, onImport: () -> Unit, onDeleteCategory: (String) -> Unit) {
    var showPrivacy by remember { mutableStateOf(false) }
    var deleteCategory by remember { mutableStateOf<CustomCategory?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Panel {
            Text("语音识别", style = MaterialTheme.typography.titleMedium)
            SettingRow(Icons.Outlined.Key, "个人 API Key", if(hasKey) "已配置 · 手机直连" else "添加后即可语音记账", !disabled, onKey)
            Text("语音与文字通过百炼识别，收支由你确认后保存。手动记账无需联网。", color = Muted, fontSize = 13.sp)
        }
        Panel {
            Text("账本与备份", style = MaterialTheme.typography.titleMedium)
            SettingRow(Icons.Outlined.IosShare, "导出账单", "CSV表格，可用Excel查看", !disabled, onCsv)
            SettingRow(Icons.Outlined.Backup, "导出完整备份", "包含账单、自定义分类和月预算，可迁移至鸿蒙版", !disabled, onBackup)
            SettingRow(Icons.Outlined.FileOpen, "导入账本备份", "合并JSON备份，相同编号不会重复", !disabled, onImport)
        }
        if (book.categories.isNotEmpty()) Panel {
            Text("自定义分类", style = MaterialTheme.typography.titleMedium)
            book.categories.forEach { category -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(category.name, Modifier.weight(1f)); Text(kindName(category.kind), color = Muted, fontSize = 12.sp)
                IconButton(onClick = { deleteCategory = category }, enabled = !disabled) { Icon(Icons.Outlined.DeleteOutline, "删除${category.name}", tint = Muted) }
            } }
            Text("记账时展开“更多”即可添加分类。已有账单使用的分类需先调整账单。", color = Muted, fontSize = 12.sp)
        }
        Panel { SettingRow(Icons.Outlined.Shield, "隐私与数据", "了解数据如何保存和处理", true) { showPrivacy = true } }
        Text("一语账 ${BuildConfig.VERSION_NAME} · 安卓个人测试版", color = Muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(12.dp))
    }
    if (showPrivacy) AlertDialog(onDismissRequest = { showPrivacy = false }, title = { Text("隐私与数据") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("账单和分类保存在本机。使用语音或文字识别时，相应录音、描述及自定义分类会发送至阿里云百炼。已保存账单不会上传用于识别。")
        Text("API Key 经系统密钥加密保存，不进入导出文件。取消录音或未识别出记账内容时清理录音；识别成功后清理音频，未确认账目保留供你继续处理；失败录音最多保留24小时。")
        Text("删除账单会同步更新本机记录与统计。你主动导出到其他位置的文件由你管理。卸载或清除数据会移除本机账本，请提前导出备份。")
    } }, confirmButton = { TextButton(onClick = { showPrivacy = false }) { Text("知道了") } })
    deleteCategory?.let { category -> AlertDialog(onDismissRequest = { deleteCategory = null }, title = { Text("删除“${category.name}”？") }, text = { Text("只有未被账单或待确认内容使用的分类可以删除。") },
        confirmButton = { TextButton(onClick = { onDeleteCategory(category.id); deleteCategory = null }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleteCategory = null }) { Text("取消") } }) }
}
@Composable private fun SettingRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, color = Color.Transparent, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Forest); Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) { Text(title, fontSize = 16.sp); Text(subtitle, fontSize = 12.sp, color = Muted) }
            Icon(Icons.Outlined.ChevronRight, null, tint = Muted)
        }
    }
}
@Composable private fun KeyDialog(hasKey: Boolean, disabled: Boolean, onClose: () -> Unit, onSave: (String) -> Unit, onClear: () -> Unit) {
    var key by remember { mutableStateOf("") }
    val context = LocalContext.current
    Dialog(onDismissRequest = { if (!disabled) { key = ""; onClose() } }, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)) {
        Surface(shape = RoundedCornerShape(26.dp), color = Card) {
            Column(Modifier.imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(if(hasKey) "更换个人密钥" else "添加个人密钥", style = MaterialTheme.typography.titleLarge)
                Text("填写阿里云百炼北京地域的 API Key，验证通过后加密保存。模型调用费用由你的百炼账户承担。", color = Muted, fontSize = 13.sp)
                OutlinedTextField(key, { key = it.take(4096) }, Modifier.fillMaxWidth(), label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, enabled = !disabled)
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, "https://help.aliyun.com/zh/model-studio/get-api-key".toUri())) }, enabled = !disabled) { Text("如何获取 API Key") }
                Button(onClick = { onSave(key) }, enabled = !disabled && key.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text(if(disabled) "验证中…" else "验证并保存") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { key = ""; onClose() }, enabled = !disabled) { Text("取消") }
                    if(hasKey) TextButton(onClick = { key = ""; onClear() }, enabled = !disabled) { Text("移除密钥", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}
@Composable private fun TextDialog(disabled: Boolean, onClose: () -> Unit, onParse: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if(!disabled) onClose() }, title = { Text("用一句话记账") }, text = {
        OutlinedTextField(text, { text = it.take(2000) }, Modifier.fillMaxWidth(), placeholder = { Text("例如：今天午饭15元，坐地铁3元") }, minLines = 4, maxLines = 8, enabled = !disabled)
    }, confirmButton = { TextButton(onClick = { onParse(text) }, enabled = !disabled && text.isNotBlank()) { Text(if(disabled) "理解中…" else "识别账目") } }, dismissButton = { TextButton(onClick = onClose, enabled = !disabled) { Text("取消") } })
}

package com.yiyu.ledger.ui

import android.view.ContextThemeWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.yiyu.ledger.R
import com.yiyu.ledger.domain.ledgerWeekday
import java.time.LocalDate

/** A centered wheel picker, with the same title/close/buttons arrangement as Harmony. */
@Composable fun LedgerDateDialog(initial: String, onClose: () -> Unit, onAccept: (String) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(initial) }
    val currentOnDate by rememberUpdatedState<(String) -> Unit>({ selected = it })
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
            Box(Modifier.fillMaxSize().background(Color(0x800D2117)).clickable(indication = null, interactionSource = null, onClick = onClose))
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                val compact = maxHeight < 400.dp
                val verticalPadding = if (compact) 12.dp else 20.dp
                val sectionGap = if (compact) 8.dp else 16.dp
                val headerHeight = if (compact) 44.dp else 48.dp
                val chromeHeight = verticalPadding * 2 + sectionGap * 2 + headerHeight + 48.dp
                val pickerHeight = (maxHeight - chromeHeight).coerceIn(140.dp, 220.dp)
                Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth(.94f).heightIn(max = maxHeight).testTag("ledger_date_dialog"), color = Cream, shape = RoundedCornerShape(28.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = verticalPadding), verticalArrangement = Arrangement.spacedBy(sectionGap)) {
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 44.dp) {
                            Row(Modifier.fillMaxWidth().height(headerHeight), verticalAlignment = Alignment.CenterVertically) {
                                Spacer(Modifier.size(44.dp))
                                Text(ledgerWeekday(selected), Modifier.weight(1f).testTag("ledger_weekday"), textAlign = TextAlign.Center, color = Forest,
                                    fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                IconButton(onClick = onClose, modifier = Modifier.size(44.dp).testTag("ledger_date_close")) {
                                    Icon(Icons.Outlined.Close, "取消选择日期", Modifier.size(19.dp), tint = Muted)
                                }
                            }
                        }
                        AndroidView(modifier = Modifier.fillMaxWidth().height(pickerHeight).testTag("ledger_date_wheels"), factory = { context ->
                            val themed = ContextThemeWrapper(context, R.style.Theme_Yiyu_LedgerPicker)
                            LedgerWheelPicker(themed, pickerHeight / 5).apply {
                                id = R.id.ledger_native_date_picker
                                updateDate(LocalDate.parse(selected))
                                onDateChanged = { currentOnDate(it) }
                            }
                        }, update = { picker ->
                            picker.rowHeight = pickerHeight / 5
                            val date = LocalDate.parse(selected)
                            if (picker.date != date) picker.updateDate(date)
                        })
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = onClose, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("ledger_date_cancel"), shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Field, contentColor = Forest)) { Text("取消", fontSize = 14.sp) }
                            Button(onClick = { onAccept(selected) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("ledger_date_accept"), shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Forest, contentColor = OnForest)) { Text("确定", fontSize = 14.sp) }
                        }
                    }
                }
            }
        }
    }
}

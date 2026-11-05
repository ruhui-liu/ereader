package com.ruhuiliu.ereader.reader

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ruhuiliu.ereader.book.TocItem
import com.ruhuiliu.ereader.data.db.BookEntity
import com.ruhuiliu.ereader.data.db.BookmarkEntity
import com.ruhuiliu.ereader.data.settings.Engines
import com.ruhuiliu.ereader.data.settings.Languages
import com.ruhuiliu.ereader.data.settings.ReaderSettings
import com.ruhuiliu.ereader.data.settings.TranslationSettings
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

// ------------------------------------------------------------------ TOC + bookmarks

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TocSheet(
    vm: ReaderViewModel,
    state: ReaderUiState,
    bookmarks: List<BookmarkEntity>,
    onDismiss: () -> Unit,
    onNavigate: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(0.85f)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("目录") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("书签 (${bookmarks.size})") })
            }
            if (tab == 0) {
                val current = state.toc.indexOfLast { it.chapterIndex <= state.chapterIndex }
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = (current - 3).coerceAtLeast(0))
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                    itemsIndexed(state.toc) { i, item: TocItem ->
                        val selected = i == current
                        Text(
                            item.title,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { vm.openToc(item); onNavigate() }
                                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                .padding(start = (20 + item.depth * 18).dp, end = 20.dp, top = 13.dp, bottom = 13.dp),
                            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (item.depth == 0) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else {
                if (bookmarks.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        Text("还没有书签。阅读时点开菜单，点右上角的书签图标即可添加。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(bookmarks, key = { it.id }) { b ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { vm.openBookmark(b); onNavigate() }
                                .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(b.chapterTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (b.excerpt.isNotBlank()) {
                                    Text(b.excerpt, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(
                                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(b.createdAt)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                            IconButton(onClick = { vm.deleteBookmark(b) }) { Icon(Icons.Default.Delete, "删除书签") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ appearance

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AppearanceSheet(vm: ReaderViewModel, s: ReaderSettings, book: BookEntity?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFont(uri, context)
    }
    var fonts by remember { mutableStateOf(vm.customFonts()) }
    LaunchedEffect(s.fontFamily) { fonts = vm.customFonts() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Font size
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("字号")
                FilledTonalIconButton(onClick = { vm.updateReaderSettings { it.copy(fontSize = (it.fontSize - 1).coerceAtLeast(12)) } }) {
                    Icon(Icons.Default.Remove, "缩小")
                }
                Text("${s.fontSize}", modifier = Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium)
                FilledTonalIconButton(onClick = { vm.updateReaderSettings { it.copy(fontSize = (it.fontSize + 1).coerceAtMost(40)) } }) {
                    Icon(Icons.Default.Add, "放大")
                }
            }
            // Themes
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("背景")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ReaderTheme.all.forEach { t ->
                        Box(
                            Modifier
                                .size(40.dp)
                                .background(t.bg, CircleShape)
                                .border(BorderStroke(if (s.theme == t.id) 2.dp else 1.dp,
                                    if (s.theme == t.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant), CircleShape)
                                .clickable { vm.updateReaderSettings { it.copy(theme = t.id) } },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (s.theme == t.id) Icon(Icons.Default.Check, t.name, tint = t.fg, modifier = Modifier.size(18.dp))
                            else Text("文", color = t.fg)
                        }
                    }
                }
            }
            // Fonts
            Column {
                Label("字体")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("book" to "原书字体", "serif" to "衬线/宋体", "sans" to "无衬线/黑体", "mono" to "等宽").forEach { (id, label) ->
                        FilterChip(selected = s.fontFamily == id, onClick = { vm.updateReaderSettings { it.copy(fontFamily = id) } }, label = { Text(label) })
                    }
                    fonts.forEach { f ->
                        val id = "custom:$f"
                        FilterChip(selected = s.fontFamily == id, onClick = { vm.updateReaderSettings { it.copy(fontFamily = id) } },
                            label = { Text(f.substringBeforeLast('.'), maxLines = 1) })
                    }
                    OutlinedButton(onClick = { fontPicker.launch(arrayOf("font/*", "application/x-font-ttf", "application/font-sfnt", "application/octet-stream", "*/*")) }) {
                        Text("导入字体…")
                    }
                }
            }
            SliderRow("行距", s.lineHeight, 1.1f..2.8f, "%.2f".format(s.lineHeight)) { v -> vm.updateReaderSettings { it.copy(lineHeight = (v * 20).roundToInt() / 20f) } }
            SliderRow("段距", s.paragraphSpacing, 0f..2f, "%.1f".format(s.paragraphSpacing)) { v -> vm.updateReaderSettings { it.copy(paragraphSpacing = (v * 10).roundToInt() / 10f) } }
            SliderRow("左右边距", s.marginH.toFloat(), 0f..64f, "${s.marginH}") { v -> vm.updateReaderSettings { it.copy(marginH = v.roundToInt()) } }
            SliderRow("上下边距", s.marginV.toFloat(), 12f..80f, "${s.marginV}") { v -> vm.updateReaderSettings { it.copy(marginV = v.roundToInt()) } }

            Column {
                Label("翻页方式")
                Segmented(listOf("paged" to "左右翻页", "scroll" to "上下滚动"), s.pageMode) { v -> vm.updateReaderSettings { it.copy(pageMode = v) } }
            }
            if (book?.vertical == true) {
                Column {
                    Label("排版方向（本书原为竖排）")
                    Segmented(listOf(true to "转为横排 · 从左到右", false to "保持竖排 · 从右到左"), book.convertToHorizontal) { v -> vm.setConvertToHorizontal(v) }
                }
            }
            Column {
                Label("对齐")
                Segmented(listOf("justify" to "两端对齐", "start" to "左对齐"), s.textAlign) { v -> vm.updateReaderSettings { it.copy(textAlign = v) } }
            }
            SwitchRow("首行缩进", s.indent) { v -> vm.updateReaderSettings { it.copy(indent = v) } }
            SwitchRow("统一排版（覆盖书籍自带的行距、颜色）", s.overrideStyles) { v -> vm.updateReaderSettings { it.copy(overrideStyles = v) } }
            SwitchRow("显示注音（振假名 / ruby）", s.showRuby) { v -> vm.updateReaderSettings { it.copy(showRuby = v) } }
            SwitchRow("翻页动画", s.pageAnimation) { v -> vm.updateReaderSettings { it.copy(pageAnimation = v) } }
            SwitchRow("音量键翻页", s.volumeKeys) { v -> vm.updateReaderSettings { it.copy(volumeKeys = v) } }
            SwitchRow("屏幕常亮", s.keepScreenOn) { v -> vm.updateReaderSettings { it.copy(keepScreenOn = v) } }
        }
    }
}

// ------------------------------------------------------------------ translation

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TranslationSheet(
    vm: ReaderViewModel,
    state: ReaderUiState,
    ts: TranslationSettings,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val s by vm.readerSettings.collectAsStateWithLifecycle()
    val book = state.book
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column {
                Label("显示方式")
                Segmented(listOf(0 to "关闭", 1 to "双语对照", 2 to "仅译文"), book?.translateMode ?: 0) { vm.setTranslateMode(it) }
            }
            when (val st = state.translation) {
                is TranslationStatus.Working -> Column {
                    Text("正在翻译本章：${st.done}/${st.total}", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { if (st.total == 0) 0f else st.done.toFloat() / st.total }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
                is TranslationStatus.Failed -> Text("翻译失败：${st.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TranslationStatus.Idle -> Unit
            }
            Column {
                Label("翻译引擎")
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = Engines.label(ts.engine),
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        Engines.all.forEach { e ->
                            DropdownMenuItem(text = { Text(Engines.label(e)) }, onClick = { vm.setEngine(e); expanded = false })
                        }
                    }
                }
                TextButton(onClick = onOpenSettings) { Text("配置 API Key / 模型 / 下载离线模型…") }
            }
            Column {
                Label("译文语言")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Languages.targets.forEach { (code, name) ->
                        FilterChip(selected = ts.targetLang == code, onClick = { vm.setTargetLang(code) }, label = { Text(name) })
                    }
                }
            }
            Column {
                Label("原文语言（本书）")
                var expanded by remember { mutableStateOf(false) }
                val current = book?.sourceLang ?: "auto"
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = Languages.sources.firstOrNull { it.first == current }?.second ?: current,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        Languages.sources.forEach { (code, name) ->
                            DropdownMenuItem(text = { Text(name) }, onClick = {
                                vm.setSourceLang(if (code == "auto") null else code)
                                expanded = false
                            })
                        }
                    }
                }
            }
            SliderRow("译文字号", s.translationScale, 0.7f..1.15f, "${(s.translationScale * 100).roundToInt()}%") { v ->
                vm.updateReaderSettings { it.copy(translationScale = (v * 100).roundToInt() / 100f) }
            }
            OutlinedButton(onClick = { vm.retranslateChapter() }, enabled = (book?.translateMode ?: 0) != 0) {
                Text("忽略缓存，重新翻译本章")
            }
            Text(
                "提示：长按选中文字，在弹出菜单中点“翻译”可单独翻译选中的词句。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ------------------------------------------------------------------ search

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchSheet(vm: ReaderViewModel, onDismiss: () -> Unit, onNavigate: () -> Unit) {
    val search by vm.search.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf(search.query) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(0.85f).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("在全书中搜索") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search(query) }),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        search.running -> "搜索中… 已找到 ${search.hits.size} 处"
                        search.query.isNotEmpty() -> "共 ${search.hits.size} 处" + if (search.hits.size >= 500) "（仅显示前 500 处）" else ""
                        else -> ""
                    },
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                if (search.query.isNotEmpty()) TextButton(onClick = { vm.clearSearchHighlight(); vm.search(""); query = "" }) { Text("清除") }
            }
            LazyColumn(Modifier.fillMaxWidth()) {
                items(search.hits) { hit ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { vm.openSearchHit(hit); onNavigate() }
                            .padding(vertical = 10.dp)
                    ) {
                        Text(hit.chapterTitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        val hl = MaterialTheme.colorScheme.tertiaryContainer
                        Text(
                            buildAnnotatedString {
                                val s = hit.snippet
                                val a = hit.matchStart.coerceIn(0, s.length)
                                val b = (a + hit.matchLength).coerceAtMost(s.length)
                                append(s.substring(0, a))
                                withStyle(SpanStyle(background = hl, fontWeight = FontWeight.Bold)) { append(s.substring(a, b)) }
                                append(s.substring(b))
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

// ------------------------------------------------------------------ selection translation

@Composable
internal fun SelectionTranslationDialog(sel: SelectionTranslation, onDismiss: () -> Unit, onCopy: (String) -> Unit) {
    val original = when (sel) {
        is SelectionTranslation.Loading -> sel.text
        is SelectionTranslation.Done -> sel.text
        is SelectionTranslation.Failed -> sel.text
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("翻译") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(original, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                when (sel) {
                    is SelectionTranslation.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    is SelectionTranslation.Done -> Text(sel.translation, style = MaterialTheme.typography.bodyLarge)
                    is SelectionTranslation.Failed -> Text(sel.message, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = {
            if (sel is SelectionTranslation.Done) TextButton(onClick = { onCopy(sel.translation) }) { Text("复制译文") }
        },
    )
}

// ------------------------------------------------------------------ small building blocks

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(end = 12.dp, bottom = 6.dp),
    )
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String, onChange: (Float) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(72.dp))
        Slider(value = local, onValueChange = { local = it }, onValueChangeFinished = { onChange(local) }, valueRange = range, modifier = Modifier.weight(1f))
        Text(display, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

package com.ruhuiliu.ereader.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ruhuiliu.ereader.data.db.BookEntity
import kotlin.math.roundToInt

private val OPEN_MIME_TYPES = arrayOf(
    "application/epub+zip", "text/plain", "text/html", "application/xhtml+xml", "application/octet-stream", "*/*",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    onOpenBook: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCloud: (String) -> Unit,
) {
    val books by vm.books.collectAsStateWithLifecycle()
    val grid by vm.grid.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showImport by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var actionBook by remember { mutableStateOf<BookEntity?>(null) }
    var editBook by remember { mutableStateOf<BookEntity?>(null) }
    var deleteBook by remember { mutableStateOf<BookEntity?>(null) }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.importUris(uris)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.importTree(uri)
    }

    LaunchedEffect(Unit) { vm.messageFlow.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { vm.openFlow.collect { onOpenBook(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = query,
                            onValueChange = { vm.query.value = it },
                            placeholder = { Text("搜索书名或作者") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text("书架")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (searching) vm.query.value = ""
                        searching = !searching
                    }) { Icon(if (searching) Icons.Default.Close else Icons.Default.Search, "搜索") }
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "排序") }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            listOf("recent" to "最近阅读", "added" to "最近添加", "title" to "书名", "progress" to "阅读进度").forEach { (k, label) ->
                                DropdownMenuItem(
                                    text = { Text(if (k == sort) "✓  $label" else "    $label") },
                                    onClick = { vm.setSort(k); sortMenu = false },
                                )
                            }
                        }
                    }
                    IconButton(onClick = { vm.setGrid(!grid) }) {
                        Icon(if (grid) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView, "切换视图")
                    }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "设置") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showImport = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("导入") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            importing?.let {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            val list = books
            when {
                list == null -> Unit
                list.isEmpty() && query.isNotBlank() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("没有找到匹配的书", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                list.isEmpty() -> EmptyLibrary(
                    onLocal = { pickFiles.launch(OPEN_MIME_TYPES) },
                    onDrive = { onOpenCloud("gdrive") },
                    onDropbox = { onOpenCloud("dropbox") },
                )
                grid -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 108.dp),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(list, key = { it.id }) { book ->
                        GridBook(book, vm, onClick = { onOpenBook(book.id) }, onLongClick = { actionBook = book })
                    }
                }
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(list, key = { it.id }) { book ->
                        ListBook(book, vm, onClick = { onOpenBook(book.id) }, onLongClick = { actionBook = book })
                    }
                }
            }
        }
    }

    if (showImport) {
        ModalBottomSheet(onDismissRequest = { showImport = false }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text("导入书籍", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                ImportOption(Icons.AutoMirrored.Filled.InsertDriveFile, "本地文件", "选择 EPUB / TXT / HTML，可多选") {
                    showImport = false; pickFiles.launch(OPEN_MIME_TYPES)
                }
                ImportOption(Icons.Default.Folder, "本地文件夹", "导入文件夹（含子文件夹）中的所有书") {
                    showImport = false; pickFolder.launch(null)
                }
                ImportOption(Icons.Default.Cloud, "Google Drive", "浏览云端硬盘并下载导入") {
                    showImport = false; onOpenCloud("gdrive")
                }
                ImportOption(Icons.Default.CloudQueue, "Dropbox", "浏览 Dropbox 并下载导入") {
                    showImport = false; onOpenCloud("dropbox")
                }
                Text(
                    "提示：如果手机装有 Google Drive 或 Dropbox 应用，在“本地文件”的系统选择器侧边栏里也能直接选它们里面的文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }
    }

    actionBook?.let { book ->
        ModalBottomSheet(onDismissRequest = { actionBook = null }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp))
                Text(
                    listOfNotNull(
                        book.author.ifBlank { null },
                        book.format,
                        book.language,
                        if (book.vertical) "竖排" else null,
                        "%.1f MB".format(book.fileSize / 1048576.0),
                        when (book.source) { "gdrive" -> "来自 Google Drive"; "dropbox" -> "来自 Dropbox"; else -> null },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
                ListItem(headlineContent = { Text("打开") }, modifier = Modifier.combinedClickable(onClick = { actionBook = null; onOpenBook(book.id) }))
                ListItem(headlineContent = { Text("编辑书名和作者") }, modifier = Modifier.combinedClickable(onClick = { actionBook = null; editBook = book }))
                ListItem(
                    headlineContent = { Text("删除", color = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.combinedClickable(onClick = { actionBook = null; deleteBook = book }),
                )
            }
        }
    }

    editBook?.let { book ->
        var title by remember(book.id) { mutableStateOf(book.title) }
        var author by remember(book.id) { mutableStateOf(book.author) }
        AlertDialog(
            onDismissRequest = { editBook = null },
            title = { Text("编辑信息") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text("书名") }, singleLine = true)
                    OutlinedTextField(author, { author = it }, label = { Text("作者") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = { vm.rename(book, title, author); editBook = null }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { editBook = null }) { Text("取消") } },
        )
    }

    deleteBook?.let { book ->
        AlertDialog(
            onDismissRequest = { deleteBook = null },
            title = { Text("删除这本书？") },
            text = { Text("《${book.title}》以及它的阅读进度和书签都会被删除。") },
            confirmButton = {
                TextButton(onClick = { vm.delete(book); deleteBook = null }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteBook = null }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridBook(book: BookEntity, vm: LibraryViewModel, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        BookCover(book, vm.coverFile(book), Modifier.fillMaxWidth().aspectRatio(0.7f))
        Spacer(Modifier.height(6.dp))
        Text(book.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(progressLabel(book), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListBook(book: BookEntity, vm: LibraryViewModel, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(book, vm.coverFile(book), Modifier.width(52.dp).aspectRatio(0.7f))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (book.author.isNotBlank()) {
                Text(book.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(progress = { book.totalProgress }, modifier = Modifier.fillMaxWidth().height(3.dp))
            Text(progressLabel(book), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun progressLabel(book: BookEntity): String = when {
    book.lastReadAt == 0L -> "未读 · ${book.format}"
    book.totalProgress >= 0.999f -> "已读完"
    else -> "已读 ${(book.totalProgress * 1000).roundToInt() / 10f}%"
}

@Composable
private fun ImportOption(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        modifier = Modifier.combinedClickable(onClick = onClick),
    )
}

@Composable
private fun EmptyLibrary(onLocal: () -> Unit, onDrive: () -> Unit, onDropbox: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("书架还是空的", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "导入 EPUB、TXT 或 HTML 电子书。阅读英文、日文书时可以打开双语对照，竖排书会自动转为横排。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onLocal, modifier = Modifier.fillMaxWidth()) { Text("从本地导入") }
        OutlinedButton(onClick = onDrive, modifier = Modifier.fillMaxWidth()) { Text("从 Google Drive 导入") }
        OutlinedButton(onClick = onDropbox, modifier = Modifier.fillMaxWidth()) { Text("从 Dropbox 导入") }
    }
}

package com.ruhuiliu.ereader.ui.cloud

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudBrowserScreen(vm: CloudViewModel, onBack: () -> Unit, onOpenBook: (Long) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as Activity

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        vm.onConsentResult(context, result.resultCode == Activity.RESULT_OK, result.data)
    }

    LaunchedEffect(Unit) {
        vm.eventFlow.collect { e ->
            when (e) {
                is CloudEvent.Consent -> consent.launch(IntentSenderRequest.Builder(e.intent.intentSender).build())
                is CloudEvent.Message -> Toast.makeText(context, e.text, Toast.LENGTH_LONG).show()
                is CloudEvent.Opened -> onOpenBook(e.bookId)
            }
        }
    }
    LifecycleResumeEffect(Unit) {
        vm.onResume(activity)
        onPauseOrDispose { }
    }
    BackHandler { if (!vm.up()) onBack() }

    var searchText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.title)
                        state.account?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (!vm.up()) onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    if (state.signedIn == true) {
                        IconButton(onClick = { vm.refresh() }) { Icon(Icons.Default.Refresh, "刷新") }
                        if (state.provider == "dropbox") {
                            IconButton(onClick = { vm.logoutDropbox() }) { Icon(Icons.AutoMirrored.Filled.Logout, "退出登录") }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.signedIn == false) {
                SignInPrompt(state, onDrive = { vm.authorizeDrive(activity) }, onDropbox = { vm.loginDropbox(activity) })
                return@Column
            }
            if (state.provider == "gdrive") {
                PrimaryTabRow(selectedTabIndex = state.driveTab) {
                    Tab(selected = state.driveTab == 0, onClick = { vm.setDriveTab(0) }, text = { Text("我的云端硬盘") })
                    Tab(selected = state.driveTab == 1, onClick = { vm.setDriveTab(1) }, text = { Text("与我共享") })
                }
            }
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                singleLine = true,
                placeholder = { Text(if (state.provider == "gdrive") "搜索整个云端硬盘" else "筛选当前文件夹") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search(searchText) }),
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                state.path.forEachIndexed { i, crumb ->
                    if (i > 0) Text("›", color = MaterialTheme.colorScheme.outline)
                    TextButton(onClick = { vm.goTo(i) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text(crumb.name, maxLines = 1)
                    }
                }
                Spacer(Modifier.weight(1f))
                FilterChip(selected = state.onlyBooks, onClick = { vm.setOnlyBooks(!state.onlyBooks) }, label = { Text("只看电子书") })
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            }
            val entries = if (state.onlyBooks) state.entries.filter { it.isFolder || it.importable } else state.entries
            if (!state.loading && entries.isEmpty() && state.error == null && state.signedIn == true) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("这里没有可导入的电子书", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(entries, key = { it.id }) { e ->
                    ListItem(
                        leadingContent = {
                            Icon(
                                when {
                                    e.isFolder -> Icons.Default.Folder
                                    e.isGoogleDoc -> Icons.Default.Description
                                    e.importable -> Icons.AutoMirrored.Filled.MenuBook
                                    else -> Icons.AutoMirrored.Filled.InsertDriveFile
                                },
                                null,
                                tint = if (e.isFolder || e.importable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            )
                        },
                        headlineContent = { Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            when {
                                e.isFolder -> Unit
                                e.isGoogleDoc -> Text("Google 文档 · 导出为纯文本")
                                e.size != null -> Text(formatSize(e.size))
                            }
                        },
                        modifier = Modifier.clickable(enabled = e.isFolder || e.importable) { vm.open(e) },
                    )
                }
            }
        }
    }

    state.download?.let { name ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("正在下载") },
            text = {
                Column {
                    Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                    val p = state.downloadProgress
                    if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = {},
        )
    }
}

@Composable
private fun SignInPrompt(state: CloudUiState, onDrive: () -> Unit, onDropbox: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state.provider == "dropbox") {
            if (state.notConfigured) {
                Text("Dropbox 尚未配置", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "需要在 Dropbox App Console 创建应用，并在编译时提供 App Key（local.properties 中的 dropbox.appKey），详见 README。\n\n" +
                        "临时办法：安装 Dropbox 应用后，在书架“导入 → 本地文件”的系统文件选择器里也可以直接选择 Dropbox 中的文件。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text("连接你的 Dropbox", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("只申请只读权限，用于浏览和下载电子书。", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(20.dp))
                Button(onClick = onDropbox) { Text("登录 Dropbox") }
            }
        } else {
            Text("连接 Google Drive", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
            }
            Text(
                "授权后可浏览云端硬盘中的 EPUB / TXT 并导入（只读权限）。\n\n" +
                    "也可以安装 Google Drive 应用，然后在“导入 → 本地文件”的系统文件选择器里直接选择云端硬盘中的文件。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onDrive) { Text("授权 Google Drive") }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1 shl 10 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

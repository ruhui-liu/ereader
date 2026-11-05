package com.ruhuiliu.ereader.reader

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ruhuiliu.ereader.KeyEvents
import kotlin.math.roundToInt

private enum class Sheet { NONE, TOC, APPEARANCE, TRANSLATION, SEARCH }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    fontsDir: java.io.File,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.readerSettings.collectAsStateWithLifecycle()
    val trSettings by viewModel.translationSettings.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val theme = ReaderTheme.byId(settings.theme)
    var sheet by remember { mutableStateOf(Sheet.NONE) }

    // ---- WebView
    val webView = remember {
        ReaderWebView.create(
            context = context,
            content = { viewModel.content },
            fontsDir = fontsDir,
            bridge = ReaderBridge { viewModel.onWebMessage(it) },
            onExternalUrl = { url -> openUrl(context, url) },
        ).also { wv ->
            wv.onTranslateSelection = { text -> viewModel.translateSelection(text) }
            wv.loadUrl(READER_URL)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }
    LaunchedEffect(webView) {
        viewModel.jsCommands.collect { code -> webView.evaluateJavascript(code, null) }
    }
    LaunchedEffect(theme) { webView.setBackgroundColor(theme.bg.toArgb()) }

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { e ->
            when (e) {
                is ReaderEvent.Toast -> Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
                is ReaderEvent.OpenUrl -> openUrl(context, e.url)
            }
        }
    }

    // ---- System bars: immersive while reading, visible with the menu.
    val window = (view.context as? Activity)?.window
    LaunchedEffect(state.menuVisible, theme, window) {
        if (window == null) return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !theme.isDark
        controller.isAppearanceLightNavigationBars = !theme.isDark
        if (state.menuVisible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(window) {
        onDispose {
            if (window != null) {
                val c = WindowCompat.getInsetsController(window, view)
                c.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // ---- Volume keys turn pages.
    val volumeKeys by rememberUpdatedState(settings.volumeKeys && sheet == Sheet.NONE && !state.menuVisible)
    DisposableEffect(Unit) {
        val handler: (KeyEvent) -> Boolean = handler@{ ev ->
            if (!volumeKeys) return@handler false
            when (ev.keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> { if (ev.action == KeyEvent.ACTION_DOWN) viewModel.next(); true }
                KeyEvent.KEYCODE_VOLUME_UP -> { if (ev.action == KeyEvent.ACTION_DOWN) viewModel.prev(); true }
                else -> false
            }
        }
        KeyEvents.handler = handler
        onDispose { if (KeyEvents.handler === handler) KeyEvents.handler = null }
    }

    BackHandler(enabled = state.menuVisible) { viewModel.hideMenu() }

    val fg = theme.fg
    Box(
        Modifier
            .fillMaxSize()
            .background(theme.bg)
    ) {
        AndroidView(
            factory = { webView },
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.displayCutout),
        )

        if (state.loading) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = fg)
        }
        state.busy?.let { msg ->
            Surface(
                modifier = Modifier.align(Alignment.Center),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 4.dp,
            ) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(msg)
                }
            }
        }
        state.error?.let { err ->
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("无法打开这本书", color = fg, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(err, color = fg.copy(alpha = .7f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onBack) { Text("返回书架") }
            }
        }

        // ---- Bottom status line while reading.
        if (!state.menuVisible && !state.loading && state.error == null) {
            StatusLine(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.displayCutout),
                color = fg.copy(alpha = .45f),
                left = viewModel.chapterTitle(state.chapterIndex),
                right = if (state.pdfPage >= 0) {
                    "第 ${state.pdfPage + 1}/${state.pdfPageCount} 页  ·  ${"%.1f".format(state.totalProgress * 100)}%"
                } else {
                    "${state.page + 1}/${state.pages}  ·  ${"%.1f".format(state.totalProgress * 100)}%"
                },
            )
        }

        // ---- Translation progress / error chip.
        TranslationChip(
            status = state.translation,
            visible = (state.book?.translateMode ?: 0) != 0,
            theme = theme,
            onRetry = { viewModel.retryTranslation() },
            onSettings = onOpenSettings,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = if (state.menuVisible) 64.dp else 4.dp, end = 8.dp),
        )

        // ---- Menu
        AnimatedVisibility(
            visible = state.menuVisible,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            val bookmarked = viewModel.currentBookmark(bookmarks, state) != null
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp, shadowElevation = 4.dp) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(56.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                    Column(Modifier.weight(1f)) {
                        Text(state.book?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        Text(
                            viewModel.chapterTitle(state.chapterIndex),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.book?.format == "PDF") {
                        val reflow = state.book?.pdfReflow == true
                        TextButton(onClick = { viewModel.setPdfReflow(!reflow) }) { Text(if (reflow) "原版" else "重排") }
                    }
                    IconButton(onClick = { sheet = Sheet.SEARCH }) { Icon(Icons.Default.Search, "搜索") }
                    IconButton(onClick = { viewModel.toggleBookmark() }) {
                        Icon(if (bookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, "书签")
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = state.menuVisible,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            BottomMenu(
                viewModel = viewModel,
                state = state,
                isDark = theme.isDark,
                onToc = { sheet = Sheet.TOC },
                onAppearance = { sheet = Sheet.APPEARANCE },
                onTranslation = { sheet = Sheet.TRANSLATION },
                onToggleNight = {
                    viewModel.updateReaderSettings { s ->
                        s.copy(theme = if (ReaderTheme.byId(s.theme).isDark) "sepia" else "dark")
                    }
                },
            )
        }
    }

    // ---- Sheets
    val dismiss = { sheet = Sheet.NONE }
    when (sheet) {
        Sheet.TOC -> TocSheet(viewModel, state, bookmarks, onDismiss = dismiss, onNavigate = { dismiss(); viewModel.hideMenu() })
        Sheet.APPEARANCE -> AppearanceSheet(viewModel, settings, state.book, onDismiss = dismiss)
        Sheet.TRANSLATION -> TranslationSheet(viewModel, state, trSettings, onDismiss = dismiss, onOpenSettings = { dismiss(); onOpenSettings() })
        Sheet.SEARCH -> SearchSheet(viewModel, onDismiss = dismiss, onNavigate = { dismiss(); viewModel.hideMenu() })
        Sheet.NONE -> Unit
    }

    selection?.let { sel ->
        SelectionTranslationDialog(sel, onDismiss = { viewModel.dismissSelection() }, onCopy = { copyText(context, it) })
    }
}

@Composable
private fun StatusLine(modifier: Modifier, color: Color, left: String, right: String) {
    Row(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(left, color = color, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(right, color = color, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun TranslationChip(
    status: TranslationStatus,
    visible: Boolean,
    theme: ReaderTheme,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier,
) {
    if (!visible) return
    when (status) {
        is TranslationStatus.Working -> Surface(
            modifier = modifier,
            shape = RoundedCornerShape(50),
            color = theme.fg.copy(alpha = .08f),
        ) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = theme.translation)
                Spacer(Modifier.width(6.dp))
                Text("翻译中 ${status.done}/${status.total}", fontSize = 11.sp, color = theme.fg.copy(alpha = .7f))
            }
        }
        is TranslationStatus.Failed -> Surface(
            modifier = modifier.padding(start = 48.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer,
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text("翻译失败：${status.message}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onSettings) { Text("翻译设置") }
                    TextButton(onClick = onRetry) { Text("重试") }
                }
            }
        }
        TranslationStatus.Idle -> Unit
    }
}

@Composable
private fun BottomMenu(
    viewModel: ReaderViewModel,
    state: ReaderUiState,
    isDark: Boolean,
    onToc: () -> Unit,
    onAppearance: () -> Unit,
    onTranslation: () -> Unit,
    onToggleNight: () -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val value = if (dragging) dragValue else state.totalProgress
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            if (dragging) {
                Text(
                    "${viewModel.titleAtTotal(dragValue)}  ${(dragValue * 1000).roundToInt() / 10f}%",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { viewModel.prevChapter() }, enabled = state.chapterIndex > 0) { Text("上一章") }
                Slider(
                    value = value,
                    onValueChange = { dragging = true; dragValue = it },
                    onValueChangeFinished = {
                        viewModel.seekTotal(dragValue)
                        dragging = false
                    },
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { viewModel.nextChapter() }, enabled = state.chapterIndex < state.chapterCount - 1) { Text("下一章") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MenuAction(Icons.AutoMirrored.Filled.List, "目录", onToc)
                MenuAction(Icons.Default.Translate, "翻译", onTranslation)
                MenuAction(Icons.Default.TextFields, "外观", onAppearance)
                MenuAction(if (isDark) Icons.Default.LightMode else Icons.Default.DarkMode, if (isDark) "日间" else "夜间", onToggleNight)
            }
        }
    }
}

@Composable
private fun MenuAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, label)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

internal fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
        .onFailure { Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show() }
}

internal fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("text", text))
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}

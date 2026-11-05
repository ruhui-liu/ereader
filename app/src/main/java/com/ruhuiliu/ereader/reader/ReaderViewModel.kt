package com.ruhuiliu.ereader.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ruhuiliu.ereader.AppContainer
import com.ruhuiliu.ereader.book.BookContent
import com.ruhuiliu.ereader.book.BookFormat
import com.ruhuiliu.ereader.book.BookPaths
import com.ruhuiliu.ereader.book.TocItem
import com.ruhuiliu.ereader.data.db.BookEntity
import com.ruhuiliu.ereader.data.db.BookmarkEntity
import com.ruhuiliu.ereader.data.settings.Engines
import com.ruhuiliu.ereader.data.settings.ReaderSettings
import com.ruhuiliu.ereader.data.settings.TranslationSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

sealed interface TranslationStatus {
    data object Idle : TranslationStatus
    data class Working(val done: Int, val total: Int) : TranslationStatus
    data class Failed(val message: String) : TranslationStatus
}

sealed interface SelectionTranslation {
    data class Loading(val text: String) : SelectionTranslation
    data class Done(val text: String, val translation: String) : SelectionTranslation
    data class Failed(val text: String, val message: String) : SelectionTranslation
}

data class SearchHit(val chapterIndex: Int, val chapterTitle: String, val occurrence: Int, val snippet: String, val matchStart: Int, val matchLength: Int)

data class SearchState(val query: String = "", val running: Boolean = false, val hits: List<SearchHit> = emptyList())

data class ReaderUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val book: BookEntity? = null,
    val toc: List<TocItem> = emptyList(),
    val chapterCount: Int = 0,
    val chapterIndex: Int = 0,
    val page: Int = 0,
    val pages: Int = 1,
    val chapterFraction: Float = 0f,
    val totalProgress: Float = 0f,
    val anchor: String? = null,
    val excerpt: String = "",
    val menuVisible: Boolean = false,
    val translation: TranslationStatus = TranslationStatus.Idle,
)

sealed interface ReaderEvent {
    data class Toast(val message: String) : ReaderEvent
    data class OpenUrl(val url: String) : ReaderEvent
}

class ReaderViewModel(private val app: AppContainer, private val bookId: Long) : ViewModel() {
    private val repo = app.books
    private val translation = app.translation

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    val readerSettings: StateFlow<ReaderSettings> =
        app.settings.readerSettings.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderSettings())
    val translationSettings: StateFlow<TranslationSettings> =
        app.settings.translationSettings.stateIn(viewModelScope, SharingStarted.Eagerly, TranslationSettings())

    val bookmarks: StateFlow<List<BookmarkEntity>> =
        repo.observeBookmarks(bookId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()

    private val _selection = MutableStateFlow<SelectionTranslation?>(null)
    val selection: StateFlow<SelectionTranslation?> = _selection.asStateFlow()

    /** JavaScript snippets for the WebView host to evaluate, in order. */
    private val jsChannel = Channel<String>(Channel.UNLIMITED)
    val jsCommands = jsChannel.receiveAsFlow()

    private val events = Channel<ReaderEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    @Volatile
    var content: BookContent? = null
        private set
    private var weights: List<Long> = emptyList()
    private var webReady = false
    private var started = false
    private var rs: ReaderSettings? = null
    private var ts: TranslationSettings? = null
    private var currentBlock = 0
    private var saveJob: Job? = null
    private var translateJob: Job? = null
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                var book = repo.getBook(bookId) ?: throw IllegalStateException("书籍不存在")
                // First time this book is opened: apply the default translation mode (not for Chinese books).
                if (book.lastReadAt == 0L && book.translateMode == 0) {
                    val def = app.settings.translationSettings.first().defaultMode
                    if (def != 0 && book.language?.startsWith("zh") != true) {
                        repo.setTranslateMode(book.id, def)
                        book = book.copy(translateMode = def)
                    }
                }
                val opened = withContext(Dispatchers.IO) { app.storage.open(book) }
                content = opened
                weights = opened.chapters.map { maxOf(1L, it.size) }
                _state.update {
                    it.copy(
                        loading = false,
                        book = book,
                        toc = opened.toc,
                        chapterCount = opened.chapters.size,
                        chapterIndex = book.chapterIndex.coerceIn(0, opened.chapters.size - 1),
                        chapterFraction = book.chapterProgress,
                        totalProgress = book.totalProgress,
                        anchor = book.anchor,
                    )
                }
                maybeStart()
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: e.toString()) }
            }
        }
        // Keep the book entity (per-book preferences) fresh.
        viewModelScope.launch {
            repo.observeBook(bookId).collect { b -> if (b != null) _state.update { it.copy(book = b) } }
        }
        // Push appearance changes to the page.
        viewModelScope.launch {
            app.settings.readerSettings.distinctUntilChanged().collect { new ->
                val first = rs == null
                rs = new
                if (first) maybeStart() else if (started) pushSettings()
            }
        }
        // Re-translate when the engine or the target language changes.
        viewModelScope.launch {
            app.settings.translationSettings.distinctUntilChanged().collect { new ->
                val old = ts
                ts = new
                when {
                    old == null -> maybeStart()
                    !started -> Unit
                    old.engine != new.engine || old.targetLang != new.targetLang || engineDetails(old) != engineDetails(new) -> {
                        js("Reader.clearTranslations()")
                        pushSettings()
                        if ((_state.value.book?.translateMode ?: 0) != 0) js("Reader.requestBlocks()")
                    }
                }
            }
        }
    }

    private fun maybeStart() {
        if (!started && webReady && content != null && rs != null && ts != null) {
            started = true
            startReading()
        }
    }

    private fun engineDetails(s: TranslationSettings) = when (s.engine) {
        Engines.OPENAI -> s.openaiBaseUrl + s.openaiModel + s.llmExtraPrompt
        Engines.CLAUDE -> s.claudeModel + s.llmExtraPrompt
        else -> ""
    }

    private fun js(code: String) {
        if (webReady) jsChannel.trySend(code)
    }

    // ------------------------------------------------------------------ settings → page

    private fun buildJsSettings(): JSONObject {
        val rs = rs ?: ReaderSettings()
        val ts = ts ?: TranslationSettings()
        val book = _state.value.book
        val theme = ReaderTheme.byId(rs.theme)
        val custom = rs.fontFamily.startsWith("custom:")
        val family = when {
            custom -> "\"YDCustom\", serif"
            rs.fontFamily == "serif" -> "serif"
            rs.fontFamily == "sans" -> "sans-serif"
            rs.fontFamily == "mono" -> "monospace"
            else -> ""
        }
        val isJaEpub = book?.format == BookFormat.EPUB.name && book.language?.startsWith("ja") == true
        val indent = when {
            !rs.indent -> "0"
            isJaEpub -> "" // Japanese books indent with a full-width space in the text itself.
            else -> "2em"
        }
        val keepVertical = book?.vertical == true && !book.convertToHorizontal
        return JSONObject()
            .put("fontSize", rs.fontSize)
            .put("lineHeight", rs.lineHeight.toDouble())
            .put("paraSpacing", rs.paragraphSpacing.toDouble())
            .put("marginH", rs.marginH)
            .put("marginV", rs.marginV)
            .put("fontFamily", family)
            .put("customFontUrl", if (custom) FONT_BASE + BookPaths.encodePath(rs.fontFamily.removePrefix("custom:")) else "")
            .put("bg", theme.bg.toCss())
            .put("fg", theme.fg.toCss())
            .put("trColor", theme.translation.toCss())
            .put("linkColor", theme.link.toCss())
            .put("textAlign", rs.textAlign)
            .put("indent", indent)
            .put("override", rs.overrideStyles)
            .put("showRuby", rs.showRuby)
            .put("paged", rs.pageMode == "paged")
            .put("vertical", keepVertical)
            .put("forceHorizontal", !keepVertical)
            .put("rtl", keepVertical)
            .put("animate", rs.pageAnimation)
            .put("trMode", book?.translateMode ?: 0)
            .put("trScale", rs.translationScale.toDouble())
            .put("bookLang", book?.language ?: "")
            .put("trLang", ts.targetLang)
    }

    private fun pushSettings() {
        js("Reader.setSettings(${buildJsSettings()})")
    }

    // ------------------------------------------------------------------ messages from the page

    fun onWebMessage(msg: JSONObject) {
        when (msg.optString("type")) {
            "ready" -> {
                // A (re)created WebView: render the current position again.
                webReady = true
                started = false
                maybeStart()
            }
            "chapterLoaded" -> {
                if ((_state.value.book?.translateMode ?: 0) != 0) js("Reader.requestBlocks()")
            }
            "position" -> onPosition(msg)
            "boundary" -> {
                val idx = _state.value.chapterIndex
                if (msg.optString("dir") == "next") {
                    if (idx < _state.value.chapterCount - 1) loadChapter(idx + 1, JSONObject().put("type", "start"))
                    else events.trySend(ReaderEvent.Toast("已经是最后一页了"))
                } else {
                    if (idx > 0) loadChapter(idx - 1, JSONObject().put("type", "end"))
                    else events.trySend(ReaderEvent.Toast("已经是第一页了"))
                }
            }
            "menu" -> _state.update { it.copy(menuVisible = !it.menuVisible) }
            "blocks" -> onBlocks(msg)
            "link" -> {
                val c = content ?: return
                val idx = c.chapterIndexOf(msg.optString("path"))
                if (idx >= 0) {
                    val frag = msg.optString("fragment")
                    loadChapter(idx, if (frag.isNotEmpty()) JSONObject().put("type", "fragment").put("id", frag) else JSONObject().put("type", "start"))
                }
            }
            "external" -> events.trySend(ReaderEvent.OpenUrl(msg.optString("url")))
            "error" -> events.trySend(ReaderEvent.Toast("加载出错：" + msg.optString("message")))
        }
    }

    private fun startReading() {
        val s = _state.value
        pushSettings()
        val pos = if (s.anchor != null) {
            JSONObject().put("type", "anchor").put("anchor", s.anchor).put("fraction", s.chapterFraction.toDouble())
        } else {
            JSONObject().put("type", "fraction").put("value", s.chapterFraction.toDouble())
        }
        loadChapter(s.chapterIndex, pos)
    }

    private fun onPosition(msg: JSONObject) {
        val index = msg.optInt("index")
        if (index != _state.value.chapterIndex) return
        val fraction = msg.optDouble("fraction", 0.0).toFloat().coerceIn(0f, 1f)
        val anchor = msg.optString("anchor").ifEmpty { null }
        currentBlock = msg.optInt("block", 0)
        val total = totalProgress(index, fraction)
        _state.update {
            it.copy(
                page = msg.optInt("page"),
                pages = msg.optInt("pages", 1).coerceAtLeast(1),
                chapterFraction = fraction,
                totalProgress = total,
                anchor = anchor,
                excerpt = msg.optString("excerpt"),
            )
        }
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(800)
            repo.savePosition(bookId, index, fraction, anchor, total)
        }
    }

    private fun totalProgress(index: Int, fraction: Float): Float {
        if (weights.isEmpty()) return 0f
        val sum = weights.sum().toDouble()
        val before = weights.take(index).sum().toDouble()
        val cur = weights.getOrElse(index) { 0L }.toDouble()
        val isLast = index == weights.size - 1
        val f = if (isLast && fraction >= 0.999f) 1.0 else fraction.toDouble()
        return ((before + cur * f) / sum).toFloat().coerceIn(0f, 1f)
    }

    // ------------------------------------------------------------------ navigation

    fun loadChapter(index: Int, position: JSONObject) {
        val c = content ?: return
        if (index !in c.chapters.indices) return
        translateJob?.cancel()
        _state.update { it.copy(chapterIndex = index, page = 0, translation = TranslationStatus.Idle) }
        val ch = c.chapters[index]
        val msg = JSONObject()
            .put("index", index)
            .put("url", BOOK_BASE + BookPaths.encodePath(ch.path))
            .put("mime", ch.mediaType)
            .put("count", c.chapters.size)
            .put("position", position)
        js("Reader.loadChapter($msg)")
    }

    fun next() = js("Reader.next()")
    fun prev() = js("Reader.prev()")
    fun toggleMenu() = _state.update { it.copy(menuVisible = !it.menuVisible) }
    fun hideMenu() = _state.update { it.copy(menuVisible = false) }

    fun nextChapter() {
        val i = _state.value.chapterIndex
        if (i < _state.value.chapterCount - 1) loadChapter(i + 1, JSONObject().put("type", "start"))
    }

    fun prevChapter() {
        val i = _state.value.chapterIndex
        if (i > 0) loadChapter(i - 1, JSONObject().put("type", "start"))
    }

    fun openToc(item: TocItem) {
        val pos = if (item.fragment != null) JSONObject().put("type", "fragment").put("id", item.fragment)
        else JSONObject().put("type", "start")
        if (item.chapterIndex == _state.value.chapterIndex && item.fragment != null) {
            js("Reader.goToFragment(${JSONObject.quote(item.fragment)})")
        } else {
            loadChapter(item.chapterIndex, pos)
        }
    }

    fun openBookmark(b: BookmarkEntity) {
        val pos = JSONObject().put("type", "anchor").put("anchor", b.anchor ?: "").put("fraction", b.chapterProgress.toDouble())
        if (b.chapterIndex == _state.value.chapterIndex) {
            js("Reader.goToAnchor(${JSONObject.quote(b.anchor ?: "")}, ${b.chapterProgress})")
        } else {
            loadChapter(b.chapterIndex, pos)
        }
    }

    /** Jump to an overall book position (0..1). */
    fun seekTotal(value: Float) {
        if (weights.isEmpty()) return
        val sum = weights.sum().toDouble()
        val target = value.coerceIn(0f, 1f) * sum
        var acc = 0.0
        var idx = weights.size - 1
        for (i in weights.indices) {
            if (acc + weights[i] >= target) { idx = i; break }
            acc += weights[i]
        }
        val frac = ((target - acc) / weights[idx]).toFloat().coerceIn(0f, 1f)
        if (idx == _state.value.chapterIndex) js("Reader.goToFraction($frac)")
        else loadChapter(idx, JSONObject().put("type", "fraction").put("value", frac.toDouble()))
    }

    /** Chapter title for an overall position, used while dragging the progress slider. */
    fun titleAtTotal(value: Float): String {
        if (weights.isEmpty()) return ""
        val target = value * weights.sum()
        var acc = 0L
        var idx = weights.size - 1
        for (i in weights.indices) {
            if (acc + weights[i] >= target) { idx = i; break }
            acc += weights[i]
        }
        return chapterTitle(idx)
    }

    fun chapterTitle(index: Int): String {
        val toc = _state.value.toc
        return toc.lastOrNull { it.chapterIndex <= index }?.title
            ?: content?.chapters?.getOrNull(index)?.title
            ?: "第 ${index + 1} 节"
    }

    // ------------------------------------------------------------------ settings changes

    fun updateReaderSettings(transform: (ReaderSettings) -> ReaderSettings) {
        viewModelScope.launch { app.settings.updateReader(transform) }
    }

    fun setTranslateMode(mode: Int) {
        viewModelScope.launch {
            repo.setTranslateMode(bookId, mode)
            _state.update { it.copy(book = it.book?.copy(translateMode = mode)) }
            pushSettings()
            if (mode == 0) {
                translateJob?.cancel()
                _state.update { it.copy(translation = TranslationStatus.Idle) }
            } else {
                js("Reader.requestBlocks()")
            }
        }
    }

    fun setSourceLang(lang: String?) {
        viewModelScope.launch {
            repo.setSourceLang(bookId, lang)
            _state.update { it.copy(book = it.book?.copy(sourceLang = lang)) }
            js("Reader.clearTranslations()")
            if ((_state.value.book?.translateMode ?: 0) != 0) js("Reader.requestBlocks()")
        }
    }

    fun setConvertToHorizontal(value: Boolean) {
        viewModelScope.launch {
            repo.setConvertToHorizontal(bookId, value)
            _state.update { it.copy(book = it.book?.copy(convertToHorizontal = value)) }
            pushSettings()
        }
    }

    fun setEngine(engine: String) {
        viewModelScope.launch { app.settings.updateTranslation { it.copy(engine = engine) } }
    }

    fun setTargetLang(lang: String) {
        viewModelScope.launch { app.settings.updateTranslation { it.copy(targetLang = lang) } }
    }

    fun retryTranslation() = js("Reader.requestBlocks()")

    /** Drops cached translations of the current chapter and translates it again. */
    fun retranslateChapter() {
        lastBlocks?.let { (idx, items) ->
            if (idx == _state.value.chapterIndex) {
                viewModelScope.launch {
                    runCatching { translation.forget(items.map { it.second }, _state.value.book?.sourceLang) }
                    js("Reader.clearTranslations()")
                    js("Reader.requestBlocks()")
                }
            }
        }
    }

    // ------------------------------------------------------------------ translation

    private var lastBlocks: Pair<Int, List<Pair<Int, String>>>? = null

    private fun onBlocks(msg: JSONObject) {
        val index = msg.optInt("index")
        if (index != _state.value.chapterIndex) return
        val arr = msg.optJSONArray("items") ?: JSONArray()
        val items = (0 until arr.length()).map { i -> arr.getJSONObject(i).let { it.getInt("i") to it.getString("t") } }
        lastBlocks = index to items
        currentBlock = msg.optInt("center", currentBlock)
        if ((_state.value.book?.translateMode ?: 0) == 0) return
        startTranslation(index, items)
    }

    private fun sendTranslations(chapter: Int, pairs: List<Pair<Int, String>>) {
        if (pairs.isEmpty()) return
        val arr = JSONArray()
        pairs.forEach { (i, t) -> arr.put(JSONObject().put("i", i).put("t", t)) }
        js("Reader.setTranslations($chapter, $arr)")
    }

    private fun startTranslation(chapter: Int, items: List<Pair<Int, String>>) {
        translateJob?.cancel()
        translateJob = viewModelScope.launch {
            val source = _state.value.book?.sourceLang
            try {
                val target = translation.targetLang()
                val todo = items.filter { !translation.shouldSkip(it.second, target) }
                if (todo.isEmpty()) {
                    _state.update { it.copy(translation = TranslationStatus.Idle) }
                    return@launch
                }
                val cached = translation.cached(todo.map { it.second }, source)
                sendTranslations(chapter, cached.map { (i, t) -> todo[i].first to t })
                val pending = todo.filterIndexed { i, _ -> i !in cached }.toMutableList()
                val total = todo.size
                _state.update { it.copy(translation = TranslationStatus.Working(total - pending.size, total)) }
                val engine = translation.engine()
                while (pending.isNotEmpty()) {
                    val center = currentBlock
                    val ahead = pending.filter { it.first >= center - 1 }.sortedBy { it.first }
                    val ordered = ahead.ifEmpty { pending.sortedByDescending { it.first } }
                    val batch = mutableListOf<Pair<Int, String>>()
                    var chars = 0
                    for (p in ordered) {
                        if (batch.isNotEmpty() && (batch.size >= engine.maxBatchItems || chars + p.second.length > engine.maxBatchChars)) break
                        batch += p
                        chars += p.second.length
                    }
                    val result = translation.translate(batch.map { it.second }, source)
                    sendTranslations(chapter, batch.mapIndexed { i, p -> p.first to result[i] })
                    pending.removeAll(batch.toSet())
                    _state.update { it.copy(translation = TranslationStatus.Working(total - pending.size, total)) }
                }
                _state.update { it.copy(translation = TranslationStatus.Idle) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(translation = TranslationStatus.Failed(e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    fun translateSelection(text: String) {
        _selection.value = SelectionTranslation.Loading(text)
        viewModelScope.launch {
            _selection.value = try {
                SelectionTranslation.Done(text, translation.translateOne(text, _state.value.book?.sourceLang))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SelectionTranslation.Failed(text, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun dismissSelection() {
        _selection.value = null
    }

    // ------------------------------------------------------------------ bookmarks

    fun currentBookmark(list: List<BookmarkEntity>, s: ReaderUiState): BookmarkEntity? {
        val tolerance = if (s.pages > 1) 0.5f / (s.pages - 1) else 1f
        return list.firstOrNull { it.chapterIndex == s.chapterIndex && abs(it.chapterProgress - s.chapterFraction) <= tolerance + 1e-4f }
    }

    fun toggleBookmark() {
        viewModelScope.launch {
            val s = _state.value
            val existing = currentBookmark(bookmarks.value, s)
            if (existing != null) {
                repo.deleteBookmark(existing.id)
                events.trySend(ReaderEvent.Toast("已删除书签"))
            } else {
                repo.addBookmark(
                    BookmarkEntity(
                        bookId = bookId,
                        chapterIndex = s.chapterIndex,
                        chapterProgress = s.chapterFraction,
                        anchor = s.anchor,
                        chapterTitle = chapterTitle(s.chapterIndex),
                        excerpt = s.excerpt.take(120),
                    )
                )
                events.trySend(ReaderEvent.Toast("已添加书签"))
            }
        }
    }

    fun deleteBookmark(b: BookmarkEntity) {
        viewModelScope.launch { repo.deleteBookmark(b.id) }
    }

    // ------------------------------------------------------------------ search

    fun search(query: String) {
        searchJob?.cancel()
        val q = query.trim()
        if (q.isEmpty()) {
            _search.value = SearchState()
            return
        }
        _search.value = SearchState(q, running = true)
        searchJob = viewModelScope.launch(Dispatchers.Default) {
            val c = content ?: return@launch
            val hits = mutableListOf<SearchHit>()
            for (i in c.chapters.indices) {
                val text = runCatching { c.chapterText(i) }.getOrDefault("")
                var from = 0
                var occ = 0
                while (hits.size < 500) {
                    val at = text.indexOf(q, from, ignoreCase = true)
                    if (at < 0) break
                    val s = maxOf(0, at - 24)
                    val e = minOf(text.length, at + q.length + 40)
                    val raw = text.substring(s, e)
                    val snippet = raw.replace(Regex("\\s+"), " ")
                    val matchStart = snippet.indexOf(text.substring(at, at + q.length).replace(Regex("\\s+"), " "), ignoreCase = true)
                    hits += SearchHit(i, chapterTitle(i), occ, snippet, matchStart.coerceAtLeast(0), q.length)
                    occ++
                    from = at + q.length
                }
                if (i % 8 == 0 || i == c.chapters.size - 1) _search.update { it.copy(hits = hits.toList()) }
                if (hits.size >= 500) break
            }
            _search.update { it.copy(hits = hits.toList(), running = false) }
        }
    }

    fun openSearchHit(hit: SearchHit) {
        val q = _search.value.query
        if (hit.chapterIndex == _state.value.chapterIndex) {
            js("Reader.search(${JSONObject.quote(q)}, ${hit.occurrence})")
        } else {
            loadChapter(hit.chapterIndex, JSONObject().put("type", "search").put("query", q).put("occurrence", hit.occurrence))
        }
    }

    fun clearSearchHighlight() = js("Reader.clearSearch()")

    // ------------------------------------------------------------------ fonts

    fun importFont(uri: android.net.Uri, context: android.content.Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull() ?: "font.ttf"
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in setOf("ttf", "otf", "ttc")) {
                events.trySend(ReaderEvent.Toast("请选择 TTF / OTF 字体文件"))
                return@launch
            }
            val safe = name.replace(Regex("[^\\w.\\-\\u4e00-\\u9fff]"), "_")
            val dest = java.io.File(app.storage.fontsDir, safe)
            context.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
            app.settings.updateReader { it.copy(fontFamily = "custom:${dest.name}") }
            events.trySend(ReaderEvent.Toast("已导入字体 ${dest.nameWithoutExtension}"))
        }
    }

    fun customFonts(): List<String> = app.storage.fonts().map { it.name }

    override fun onCleared() {
        super.onCleared()
        val s = _state.value
        if (!s.loading && s.error == null) {
            app.appScope.launch { repo.savePosition(bookId, s.chapterIndex, s.chapterFraction, s.anchor, s.totalProgress) }
        }
        content?.close()
    }
}

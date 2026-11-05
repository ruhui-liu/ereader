package com.ruhuiliu.ereader.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ruhuiliu.ereader.AppContainer
import com.ruhuiliu.ereader.IncomingFiles
import com.ruhuiliu.ereader.data.ImportResult
import com.ruhuiliu.ereader.data.db.BookEntity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class LibraryViewModel(private val app: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")
    val grid: StateFlow<Boolean> = app.settings.libraryGrid.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val sort: StateFlow<String> = app.settings.librarySort.stateIn(viewModelScope, SharingStarted.Eagerly, "recent")

    val books: StateFlow<List<BookEntity>?> = combine(app.books.observeBooks(), query, sort) { list, q, s ->
        val filtered = if (q.isBlank()) list else list.filter {
            it.title.contains(q, ignoreCase = true) || it.author.contains(q, ignoreCase = true)
        }
        when (s) {
            "title" -> filtered.sortedBy { it.title.lowercase() }
            "added" -> filtered.sortedByDescending { it.addedAt }
            "progress" -> filtered.sortedByDescending { it.totalProgress }
            else -> filtered
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _importing = MutableStateFlow<String?>(null)
    val importing: StateFlow<String?> = _importing

    private val messages = Channel<String>(Channel.BUFFERED)
    val messageFlow = messages.receiveAsFlow()

    /** Set when a single imported book should be opened right away. */
    private val openRequests = Channel<Long>(Channel.BUFFERED)
    val openFlow = openRequests.receiveAsFlow()

    init {
        viewModelScope.launch {
            IncomingFiles.pending.collect { uris ->
                if (uris.isNotEmpty()) {
                    IncomingFiles.pending.value = emptyList()
                    importUris(uris, openSingle = true)
                }
            }
        }
    }

    fun coverFile(book: BookEntity): File? = app.storage.coverFile(book)

    fun setGrid(value: Boolean) = viewModelScope.launch { app.settings.setLibraryGrid(value) }
    fun setSort(value: String) = viewModelScope.launch { app.settings.setLibrarySort(value) }

    fun importUris(uris: List<Uri>, openSingle: Boolean = false) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val results = mutableListOf<ImportResult>()
            uris.forEachIndexed { i, uri ->
                _importing.value = if (uris.size > 1) "正在导入 ${i + 1}/${uris.size}…" else "正在导入…"
                results += app.importer.importUri(uri)
            }
            _importing.value = null
            report(results)
            if (openSingle && results.size == 1) {
                when (val r = results[0]) {
                    is ImportResult.Added -> openRequests.send(r.book.id)
                    is ImportResult.Duplicate -> openRequests.send(r.book.id)
                    else -> Unit
                }
            }
        }
    }

    fun importTree(uri: Uri) {
        viewModelScope.launch {
            val results = mutableListOf<ImportResult>()
            _importing.value = "正在扫描文件夹…"
            app.importer.importTree(uri) { r ->
                results += r
                _importing.value = "已导入 ${results.size} 个文件…"
            }
            _importing.value = null
            if (results.isEmpty()) messages.send("文件夹中没有找到 EPUB / TXT / HTML 文件") else report(results)
        }
    }

    private suspend fun report(results: List<ImportResult>) {
        val added = results.filterIsInstance<ImportResult.Added>()
        val dup = results.filterIsInstance<ImportResult.Duplicate>()
        val failed = results.filterIsInstance<ImportResult.Failed>()
        val parts = mutableListOf<String>()
        if (added.isNotEmpty()) parts += if (added.size == 1) "已导入《${added[0].book.title}》" else "已导入 ${added.size} 本书"
        if (dup.isNotEmpty()) parts += "${dup.size} 本已在书架中"
        if (failed.isNotEmpty()) parts += "${failed.size} 个失败：" + failed.joinToString("；") { "${it.name}（${it.message}）" }
        if (parts.isNotEmpty()) messages.send(parts.joinToString("，"))
    }

    fun delete(book: BookEntity) = viewModelScope.launch {
        app.books.delete(book)
        messages.send("已删除《${book.title}》")
    }

    fun rename(book: BookEntity, title: String, author: String) = viewModelScope.launch {
        app.books.rename(book.id, title.trim().ifEmpty { book.title }, author.trim())
    }
}

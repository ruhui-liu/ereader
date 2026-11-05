package com.ruhuiliu.ereader.data

import com.ruhuiliu.ereader.data.db.AppDatabase
import com.ruhuiliu.ereader.data.db.BookEntity
import com.ruhuiliu.ereader.data.db.BookmarkEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class BookRepository(private val db: AppDatabase, val storage: BookStorage) {
    private val books = db.books()
    private val bookmarks = db.bookmarks()

    fun observeBooks(): Flow<List<BookEntity>> = books.observeAll()
    fun observeBook(id: Long): Flow<BookEntity?> = books.observe(id)
    suspend fun getBook(id: Long) = books.get(id)

    suspend fun delete(book: BookEntity) = withContext(Dispatchers.IO) {
        books.delete(book.id)
        storage.bookDir(book).deleteRecursively()
        storage.pageCacheDir(book).deleteRecursively()
    }

    suspend fun rename(id: Long, title: String, author: String) = books.rename(id, title, author)

    suspend fun savePosition(id: Long, chapter: Int, progress: Float, anchor: String?, total: Float) =
        books.updatePosition(id, chapter, progress, anchor, total, System.currentTimeMillis())

    suspend fun setTranslateMode(id: Long, mode: Int) = books.setTranslateMode(id, mode)
    suspend fun setSourceLang(id: Long, lang: String?) = books.setSourceLang(id, lang)
    suspend fun setConvertToHorizontal(id: Long, value: Boolean) = books.setConvertToHorizontal(id, value)
    suspend fun setPdfReflow(id: Long, value: Boolean) = books.setPdfReflow(id, value)

    fun observeBookmarks(bookId: Long): Flow<List<BookmarkEntity>> = bookmarks.observe(bookId)
    suspend fun addBookmark(b: BookmarkEntity) = bookmarks.insert(b)
    suspend fun deleteBookmark(id: Long) = bookmarks.delete(id)
}

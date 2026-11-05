package com.ruhuiliu.ereader.data

import android.content.Context
import com.ruhuiliu.ereader.book.BookContent
import com.ruhuiliu.ereader.book.BookFormat
import com.ruhuiliu.ereader.book.HtmlBook
import com.ruhuiliu.ereader.book.epub.EpubBook
import com.ruhuiliu.ereader.book.txt.TxtBook
import com.ruhuiliu.ereader.data.db.BookEntity
import java.io.File

/** Where books, covers and fonts live inside the app's private storage. */
class BookStorage(context: Context) {
    val booksRoot = File(context.filesDir, "books").apply { mkdirs() }
    val fontsDir = File(context.filesDir, "fonts").apply { mkdirs() }
    val tempDir = File(context.cacheDir, "import").apply { mkdirs() }

    fun bookDir(book: BookEntity) = File(booksRoot, book.dirName)

    fun coverFile(book: BookEntity): File? = book.coverFile?.let { File(bookDir(book), it) }?.takeIf { it.exists() }

    fun open(book: BookEntity): BookContent {
        val dir = bookDir(book)
        return when (BookFormat.valueOf(book.format)) {
            BookFormat.EPUB -> EpubBook(File(dir, book.fileName))
            BookFormat.TXT -> TxtBook(File(dir, book.fileName), File(dir, TXT_INDEX))
            BookFormat.HTML -> HtmlBook(File(dir, book.fileName))
        }
    }

    fun fonts(): List<File> = fontsDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name.lowercase() }.orEmpty()

    companion object {
        const val TXT_INDEX = "chapters.json"
    }
}

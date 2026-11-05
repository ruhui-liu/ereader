package com.ruhuiliu.ereader.book

import java.io.File

/** A single (X)HTML file read as a one-chapter book. */
class HtmlBook(private val file: File) : BookContent {
    private val fileName = if (file.extension.equals("xhtml", true)) "index.xhtml" else "index.html"

    override val chapters = listOf(Chapter(0, fileName, BookPaths.mimeTypeForPath(fileName), file.length(), null))

    override val toc: List<TocItem> = listOf(
        TocItem(HtmlText.title(file.readText()) ?: file.nameWithoutExtension, 0, null, 0)
    )

    override fun read(path: String): ByteArray? = if (path == fileName) file.readBytes() else null

    override fun chapterText(index: Int): String = HtmlText.toPlainText(file.readText())
}

package com.ruhuiliu.ereader.book.epub

import com.ruhuiliu.ereader.book.BookContent
import com.ruhuiliu.ereader.book.BookPaths
import com.ruhuiliu.ereader.book.Chapter
import com.ruhuiliu.ereader.book.HtmlText
import com.ruhuiliu.ereader.book.TocItem
import java.io.File
import java.util.zip.ZipFile

/** [EntryReader] over a zip file, tolerant of case mismatches between hrefs and entry names. */
class ZipEntryReader(file: File) : EntryReader, AutoCloseable {
    private val zip = ZipFile(file)
    private val byLower: Map<String, String> = zip.entries().asSequence().map { it.name }
        .associateBy { BookPaths.normalize(it).lowercase() }

    override val entryNames: Collection<String> get() = byLower.values

    private fun entryName(path: String): String? {
        val norm = BookPaths.normalize(path)
        return if (zip.getEntry(norm) != null) norm else byLower[norm.lowercase()]
    }

    @Synchronized
    override fun read(path: String): ByteArray? {
        val name = entryName(path) ?: return null
        val entry = zip.getEntry(name) ?: return null
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    override fun size(path: String): Long {
        val name = entryName(path) ?: return 0
        return zip.getEntry(name)?.size?.coerceAtLeast(0) ?: 0
    }

    override fun close() = zip.close()
}

class EpubBook(file: File) : BookContent {
    private val reader = ZipEntryReader(file)
    val pkg: EpubPackage = try {
        EpubParser.parse(reader)
    } catch (e: Exception) {
        reader.close()
        throw e
    }

    override val chapters: List<Chapter> = pkg.spine.mapIndexed { i, item ->
        Chapter(i, item.path, item.mediaType.ifEmpty { BookPaths.mimeTypeForPath(item.path) }, reader.size(item.path), null)
    }

    override val toc: List<TocItem> = buildToc()

    private fun buildToc(): List<TocItem> {
        val items = pkg.toc.mapNotNull { e ->
            val idx = chapterIndexOf(e.path)
            if (idx < 0) null else TocItem(e.title, idx, e.fragment, e.depth)
        }
        if (items.isNotEmpty()) return items
        // No usable TOC: derive one from document titles.
        return chapters.map { c ->
            val title = read(c.path)?.let { HtmlText.title(String(it, Charsets.UTF_8)) }
            TocItem(title ?: "第 ${c.index + 1} 节", c.index, null, 0)
        }
    }

    override fun read(path: String): ByteArray? = reader.read(path)

    override fun mimeType(path: String): String {
        val norm = BookPaths.normalize(path)
        return pkg.manifest.values.firstOrNull { it.path == norm }?.mediaType?.ifEmpty { null }
            ?: BookPaths.mimeTypeForPath(path)
    }

    override fun chapterText(index: Int): String {
        val bytes = read(chapters[index].path) ?: return ""
        return HtmlText.toPlainText(String(bytes, Charsets.UTF_8))
    }

    override fun close() = reader.close()
}

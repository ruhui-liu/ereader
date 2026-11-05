package com.ruhuiliu.ereader.book.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.ruhuiliu.ereader.book.BookContent
import com.ruhuiliu.ereader.book.Chapter
import com.ruhuiliu.ereader.book.HtmlText
import com.ruhuiliu.ereader.book.TocItem
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.Writer
import kotlin.math.roundToInt

/** Collects positioned glyphs from PdfBox in content-stream order. */
internal class GlyphCollector : PDFTextStripper() {
    val glyphs = mutableListOf<Glyph>()

    init {
        sortByPosition = false
    }

    override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
        textPositions?.forEach { tp ->
            val u = tp.unicode ?: return@forEach
            if (u.isBlank()) return@forEach
            val size = if (tp.fontSizeInPt > 0f) tp.fontSizeInPt else tp.heightDir
            glyphs += Glyph(u, tp.xDirAdj, tp.yDirAdj - tp.heightDir, tp.widthDirAdj, tp.heightDir, size)
        }
    }

    override fun writeWordSeparator() {}
    override fun writeLineSeparator() {}

    /** Glyphs of one page (0-based). */
    fun page(doc: PDDocument, index: Int): List<Glyph> {
        glyphs.clear()
        startPage = index + 1
        endPage = index + 1
        writeText(doc, NullWriter)
        return glyphs.toList()
    }

    private object NullWriter : Writer() {
        override fun write(cbuf: CharArray, off: Int, len: Int) {}
        override fun flush() {}
        override fun close() {}
    }
}

/**
 * A PDF book. Pages are grouped into chapters of [PAGES_PER_CHAPTER] pages and shown either as the original
 * page images ([reflow] = false, rendered by the platform PdfRenderer) or as extracted, re-flowed text
 * ([reflow] = true, PdfBox + [PdfLayout]) that supports fonts, themes and inline translation.
 *
 * Files kept in [workDir]: pages.json (page sizes), outline.json, text/<chapter>.json (extracted paragraphs).
 * Rendered page images are cached in [imageCacheDir].
 */
class PdfBook(
    private val file: File,
    private val workDir: File,
    private val imageCacheDir: File,
    private val renderWidth: Int,
    @Volatile var reflow: Boolean,
    private val language: String?,
) : BookContent {

    /** Include each page's (hidden) text with the original page images, so it can be translated. */
    @Volatile
    var withText: Boolean = false

    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try {
        PdfRenderer(pfd)
    } catch (e: Exception) {
        pfd.close()
        throw e
    }
    private val renderLock = Any()
    private val textLock = Any()
    private var pdDoc: PDDocument? = null
    private var collector: GlyphCollector? = null

    val pageCount: Int = renderer.pageCount

    /** Page sizes in points (width to height), used for image aspect ratios. */
    private val pageSizes: List<Pair<Int, Int>> = loadPageSizes()

    override val chapters: List<Chapter> = (0 until chapterCount(pageCount)).map { k ->
        val r = pageRange(k)
        Chapter(k, "$DIR/$k.html", "text/html", (r.last - r.first + 1).toLong(), "第 ${r.first + 1}–${r.last + 1} 页")
    }

    override val toc: List<TocItem> = buildToc()

    fun pageRange(chapter: Int): IntRange = (chapter * PAGES_PER_CHAPTER) until minOf(pageCount, (chapter + 1) * PAGES_PER_CHAPTER)

    fun chapterOfPage(page: Int): Int = (page.coerceIn(0, maxOf(0, pageCount - 1))) / PAGES_PER_CHAPTER

    // ------------------------------------------------------------------ BookContent

    override fun read(path: String): ByteArray? {
        if (!path.startsWith("$DIR/")) return null
        val rest = path.removePrefix("$DIR/")
        if (rest.startsWith("img/")) {
            // img/<page>_<width>.jpg
            val name = rest.removePrefix("img/").removeSuffix(".jpg")
            val page = name.substringBefore('_').toIntOrNull() ?: return null
            val width = name.substringAfter('_', "").toIntOrNull() ?: renderWidth
            if (page !in 0 until pageCount) return null
            return pageImage(page, width.coerceIn(200, 3000))
        }
        val chapter = rest.removeSuffix(".html").toIntOrNull() ?: return null
        if (chapter !in chapters.indices) return null
        return (if (reflow) reflowHtml(chapter) else originalHtml(chapter)).toByteArray(Charsets.UTF_8)
    }

    override fun mimeType(path: String): String = if (path.endsWith(".jpg")) "image/jpeg" else "text/html"

    /** Text of a chapter for search; pages are separated by form feeds (see [pageOfOffset]). */
    override fun chapterText(index: Int): String =
        chapterParagraphs(index).joinToString(PAGE_SEPARATOR.toString()) { page -> page.joinToString("\n") { it.text } }

    /** 0-based page of a character offset in [chapterText]. */
    fun pageOfOffset(chapter: Int, text: String, offset: Int): Int =
        pageRange(chapter).first + text.substring(0, offset.coerceIn(0, text.length)).count { it == PAGE_SEPARATOR }

    override fun close() {
        synchronized(renderLock) {
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
        synchronized(textLock) {
            runCatching { pdDoc?.close() }
            pdDoc = null
        }
    }

    // ------------------------------------------------------------------ HTML

    private fun imageTag(page: Int): String {
        val (w, h) = pageSizes.getOrElse(page) { 595 to 842 }
        val height = (renderWidth.toFloat() * h / w).roundToInt()
        return "<img src=\"img/${page}_$renderWidth.jpg\" width=\"$renderWidth\" height=\"$height\" alt=\"\" loading=\"lazy\" decoding=\"async\"/>"
    }

    private fun paragraphHtml(sb: StringBuilder, p: PdfParagraph) {
        val tag = if (p.heading) "h3" else "p"
        sb.append('<').append(tag).append('>').append(HtmlText.escape(p.text)).append("</").append(tag).append('>')
    }

    private fun head(chapter: Int, cls: String): StringBuilder {
        val lang = language?.let { " lang=\"${HtmlText.escape(it)}\"" } ?: ""
        return StringBuilder().append("<!DOCTYPE html><html$lang><head><meta charset=\"utf-8\"><title>")
            .append(HtmlText.escape(chapters[chapter].title.orEmpty()))
            .append("</title></head><body class=\"$cls\">")
    }

    /** Original pages: each page image, followed by its (hidden) text so it can be translated below the page. */
    private fun originalHtml(chapter: Int): String {
        val cached = File(workDir, "text/$chapter.json").isFile
        val paragraphs = if (withText || cached) runCatching { chapterParagraphs(chapter) }.getOrNull() else null
        val sb = head(chapter, "yd-pdf-orig")
        pageRange(chapter).forEachIndexed { i, page ->
            sb.append("<section class=\"yd-pdf-page\" id=\"yd-page-$page\" data-pdf-page=\"$page\">")
            sb.append(imageTag(page))
            val paras = paragraphs?.getOrNull(i).orEmpty()
            if (paras.isNotEmpty()) {
                sb.append("<div class=\"yd-pdf-text\">")
                paras.forEach { paragraphHtml(sb, it) }
                sb.append("</div>")
            }
            sb.append("</section>")
        }
        return sb.append("</body></html>").toString()
    }

    /** Re-flowed text. Pages without (much) text, e.g. illustrations or scans, are shown as images. */
    private fun reflowHtml(chapter: Int): String {
        val paragraphs = chapterParagraphs(chapter)
        val sb = head(chapter, "yd-pdf-reflow")
        pageRange(chapter).forEachIndexed { i, page ->
            sb.append("<div class=\"yd-pdf-mark\" id=\"yd-page-$page\" data-pdf-page=\"$page\"></div>")
            val paras = paragraphs.getOrNull(i).orEmpty()
            val chars = paras.sumOf { p -> p.text.count { !it.isWhitespace() } }
            if (chars < IMAGE_PAGE_MAX_CHARS) {
                sb.append("<figure class=\"yd-pdf-figure\">").append(imageTag(page)).append("</figure>")
                if (paras.isNotEmpty()) {
                    sb.append("<div class=\"yd-pdf-text\">")
                    paras.forEach { paragraphHtml(sb, it) }
                    sb.append("</div>")
                }
            } else {
                paras.forEach { paragraphHtml(sb, it) }
            }
        }
        return sb.append("</body></html>").toString()
    }

    // ------------------------------------------------------------------ text extraction

    /** Paragraphs per page of a chapter (cached on disk after the first extraction). */
    fun chapterParagraphs(chapter: Int): List<List<PdfParagraph>> {
        val cache = File(workDir, "text/$chapter.json")
        if (cache.isFile) {
            runCatching { return decode(cache.readText()) }
        }
        val pages = synchronized(textLock) {
            val doc = pdDoc ?: PDDocument.load(file).also { pdDoc = it }
            val c = collector ?: GlyphCollector().also { collector = it }
            pageRange(chapter).map { p ->
                runCatching {
                    val page = doc.getPage(p)
                    val box = page.cropBox
                    val (w, h) = if (page.rotation % 180 != 0) box.height to box.width else box.width to box.height
                    PdfLayout.analyze(c.page(doc, p), w, h)
                }.getOrElse { PdfPageText(false, emptyList()) }
            }
        }
        val merged = PdfLayout.mergePages(pages)
        cache.parentFile?.mkdirs()
        runCatching { cache.writeText(encode(merged)) }
        return merged
    }

    private fun encode(pages: List<List<PdfParagraph>>): String = JSONArray().apply {
        pages.forEach { page ->
            put(JSONArray().apply {
                page.forEach { p -> put(JSONObject().put("t", p.text).put("h", p.heading)) }
            })
        }
    }.toString()

    private fun decode(json: String): List<List<PdfParagraph>> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val page = arr.getJSONArray(i)
            (0 until page.length()).map { j ->
                val o = page.getJSONObject(j)
                PdfParagraph(o.getString("t"), heading = o.optBoolean("h"))
            }
        }
    }

    // ------------------------------------------------------------------ rendering

    /** JPEG of a page at the given pixel width (disk-cached). */
    fun pageImage(page: Int, width: Int): ByteArray {
        val cached = File(imageCacheDir, "${page}_$width.jpg")
        if (cached.isFile) runCatching { return cached.readBytes() }
        val bytes = synchronized(renderLock) {
            renderer.openPage(page).use { p ->
                val height = (width.toFloat() * p.height / p.width).roundToInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                bmp.recycle()
                out.toByteArray()
            }
        }
        runCatching {
            imageCacheDir.mkdirs()
            cached.writeBytes(bytes)
        }
        return bytes
    }

    private fun loadPageSizes(): List<Pair<Int, Int>> {
        val f = File(workDir, "pages.json")
        if (f.isFile) {
            runCatching {
                val arr = JSONArray(f.readText())
                if (arr.length() == pageCount) return (0 until arr.length()).map { arr.getJSONArray(it).let { a -> a.getInt(0) to a.getInt(1) } }
            }
        }
        val sizes = synchronized(renderLock) {
            (0 until pageCount).map { i -> renderer.openPage(i).use { it.width.coerceAtLeast(1) to it.height.coerceAtLeast(1) } }
        }
        runCatching { f.writeText(JSONArray(sizes.map { JSONArray(listOf(it.first, it.second)) }).toString()) }
        return sizes
    }

    // ------------------------------------------------------------------ outline

    private fun buildToc(): List<TocItem> {
        val f = File(workDir, "outline.json")
        val entries: List<Triple<String, Int, Int>> = if (f.isFile) {
            runCatching {
                val arr = JSONArray(f.readText())
                (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Triple(o.getString("t"), o.getInt("p"), o.getInt("d")) } }
            }.getOrDefault(emptyList())
        } else {
            val list = runCatching { readOutline() }.getOrDefault(emptyList())
            runCatching {
                f.writeText(JSONArray(list.map { (t, p, d) -> JSONObject().put("t", t).put("p", p).put("d", d) }).toString())
            }
            list
        }
        if (entries.isNotEmpty()) {
            return entries.map { (title, page, depth) -> TocItem(title, chapterOfPage(page), "yd-page-$page", depth) }
        }
        return chapters.map { TocItem(it.title.orEmpty(), it.index, null, 0) }
    }

    private fun readOutline(): List<Triple<String, Int, Int>> = synchronized(textLock) {
        val doc = pdDoc ?: PDDocument.load(file).also { pdDoc = it }
        val outline = doc.documentCatalog.documentOutline ?: return emptyList()
        val out = mutableListOf<Triple<String, Int, Int>>()
        fun walk(node: PDOutlineNode, depth: Int) {
            for (item in node.children()) {
                val title = item.title?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                val page = runCatching { item.findDestinationPage(doc)?.let { doc.pages.indexOf(it) } }.getOrNull() ?: -1
                if (title.isNotEmpty() && page >= 0) out += Triple(title, page, depth)
                if (depth < 4) walk(item, depth + 1)
                if (out.size > 2000) return
            }
        }
        walk(outline, 0)
        out
    }

    companion object {
        const val DIR = "__pdf"
        const val PAGES_PER_CHAPTER = 10
        private const val PAGE_SEPARATOR = '\u000C'
        /** Pages with fewer extracted characters are shown as images in reflow mode. */
        private const val IMAGE_PAGE_MAX_CHARS = 60

        fun chapterCount(pages: Int) = maxOf(1, (pages + PAGES_PER_CHAPTER - 1) / PAGES_PER_CHAPTER)

        /** Glyph-level analysis of the first pages, for import-time metadata. */
        fun sample(doc: PDDocument, pages: Int): List<PdfPageText> {
            val c = GlyphCollector()
            return (0 until minOf(pages, doc.numberOfPages)).map { p ->
                runCatching {
                    val box = doc.getPage(p).cropBox
                    PdfLayout.analyze(c.page(doc, p), box.width, box.height)
                }.getOrElse { PdfPageText(false, emptyList()) }
            }
        }
    }
}

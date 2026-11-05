package com.ruhuiliu.ereader.book.epub

import com.ruhuiliu.ereader.book.BookPaths
import com.ruhuiliu.ereader.book.xml.XElement
import com.ruhuiliu.ereader.book.xml.XmlDom

/** Read access to the files of an EPUB container. */
interface EntryReader {
    val entryNames: Collection<String>
    fun read(path: String): ByteArray?
    fun size(path: String): Long
}

data class ManifestItem(
    val id: String,
    val path: String,
    val mediaType: String,
    val properties: Set<String>,
)

data class EpubTocEntry(val title: String, val path: String, val fragment: String?, val depth: Int)

data class EpubPackage(
    val opfPath: String,
    val title: String,
    val author: String,
    val language: String?,
    val manifest: Map<String, ManifestItem>,
    val spine: List<ManifestItem>,
    val toc: List<EpubTocEntry>,
    val coverPath: String?,
    /** Spine page-progression-direction="rtl". */
    val rtl: Boolean,
    /** Text is laid out in vertical writing mode (tategaki). */
    val vertical: Boolean,
    val fixedLayout: Boolean,
)

class EpubFormatException(message: String) : Exception(message)

object EpubParser {
    private const val NS_OPS = "http://www.idpf.org/2007/ops"
    private val VERTICAL_CSS = Regex("writing-mode\\s*:\\s*(vertical-rl|tb-rl|vertical-lr)", RegexOption.IGNORE_CASE)
    private val IMG_SRC = Regex("<(?:img|image)\\b[^>]*?(?:src|xlink:href|href)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

    fun parse(reader: EntryReader): EpubPackage {
        val containerBytes = reader.read("META-INF/container.xml")
            ?: throw EpubFormatException("不是有效的 EPUB：缺少 META-INF/container.xml")
        val container = XmlDom.parse(containerBytes)
        val opfPath = container.descendants("rootfile")
            .firstOrNull { it.attr("media-type")?.contains("oebps-package") != false }
            ?.attr("full-path")
            ?.let { BookPaths.normalize(BookPaths.percentDecode(it)) }
            ?: throw EpubFormatException("不是有效的 EPUB：找不到 OPF 文件")
        val opfBytes = reader.read(opfPath) ?: throw EpubFormatException("找不到 OPF 文件：$opfPath")
        val opf = XmlDom.parse(opfBytes)

        val metadata = opf.child("metadata") ?: opf.descendants("metadata").firstOrNull()
        val title = metadata?.children("title")?.firstOrNull()?.text()?.trim().orEmpty()
        val author = metadata?.children("creator")?.joinToString(", ") { it.text().trim() }?.trim().orEmpty()
        val language = metadata?.children("language")?.firstOrNull()?.text()?.trim()?.ifEmpty { null }
        val metas = metadata?.children("meta").orEmpty()

        val manifestEl = opf.child("manifest") ?: opf.descendants("manifest").firstOrNull()
            ?: throw EpubFormatException("OPF 缺少 manifest")
        val manifest = LinkedHashMap<String, ManifestItem>()
        for (item in manifestEl.children("item")) {
            val id = item.attr("id") ?: continue
            val href = item.attr("href") ?: continue
            manifest[id] = ManifestItem(
                id = id,
                path = BookPaths.resolve(opfPath, href),
                mediaType = item.attr("media-type").orEmpty(),
                properties = item.attr("properties").orEmpty().split(' ').filter { it.isNotBlank() }.toSet(),
            )
        }

        val spineEl = opf.child("spine") ?: opf.descendants("spine").firstOrNull()
            ?: throw EpubFormatException("OPF 缺少 spine")
        val spine = spineEl.children("itemref").mapNotNull { manifest[it.attr("idref")] }
            .filter { it.mediaType.contains("html") || it.mediaType.contains("xml") || it.mediaType.isEmpty() }
        if (spine.isEmpty()) throw EpubFormatException("EPUB 没有可阅读的内容")
        val rtl = spineEl.attr("page-progression-direction").equals("rtl", ignoreCase = true)

        val fixedLayout = metas.any {
            it.attr("property") == "rendition:layout" && it.text().trim() == "pre-paginated"
        } || metas.any { it.attr("name") == "fixed-layout" && it.attr("content") == "true" }

        // ---- Table of contents: EPUB 3 nav document first, then NCX.
        var toc = emptyList<EpubTocEntry>()
        manifest.values.firstOrNull { "nav" in it.properties }?.let { nav ->
            toc = runCatching { reader.read(nav.path)?.let { parseNav(XmlDom.parse(it), nav.path) } }.getOrNull().orEmpty()
        }
        if (toc.isEmpty()) {
            val ncxItem = spineEl.attr("toc")?.let { manifest[it] }
                ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
            if (ncxItem != null) {
                toc = runCatching { reader.read(ncxItem.path)?.let { parseNcx(XmlDom.parse(it), ncxItem.path) } }.getOrNull().orEmpty()
            }
        }

        // ---- Cover image.
        val coverPath = manifest.values.firstOrNull { "cover-image" in it.properties }?.path
            ?: metas.firstOrNull { it.attr("name") == "cover" }?.attr("content")?.let { manifest[it] }
                ?.takeIf { it.mediaType.startsWith("image/") }?.path
            ?: manifest.values.firstOrNull { it.mediaType.startsWith("image/") && it.id.contains("cover", true) }?.path
            ?: manifest.values.firstOrNull { it.mediaType.startsWith("image/") && it.path.substringAfterLast('/').contains("cover", true) }?.path
            ?: firstImageOfDocument(reader, spine.first().path)

        // ---- Vertical writing detection.
        val metaVertical = metas.any {
            it.attr("name") == "primary-writing-mode" && it.attr("content").orEmpty().startsWith("vertical")
        }
        val cssVertical = metaVertical || manifest.values.asSequence()
            .filter { it.mediaType == "text/css" || it.path.endsWith(".css", true) }
            .take(20)
            .any { item -> reader.read(item.path)?.let { VERTICAL_CSS.containsMatchIn(String(it, Charsets.UTF_8)) } == true }
            || spine.take(3).any { item -> reader.read(item.path)?.let { VERTICAL_CSS.containsMatchIn(String(it, Charsets.UTF_8)) } == true }
        val cjk = language?.lowercase()?.let { it.startsWith("ja") || it.startsWith("zh") } == true
        val vertical = cssVertical || (rtl && cjk)

        return EpubPackage(
            opfPath = opfPath,
            title = title,
            author = author,
            language = language,
            manifest = manifest,
            spine = spine,
            toc = toc,
            coverPath = coverPath,
            rtl = rtl,
            vertical = vertical,
            fixedLayout = fixedLayout,
        )
    }

    private fun firstImageOfDocument(reader: EntryReader, docPath: String): String? {
        val html = reader.read(docPath)?.toString(Charsets.UTF_8) ?: return null
        val src = IMG_SRC.find(html)?.groupValues?.get(1) ?: return null
        return BookPaths.resolve(docPath, src).takeIf { reader.read(it) != null }
    }

    internal fun parseNav(root: XElement, navPath: String): List<EpubTocEntry> {
        val navs = root.descendants("nav").toList()
        val tocNav = navs.firstOrNull { (it.attr(NS_OPS, "type") ?: it.attr("type")).orEmpty().split(' ').contains("toc") }
            ?: navs.firstOrNull() ?: return emptyList()
        val list = tocNav.descendants("ol").firstOrNull() ?: tocNav.descendants("ul").firstOrNull() ?: return emptyList()
        val out = mutableListOf<EpubTocEntry>()
        fun walk(ol: XElement, depth: Int) {
            for (li in ol.elements().filter { it.name == "li" }) {
                val a = li.child("a") ?: li.descendants("a").firstOrNull()
                val label = (a ?: li.child("span"))?.text()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                val href = a?.attr("href")
                if (label.isNotEmpty() && !href.isNullOrBlank()) {
                    val (_, frag) = BookPaths.splitFragment(href)
                    out += EpubTocEntry(label, BookPaths.resolve(navPath, href), frag, depth)
                }
                li.elements().filter { it.name == "ol" || it.name == "ul" }.forEach { walk(it, depth + 1) }
            }
        }
        walk(list, 0)
        return out
    }

    internal fun parseNcx(root: XElement, ncxPath: String): List<EpubTocEntry> {
        val navMap = root.descendants("navMap").firstOrNull() ?: return emptyList()
        val out = mutableListOf<EpubTocEntry>()
        fun walk(parent: XElement, depth: Int) {
            for (np in parent.children("navPoint")) {
                val label = np.child("navLabel")?.child("text")?.text()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                val src = np.child("content")?.attr("src")
                if (label.isNotEmpty() && !src.isNullOrBlank()) {
                    val (_, frag) = BookPaths.splitFragment(src)
                    out += EpubTocEntry(label, BookPaths.resolve(ncxPath, src), frag, depth)
                }
                walk(np, depth + 1)
            }
        }
        walk(navMap, 0)
        return out
    }
}

package com.ruhuiliu.ereader.book

import java.io.Closeable

enum class BookFormat { EPUB, TXT, HTML }

/** One reading-order document (EPUB spine item / TXT chapter). */
data class Chapter(
    val index: Int,
    /** Path inside the book container, used to build the resource URL. */
    val path: String,
    val mediaType: String,
    /** Approximate size in bytes, used to weight the overall progress. */
    val size: Long,
    val title: String?,
)

data class TocItem(
    val title: String,
    val chapterIndex: Int,
    val fragment: String?,
    val depth: Int,
)

/** An opened book whose chapters and resources can be served to the reader WebView. */
interface BookContent : Closeable {
    val chapters: List<Chapter>
    val toc: List<TocItem>

    /** Raw bytes of a resource inside the book, or null if missing. */
    fun read(path: String): ByteArray?

    fun mimeType(path: String): String = BookPaths.mimeTypeForPath(path)

    /** Plain text of a chapter (ruby annotations removed), used for full-text search. */
    fun chapterText(index: Int): String

    /** Index of the chapter whose path equals [path], or -1. */
    fun chapterIndexOf(path: String): Int {
        val norm = BookPaths.normalize(path)
        return chapters.indexOfFirst { it.path == norm }.takeIf { it >= 0 }
            ?: chapters.indexOfFirst { it.path.equals(norm, ignoreCase = true) }
    }

    override fun close() {}
}

/** Shared HTML helpers. */
object HtmlText {
    private val RUBY_ANNOTATION = Regex("<(rt|rp)\\b[^>]*>.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val HEAD = Regex("<head\\b.*?</head\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val SCRIPT_STYLE = Regex("<(script|style)\\b.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val BLOCK_BREAK = Regex("<(br|/p|/div|/h[1-6]|/li|/tr|/blockquote|/section)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]*>")
    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]*);")
    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0",
        "hellip" to "…", "mdash" to "—", "ndash" to "–", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“",
        "rdquo" to "”", "middot" to "·", "copy" to "©", "laquo" to "«", "raquo" to "»",
    )

    fun escape(s: String): String = buildString(s.length + 16) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }

    fun decodeEntities(s: String): String = ENTITY.replace(s) { m ->
        val v = m.groupValues[1]
        when {
            v.startsWith("#x") || v.startsWith("#X") -> v.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
            v.startsWith("#") -> v.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
            else -> NAMED[v]
        } ?: m.value
    }

    /** Visible text of an (X)HTML document body, keeping paragraph breaks as '\n'. */
    fun toPlainText(html: String): String {
        var s = HEAD.replace(html, "")
        s = SCRIPT_STYLE.replace(s, "")
        s = RUBY_ANNOTATION.replace(s, "")
        s = BLOCK_BREAK.replace(s, "\n")
        s = TAG.replace(s, "")
        return decodeEntities(s)
    }

    /** Title of an (X)HTML document, if any. */
    fun title(html: String): String? =
        Regex("<title\\b[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1)?.let { decodeEntities(TAG.replace(it, "")).trim() }?.ifEmpty { null }
}

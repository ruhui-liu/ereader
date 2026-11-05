package com.ruhuiliu.ereader.book.pdf

import kotlin.math.abs
import kotlin.math.max

/** A positioned piece of text on a PDF page. Coordinates are in points, origin top-left, y grows downward. */
data class Glyph(
    val text: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val size: Float,
)

data class PdfParagraph(
    val text: String,
    val heading: Boolean = false,
    /** The first line is indented: this starts a new paragraph rather than continuing one. */
    val indented: Boolean = false,
    /** The last line stops well before the end of the column: the paragraph really ends here. */
    val endsShort: Boolean = false,
    /** A single line in the top/bottom margin (running header or footer candidate). */
    val band: Boolean = false,
)

data class PdfPageText(val vertical: Boolean, val paragraphs: List<PdfParagraph>)

/**
 * Rebuilds reading-order paragraphs from glyph positions, for both horizontal and vertical (tategaki) pages.
 * Page numbers are dropped, hyphenated line breaks are joined, and CJK lines are joined without spaces.
 */
object PdfLayout {
    private const val BAND = 0.07f

    /** Glyph in a direction-normalized frame: u runs along the line, v across lines (top→bottom or right→left). */
    private class G(val text: String, val u: Float, val v: Float, val uw: Float, val vh: Float, val size: Float)

    private class Line(val glyphs: List<G>) {
        val start = glyphs.minOf { it.u }
        val end = glyphs.maxOf { it.u + it.uw }
        val v = glyphs.map { it.v }.median()
        val size = glyphs.map { it.size }.median()
        var text = ""
        var band = false
    }

    fun analyze(glyphs: List<Glyph>, pageW: Float, pageH: Float): PdfPageText {
        val cleaned = glyphs.filter { g -> g.text.isNotEmpty() && g.text.any { !it.isISOControl() } && g.size > 0f }
        if (cleaned.isEmpty()) return PdfPageText(false, emptyList())
        val body = removePageNumbers(cleaned, pageH)
        if (body.isEmpty()) return PdfPageText(false, emptyList())
        val vertical = isVertical(body)
        val norm = body.map { g ->
            if (vertical) G(g.text, g.y, pageW - (g.x + g.w), g.h, g.w, g.size)
            else G(g.text, g.x, g.y, g.w, g.h, g.size)
        }
        val lines = buildLines(norm)
        for (l in lines) {
            l.text = lineText(l.glyphs)
            if (!vertical) {
                val top = l.glyphs.minOf { it.v }
                val bottom = l.glyphs.maxOf { it.v + it.vh }
                l.band = bottom < pageH * BAND * 1.4f || top > pageH * (1 - BAND * 1.4f)
            }
        }
        return PdfPageText(vertical, paragraphs(lines.filter { it.text.isNotBlank() }))
    }

    // ------------------------------------------------------------------ page numbers

    private val PAGE_NUMBER = Regex(
        "^(?:(?:page|p\\.?|第)\\s*)?(?:[0-9０-９]{1,5}|[ivxlcdm]{1,7})(?:\\s*(?:页|頁|ページ))?(?:\\s*(?:/|／|of)\\s*[0-9０-９]{1,5})?$",
        RegexOption.IGNORE_CASE,
    )

    private fun isPageNumber(text: String): Boolean {
        val t = text.trim().trim('-', '–', '—', '·', '•', '|', '(', ')', '[', ']', '（', '）', '〔', '〕', ' ', '\u3000').trim()
        return t.isNotEmpty() && PAGE_NUMBER.matches(t)
    }

    /** Removes page numbers printed in the top/bottom margins (as raw horizontal runs). */
    private fun removePageNumbers(glyphs: List<Glyph>, pageH: Float): List<Glyph> {
        val inBand = glyphs.filter { it.y + it.h < pageH * BAND * 1.4f || it.y > pageH * (1 - BAND * 1.4f) }
        if (inBand.isEmpty()) return glyphs
        val drop = HashSet<Glyph>()
        val sorted = inBand.sortedWith(compareBy({ it.y }, { it.x }))
        var group = mutableListOf<Glyph>()
        fun flush() {
            if (group.isEmpty()) return
            val text = group.sortedBy { it.x }.joinToString("") { it.text }
            if (isPageNumber(text)) drop.addAll(group)
            group = mutableListOf()
        }
        for (g in sorted) {
            val last = group.lastOrNull()
            if (last != null && (abs(g.y - last.y) > 0.5f * max(g.size, last.size) || g.x - (last.x + last.w) > 3 * g.size)) flush()
            group.add(g)
        }
        flush()
        return if (drop.isEmpty()) glyphs else glyphs.filter { it !in drop }
    }

    // ------------------------------------------------------------------ direction

    internal fun isVertical(glyphs: List<Glyph>): Boolean {
        var vert = 0
        var horiz = 0
        for (i in 1 until glyphs.size) {
            val a = glyphs[i - 1]
            val b = glyphs[i]
            val s = max(a.size, b.size)
            val dx = b.x - a.x
            val dy = b.y - a.y
            if (abs(dx) > 2 * s || abs(dy) > 2 * s) continue
            if (dy > 0 && abs(dy) > abs(dx) * 2) vert++
            else if (dx > 0 && abs(dx) > abs(dy) * 2) horiz++
        }
        return vert >= 8 && vert > horiz * 1.5
    }

    // ------------------------------------------------------------------ lines

    private fun buildLines(glyphs: List<G>): List<Line> {
        val groups = mutableListOf<MutableList<G>>()
        var cur = mutableListOf<G>()
        for (g in glyphs) {
            val prev = cur.lastOrNull()
            val sameLine = prev != null &&
                abs(g.v - prev.v) < 0.5f * max(g.size, prev.size) &&
                g.u > prev.u - prev.size * 0.6f &&
                g.u - (prev.u + prev.uw) < 3f * max(g.size, prev.size)
            if (prev != null && !sameLine) {
                groups += cur
                cur = mutableListOf()
            }
            cur.add(g)
        }
        if (cur.isNotEmpty()) groups += cur

        // Content order is usually reading order. If it clearly isn't (many lines going backwards
        // without a column change), fall back to sorting lines by position.
        var lines = groups.map { Line(it.sortedBy { g -> g.u }) }
        var backwards = 0
        for (i in 1 until lines.size) {
            val a = lines[i - 1]
            val b = lines[i]
            val columnJump = b.start > a.end + 2 * a.size || b.end < a.start - 2 * a.size
            if (b.v < a.v - 0.5f * a.size && !columnJump) backwards++
        }
        if (lines.size >= 4 && backwards > lines.size * 0.3) {
            lines = lines.sortedWith(compareBy({ it.v }, { it.start }))
        }
        return mergeSameBaseline(lines)
    }

    /** Fragments of one visual line emitted separately (e.g. a word drawn later) are put back together. */
    private fun mergeSameBaseline(lines: List<Line>): List<Line> {
        val out = mutableListOf<Line>()
        for (l in lines) {
            val p = out.lastOrNull()
            if (p != null && abs(p.v - l.v) < 0.3f * p.size && l.start >= p.end - p.size * 0.3f && l.start - p.end < 3 * p.size) {
                out[out.size - 1] = Line((p.glyphs + l.glyphs).sortedBy { it.u })
            } else {
                out.add(l)
            }
        }
        return out
    }

    internal fun isCjk(c: Char): Boolean {
        val code = c.code
        return code in 0x2E80..0x9FFF || code in 0xAC00..0xD7AF || code in 0xF900..0xFAFF ||
            code in 0xFE30..0xFE4F || code in 0xFF00..0xFFEF || code in 0x3000..0x303F
    }

    private fun lineText(glyphs: List<G>): String {
        val sb = StringBuilder()
        var prev: G? = null
        for (g in glyphs) {
            val t = g.text
            if (prev != null) {
                val gap = g.u - (prev.u + prev.uw)
                val last = sb.lastOrNull()
                val first = t.first()
                val cjk = (last != null && isCjk(last)) || isCjk(first)
                val threshold = if (cjk) 0.8f * g.size else 0.15f * g.size
                if (gap > threshold && last != null && !last.isWhitespace() && !first.isWhitespace()) sb.append(' ')
            }
            sb.append(t)
            prev = g
        }
        return sb.toString().replace(Regex("[ \\t\\u00A0]+"), " ").trim()
    }

    // ------------------------------------------------------------------ paragraphs

    private val HYPHENS = setOf('-', '\u00AD', '\u2010', '\u2011')
    private val CJK_OPENING = setOf('「', '『', '（', '“', '【', '〈', '《', '―', '—')
    private val CJK_CLOSING = setOf('。', '！', '？', '」', '』', '…', '）', '”')

    private val TERMINAL = setOf('.', '!', '?', '。', '！', '？', '」', '』', '”', '"', '）', ')', '…', ':', '：', '．', '’', '\'')

    private fun paragraphs(lines: List<Line>): List<PdfParagraph> {
        if (lines.isEmpty()) return emptyList()
        val bodyLines = lines.filter { !it.band }
        val bodySize = (bodyLines.ifEmpty { lines }).flatMap { l -> List(l.text.length.coerceAtMost(200)) { l.size } }.median()
        val gaps = (1 until lines.size).map { lines[it].v - lines[it - 1].v }
            .filter { it > 0.5f * bodySize && it < 3f * bodySize }
        val lineGap = if (gaps.isNotEmpty()) gaps.median() else bodySize * 1.4f

        fun sameColumn(a: Line, b: Line) = abs(a.start - b.start) < 6 * bodySize || abs(a.end - b.end) < 6 * bodySize

        fun windowOf(i: Int): List<Line> = (maxOf(0, i - 3)..minOf(lines.size - 1, i + 3))
            .map { lines[it] }
            .filter { !it.band && abs(it.size - lines[i].size) < 0.15f * bodySize && sameColumn(it, lines[i]) }

        fun indented(i: Int): Boolean {
            val l = lines[i]
            val ref = windowOf(i).filter { it !== l }.minOfOrNull { it.start } ?: return false
            return l.start > ref + 0.8f * l.size && l.start < ref + 8 * l.size
        }

        fun short(i: Int): Boolean {
            val l = lines[i]
            val w = windowOf(i)
            val maxEnd = w.maxOfOrNull { it.end } ?: return false
            val minStart = w.minOfOrNull { it.start } ?: l.start
            val width = maxEnd - minStart
            if (w.size < 2 || width <= 0f) return false
            val last = l.text.lastOrNull() ?: return false
            val strong = l.end < maxEnd - 0.3f * width
            val weak = l.end < maxEnd - 1.5f * l.size && (isCjk(last) || last in TERMINAL)
            return strong || weak
        }

        val out = mutableListOf<PdfParagraph>()
        var cur = mutableListOf<Int>()
        fun flush() {
            if (cur.isEmpty()) return
            val ls = cur.map { lines[it] }
            val text = joinLines(ls.map { it.text })
            val size = ls.map { it.size }.median()
            val band = ls.size == 1 && ls[0].band
            out += PdfParagraph(
                text = text,
                heading = !band && size > bodySize * 1.15f && text.length <= 120,
                indented = indented(cur.first()),
                endsShort = short(cur.last()),
                band = band,
            )
            cur = mutableListOf()
        }
        for (i in lines.indices) {
            if (cur.isNotEmpty()) {
                val prev = lines[i - 1]
                val l = lines[i]
                val gap = l.v - prev.v
                val newPara = l.band || prev.band ||
                    abs(l.size - prev.size) > 0.15f * bodySize ||
                    (gap > 1.6f * lineGap && gap < 20 * lineGap) ||
                    indented(i) ||
                    short(i - 1) ||
                    // CJK dialogue: a line opening with 「 after a finished sentence starts a new paragraph.
                    (prev.text.lastOrNull() in CJK_CLOSING && l.text.firstOrNull() in CJK_OPENING)
                if (newPara) flush()
            }
            cur.add(i)
        }
        flush()
        return out
    }

    /** Joins the lines of one paragraph: de-hyphenates Latin words, no spaces between CJK characters. */
    fun joinLines(lines: List<String>): String {
        val sb = StringBuilder()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (sb.isEmpty()) {
                sb.append(line)
                continue
            }
            val last = sb.last()
            val first = line.first()
            when {
                last in HYPHENS && sb.length >= 2 && sb[sb.length - 2].isLetter() && first.isLowerCase() -> {
                    sb.setLength(sb.length - 1)
                    sb.append(line)
                }
                // "well-" + "Known": a real hyphen at the line end, keep it without a space.
                last in HYPHENS && sb.length >= 2 && sb[sb.length - 2].isLetter() -> sb.append(line)
                isCjk(last) || isCjk(first) -> sb.append(line)
                else -> sb.append(' ').append(line)
            }
        }
        return sb.toString()
    }

    /**
     * Merges paragraphs across page breaks: a paragraph that runs to the end of the column on one page and
     * continues unindented on the next page is joined. Returns, for each page, the paragraphs it starts.
     * Repeated margin lines (running headers) across pages are removed.
     */
    fun mergePages(pages: List<PdfPageText>): List<List<PdfParagraph>> {
        val bandCounts = pages.flatMap { p -> p.paragraphs.filter { it.band }.map { normalizeBand(it.text) }.distinct() }
            .groupingBy { it }.eachCount()
        val cleaned = pages.map { p ->
            p.paragraphs.filter { !(it.band && (bandCounts[normalizeBand(it.text)] ?: 0) >= 2) }
        }
        val result = cleaned.map { it.toMutableList() }
        for (i in 1 until result.size) {
            val prevPage = result.subList(0, i).lastOrNull { it.isNotEmpty() } ?: continue
            val next = result[i].firstOrNull() ?: continue
            val prev = prevPage.last()
            val prevEndsSentence = prev.text.lastOrNull()?.let { it in TERMINAL } == true
            if (!prev.endsShort && !prevEndsSentence && !prev.heading && !prev.band && !next.indented && !next.heading && !next.band) {
                prevPage[prevPage.size - 1] = prev.copy(text = joinLines(listOf(prev.text, next.text)), endsShort = next.endsShort)
                result[i].removeAt(0)
            }
        }
        return result
    }

    private fun normalizeBand(s: String) = s.replace(Regex("[0-9０-９]+"), "#").replace(Regex("\\s+"), " ").trim()

    private fun List<Float>.median(): Float {
        if (isEmpty()) return 0f
        val s = sorted()
        return s[s.size / 2]
    }
}

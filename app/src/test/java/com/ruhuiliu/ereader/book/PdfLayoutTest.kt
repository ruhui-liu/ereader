package com.ruhuiliu.ereader.book

import com.ruhuiliu.ereader.book.pdf.Glyph
import com.ruhuiliu.ereader.book.pdf.PdfLayout
import com.ruhuiliu.ereader.book.pdf.PdfPageText
import com.ruhuiliu.ereader.book.pdf.PdfParagraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfLayoutTest {
    private val pageW = 400f
    private val pageH = 600f

    /** Latin line: one glyph per char (no space glyphs), optionally justified to end at [justifyTo]. */
    private fun latin(text: String, x: Float, y: Float, size: Float = 10f, justifyTo: Float? = null): List<Glyph> {
        val cw = size * 0.5f
        val words = text.split(' ')
        val natural = text.replace(" ", "").length * cw + (words.size - 1) * size * 0.3f
        val extra = if (justifyTo != null && words.size > 1) (justifyTo - x - natural) / (words.size - 1) else 0f
        val out = mutableListOf<Glyph>()
        var cx = x
        words.forEachIndexed { i, w ->
            for (c in w) {
                out += Glyph(c.toString(), cx, y, cw, size, size)
                cx += cw
            }
            if (i < words.size - 1) cx += size * 0.3f + extra
        }
        return out
    }

    /** Vertical CJK column starting at the top [y], at column x [x] (glyph box left edge). */
    private fun column(text: String, x: Float, y: Float, size: Float = 12f): List<Glyph> =
        text.mapIndexed { i, c -> Glyph(c.toString(), x, y + i * size, size, size, size) }

    @Test
    fun englishParagraphsWithHyphenationAndPageNumber() {
        val right = 360f
        val g = mutableListOf<Glyph>()
        g += latin("The quick brown fox jumps over the lazy dog and keeps running until the", 40f, 60f, justifyTo = right)
        g += latin("evening comes over the quiet hills, where an exam- ple of hyphenation", 40f, 74f, justifyTo = right)
        g += latin("ple shows up.", 40f, 88f)
        g += latin("A second paragraph starts with an indent and runs on for a while", 60f, 102f, justifyTo = right)
        g += latin("until it finally ends here.", 40f, 116f)
        g += latin("12", 196f, 570f) // page number in the bottom margin
        // Fix the deliberate hyphen: line 2 ends with "exam-" and line 3 starts with "ple".
        val page = PdfLayout.analyze(g.filterNot { it.text == "p" && it.y == 74f && it.x > 300f }, pageW, pageH)
        assertFalse(page.vertical)
        assertEquals(2, page.paragraphs.size)
        assertTrue(page.paragraphs[0].text.startsWith("The quick brown fox"))
        assertTrue(page.paragraphs[0].text.endsWith("shows up."))
        assertTrue(page.paragraphs[0].text.contains("until the evening"))
        assertTrue(page.paragraphs[1].indented)
        assertTrue(page.paragraphs.none { it.text.contains("12") })
    }

    @Test
    fun joinsHyphenatedWords() {
        assertEquals("an example of it", PdfLayout.joinLines(listOf("an exam-", "ple of it")))
        assertEquals("well-Known", PdfLayout.joinLines(listOf("well-", "Known")))
        assertEquals("吾輩は猫である。名前はまだ無い。", PdfLayout.joinLines(listOf("吾輩は猫である。", "名前はまだ無い。")))
        assertEquals("Hello world", PdfLayout.joinLines(listOf("Hello", "world")))
    }

    @Test
    fun verticalJapaneseColumnsRightToLeft() {
        val top = 50f
        val g = mutableListOf<Glyph>()
        // Paragraph 1: two full columns + a short one. Columns go right to left.
        g += column("吾輩は猫である。名前はまだ無い。どこで生", 340f, top)
        g += column("れたかとんと見当がつかぬ。何でも薄暗いじ", 322f, top)
        g += column("めじめした所で泣いていた。", 304f, top)
        // Paragraph 2: indented by one character.
        g += column("吾輩はここで始めて人間というものを見た。", 286f, top + 12f)
        g += column("しかもあとで聞くとそれは書生という。", 268f, top)
        val page = PdfLayout.analyze(g, pageW, pageH)
        assertTrue(page.vertical)
        assertEquals(2, page.paragraphs.size)
        assertEquals("吾輩は猫である。名前はまだ無い。どこで生れたかとんと見当がつかぬ。何でも薄暗いじめじめした所で泣いていた。", page.paragraphs[0].text)
        assertTrue(page.paragraphs[1].text.startsWith("吾輩はここで始めて"))
        assertTrue(page.paragraphs[1].indented)
    }

    @Test
    fun largerTextBecomesHeading() {
        val g = mutableListOf<Glyph>()
        g += latin("Chapter One", 120f, 60f, size = 20f)
        g += latin("It was a bright cold day in April, and the clocks were striking", 40f, 100f, justifyTo = 360f)
        g += latin("thirteen.", 40f, 114f)
        val page = PdfLayout.analyze(g, pageW, pageH)
        assertEquals(2, page.paragraphs.size)
        assertTrue(page.paragraphs[0].heading)
        assertEquals("Chapter One", page.paragraphs[0].text)
        assertFalse(page.paragraphs[1].heading)
    }

    @Test
    fun mergesParagraphSplitByPageBreakAndDropsRunningHeaders() {
        val p1 = PdfPageText(
            false, listOf(
                PdfParagraph("MY BOOK", band = true),
                PdfParagraph("First paragraph.", endsShort = true),
                PdfParagraph("This sentence continues on", endsShort = false),
            )
        )
        val p2 = PdfPageText(
            false, listOf(
                PdfParagraph("MY BOOK", band = true),
                PdfParagraph("the next page.", indented = false, endsShort = true),
                PdfParagraph("New paragraph.", indented = true, endsShort = true),
            )
        )
        val merged = PdfLayout.mergePages(listOf(p1, p2))
        assertEquals(listOf("First paragraph.", "This sentence continues on the next page."), merged[0].map { it.text })
        assertEquals(listOf("New paragraph."), merged[1].map { it.text })
    }

    @Test
    fun emptyPageHasNoParagraphs() {
        assertTrue(PdfLayout.analyze(emptyList(), pageW, pageH).paragraphs.isEmpty())
    }
}

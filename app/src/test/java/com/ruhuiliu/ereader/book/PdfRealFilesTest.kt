package com.ruhuiliu.ereader.book

import com.ruhuiliu.ereader.book.pdf.Glyph
import com.ruhuiliu.ereader.book.pdf.PdfLayout
import com.ruhuiliu.ereader.book.pdf.PdfPageText
import com.ruhuiliu.ereader.book.pdf.PdfParagraph
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

/**
 * Runs the layout analysis on glyphs extracted by desktop PDFBox (same TextPosition API as PdfBox-Android)
 * from PDFs printed by Chromium: English, Japanese horizontal, Japanese vertical.
 */
class PdfRealFilesTest {
    private class Collector : PDFTextStripper() {
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
    }

    private fun extract(name: String, pages: Int = 3): Pair<List<PdfPageText>, List<List<PdfParagraph>>> {
        val bytes = javaClass.getResourceAsStream("/pdf/$name")!!.readBytes()
        PDDocument.load(bytes).use { doc ->
            val c = Collector()
            val result = (0 until minOf(pages, doc.numberOfPages)).map { p ->
                c.glyphs.clear()
                c.startPage = p + 1
                c.endPage = p + 1
                c.writeText(doc, StringWriter())
                val box = doc.getPage(p).cropBox
                PdfLayout.analyze(c.glyphs.toList(), box.width, box.height)
            }
            return result to PdfLayout.mergePages(result)
        }
    }

    private fun dump(name: String, merged: List<List<PdfParagraph>>) {
        println("######## $name")
        merged.forEachIndexed { i, ps ->
            println("=== page ${i + 1}")
            ps.forEach { println((if (it.heading) "[H] " else if (it.band) "[B] " else "") + it.text.take(160)) }
        }
    }

    @Test
    fun english() {
        val (pages, merged) = extract("en.pdf")
        dump("en", merged)
        assertFalse(pages[0].vertical)
        val all = merged.flatten()
        assertEquals("Chapter One", all[0].text)
        assertTrue(all[0].heading)
        // Running header and page numbers are gone; every paragraph is reassembled in one piece.
        assertTrue(all.none { it.text.contains("TALE OF TWO CITIES") })
        val bodies = all.drop(1)
        assertEquals((1..14).map { "Paragraph $it." }, bodies.map { it.text.substringBefore(" It was").substringAfter("old man, ") })
        bodies.forEach { assertTrue(it.text, it.text.endsWith("we had nothing before us.")) }
        val hyphenSpace = "${Char(0x2010)} "
        assertTrue(all.none { it.text.contains(hyphenSpace) || it.text.contains("- ") })
    }

    @Test
    fun japaneseHorizontal() {
        val (pages, merged) = extract("ja_h.pdf")
        dump("ja_h", merged)
        assertFalse(pages[0].vertical)
        val all = merged.flatten().filter { !it.heading }
        assertEquals(24, all.size)
        assertTrue(all[0].text.contains("吾輩は猫である。"))
        assertEquals("「名前は？」と彼は聞いた。", all[1].text)
    }

    @Test
    fun japaneseVertical() {
        val (pages, merged) = extract("ja_v.pdf")
        dump("ja_v", merged)
        assertTrue(pages[0].vertical)
        val all = merged.flatten().map { it.text }
        assertEquals(12, all.count { it == "「名前は？」と彼は聞いた。" })
        val body = all.filter { it.startsWith("吾輩は猫である。") }
        assertEquals(12, body.size)
        body.forEach { assertTrue(it, it.endsWith("種族であったそうだ。")) }
    }
}

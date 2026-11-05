package com.ruhuiliu.ereader.book

import com.ruhuiliu.ereader.book.txt.TextDecoding
import com.ruhuiliu.ereader.book.txt.TxtBook
import com.ruhuiliu.ereader.book.txt.TxtChapterSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.Charset

class TxtTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun body(n: Int, seed: String) = (1..n).joinToString("\n") { "　　$seed 第${it}行的正文内容，这里有足够多的文字。" }

    @Test
    fun splitsChineseChaptersAndSkipsTableOfContents() {
        val text = buildString {
            append("书名：测试小说\n作者：某人\n\n")
            append("目录\n第一章 开端\n第二章 发展\n第三章 结局\n\n") // a TOC listing: must not create empty chapters
            append("第一章 开端\n").append(body(5, "甲")).append('\n')
            append("第二章 发展\n").append(body(5, "乙")).append('\n')
            append("第三章 结局\n").append(body(5, "丙")).append('\n')
        }
        val parts = TxtChapterSplitter.split(text)
        assertEquals(listOf("开篇", "第一章 开端", "第二章 发展", "第三章 结局"), parts.map { it.title })
        assertTrue(text.substring(parts[1].start, parts[1].end).contains("甲 第5行"))
        assertEquals(text.length, parts.last().end)
    }

    @Test
    fun recognisesJapaneseAndEnglishHeadings() {
        assertTrue(TxtChapterSplitter.isHeading("第3話　始まりの日"))
        assertTrue(TxtChapterSplitter.isHeading("プロローグ"))
        assertTrue(TxtChapterSplitter.isHeading("Chapter 12: The Return"))
        assertTrue(TxtChapterSplitter.isHeading("CHAPTER XII"))
        assertTrue(TxtChapterSplitter.isHeading("Prologue"))
        assertFalse(TxtChapterSplitter.isHeading("Part of the problem was the weather."))
        assertFalse(TxtChapterSplitter.isHeading("Book did not matter to him."))
        assertFalse(TxtChapterSplitter.isHeading("他走进了第一章所描述的那个房间，然后坐了下来，一句话也没有说，只是静静地看着窗外的雨。"))
    }

    @Test
    fun splitsUnstructuredTextIntoChunks() {
        val text = (1..3000).joinToString("\n") { "Line number $it of a long text without any headings." }
        val parts = TxtChapterSplitter.split(text)
        assertTrue(parts.size > 5)
        assertEquals(0, parts.first().start)
        assertEquals(text.length, parts.last().end)
        parts.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start) }
    }

    @Test
    fun detectsCommonEncodings() {
        val zh = "第一章 天下大势，分久必合，合久必分。周末七国分争，并入于秦。"
        val ja = "吾輩は猫である。名前はまだ無い。どこで生れたかとんと見当がつかぬ。"
        assertEquals(zh, TextDecoding.decode(zh.toByteArray(Charset.forName("GB18030"))))
        assertEquals(ja, TextDecoding.decode(ja.toByteArray(Charset.forName("Shift_JIS"))))
        assertEquals(ja, TextDecoding.decode(ja.toByteArray(Charsets.UTF_8)))
        assertEquals(zh, TextDecoding.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + zh.toByteArray(Charsets.UTF_16LE)))
        assertEquals("a\nb\nc", TextDecoding.decode("a\r\nb\rc".toByteArray()))
    }

    @Test
    fun writesAndReadsTxtBook() {
        val text = "第一章 开端\n" + body(3, "甲") + "\n第二章 发展\n" + body(3, "乙") + "\n第三章 <结局>\n" + body(3, "丙")
        val textFile = tmp.newFile("book.txt")
        val index = tmp.newFile("chapters.json")
        TxtBook.write(text, textFile, index)
        val book = TxtBook(textFile, index)
        assertEquals(3, book.chapters.size)
        assertEquals("第三章 <结局>", book.toc[2].title)
        val html = String(book.read("__txt/2.html")!!, Charsets.UTF_8)
        assertTrue(html.contains("<h2 class=\"yd-txt-title\">第三章 &lt;结局&gt;</h2>"))
        assertTrue(html.contains("<p>丙 第1行"))
        assertTrue(book.chapterText(1).startsWith("第二章 发展"))
    }

    @Test
    fun guessesLanguageFromScript() {
        assertEquals("ja", LanguageGuess.guess("吾輩は猫である。名前はまだ無い。"))
        assertEquals("zh", LanguageGuess.guess("天下大势，分久必合，合久必分。"))
        assertEquals("en", LanguageGuess.guess("It was the best of times, it was the worst of times."))
        assertEquals("ko", LanguageGuess.guess("나는 고양이로소이다. 이름은 아직 없다."))
        assertTrue(LanguageGuess.alreadyInTarget("第一章", "zh-CN"))
        assertFalse(LanguageGuess.alreadyInTarget("名前はまだ無い", "zh-CN"))
        assertTrue(LanguageGuess.isUntranslatable("＊　＊　＊"))
    }

    @Test
    fun plainTextDropsRubyAnnotations() {
        val html = "<html><head><title>T</title></head><body><p><ruby>吾輩<rt>わがはい</rt></ruby>は猫&amp;犬</p><p>二</p></body></html>"
        val text = HtmlText.toPlainText(html)
        assertTrue(text.contains("吾輩は猫&犬"))
        assertFalse(text.contains("わがはい"))
        assertEquals("T", HtmlText.title(html))
    }
}

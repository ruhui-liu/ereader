package com.ruhuiliu.ereader.book

import org.junit.Assert.assertEquals
import org.junit.Test

class BookPathsTest {
    @Test
    fun resolvesRelativeHrefs() {
        assertEquals("OEBPS/Images/a b.jpg", BookPaths.resolve("OEBPS/Text/ch1.xhtml", "../Images/a%20b.jpg#frag"))
        assertEquals("OEBPS/Text/ch2.xhtml", BookPaths.resolve("OEBPS/Text/ch1.xhtml", "ch2.xhtml"))
        assertEquals("OEBPS/Text/ch1.xhtml", BookPaths.resolve("OEBPS/Text/ch1.xhtml", "#note1"))
        assertEquals("Styles/x.css", BookPaths.resolve("OEBPS/content.opf", "/Styles/x.css"))
        assertEquals("a+b.xhtml", BookPaths.resolve("content.opf", "a+b.xhtml"))
    }

    @Test
    fun splitsFragment() {
        assertEquals("a.xhtml" to "x", BookPaths.splitFragment("a.xhtml#x"))
        assertEquals("a.xhtml" to null, BookPaths.splitFragment("a.xhtml"))
    }

    @Test
    fun encodesPathSegmentsForUrls() {
        assertEquals("OEBPS/%E6%97%A5%E6%9C%AC%20%E8%AA%9E.xhtml", BookPaths.encodePath("OEBPS/日本 語.xhtml"))
        assertEquals("OEBPS/日本 語.xhtml", BookPaths.percentDecode(BookPaths.encodePath("OEBPS/日本 語.xhtml")))
        assertEquals("__txt/3.html", BookPaths.encodePath("__txt/3.html"))
    }
}

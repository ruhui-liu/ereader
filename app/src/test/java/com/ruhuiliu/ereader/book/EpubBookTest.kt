package com.ruhuiliu.ereader.book

import com.ruhuiliu.ereader.TestXml
import com.ruhuiliu.ereader.book.epub.EpubBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubBookTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() = TestXml.install()

    private fun zip(file: File, entries: Map<String, String>) {
        ZipOutputStream(file.outputStream()).use { z ->
            entries.forEach { (name, body) ->
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray())
                z.closeEntry()
            }
        }
    }

    @Test
    fun opensZipAndServesChapters() {
        val f = tmp.newFile("book.epub")
        zip(
            f, linkedMapOf(
                "mimetype" to "application/epub+zip",
                "META-INF/container.xml" to """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles>
                    <rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
                "content.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title></metadata>
                    <manifest>
                      <item id="c1" href="text/One.xhtml" media-type="application/xhtml+xml"/>
                      <item id="c2" href="text/two.xhtml" media-type="application/xhtml+xml"/>
                    </manifest>
                    <spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>""",
                // Entry name case differs from the manifest href ("One" vs "one"), as in some real books.
                "text/one.xhtml" to "<html><head><title>First</title></head><body><p>Hello <ruby>漢<rt>かん</rt></ruby> world</p></body></html>",
                "text/two.xhtml" to "<html><head><title>Second</title></head><body><p>Bye</p></body></html>",
            )
        )
        EpubBook(f).use { book ->
            assertEquals(2, book.chapters.size)
            // No nav/NCX: the TOC falls back to document titles.
            assertEquals(listOf("First", "Second"), book.toc.map { it.title })
            assertTrue(String(book.read("text/One.xhtml")!!).contains("Hello"))
            assertEquals(0, book.chapterIndexOf("text/One.xhtml"))
            assertEquals(1, book.chapterIndexOf("text/two.xhtml"))
            assertTrue(book.chapterText(0).contains("Hello 漢 world"))
            assertEquals("application/xhtml+xml", book.mimeType("text/two.xhtml"))
        }
    }
}

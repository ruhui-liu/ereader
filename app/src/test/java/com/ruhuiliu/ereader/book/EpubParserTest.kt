package com.ruhuiliu.ereader.book

import com.ruhuiliu.ereader.TestXml
import com.ruhuiliu.ereader.book.epub.EntryReader
import com.ruhuiliu.ereader.book.epub.EpubParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EpubParserTest {
    private class MapReader(private val files: Map<String, String>) : EntryReader {
        override val entryNames: Collection<String> get() = files.keys
        override fun read(path: String): ByteArray? = files[path]?.toByteArray()
        override fun size(path: String): Long = files[path]?.length?.toLong() ?: 0
    }

    private val container = """<?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
        </container>"""

    @Before
    fun setUp() = TestXml.install()

    @Test
    fun parsesEpub3WithNavAndVerticalWriting() {
        val opf = """<?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>吾輩は猫である</dc:title>
                <dc:creator>夏目漱石</dc:creator>
                <dc:language>ja</dc:language>
                <meta property="rendition:layout">reflowable</meta>
              </metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="css" href="Styles/book.css" media-type="text/css"/>
                <item id="cover" href="Images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                <item id="c1" href="Text/ch%201.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="Text/ch2.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine page-progression-direction="rtl">
                <itemref idref="c1"/><itemref idref="c2"/>
              </spine>
            </package>"""
        val nav = """<?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE html>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
            <body>
              <nav epub:type="landmarks"><ol><li><a href="Text/ch2.xhtml">Landmark</a></li></ol></nav>
              <nav epub:type="toc"><h1>目次</h1>
                <ol>
                  <li><a href="Text/ch%201.xhtml">第一章&nbsp;猫</a>
                    <ol><li><a href="Text/ch%201.xhtml#s2">一の二</a></li></ol>
                  </li>
                  <li><a href="Text/ch2.xhtml">第二章</a></li>
                </ol>
              </nav>
            </body></html>"""
        val files = mapOf(
            "META-INF/container.xml" to container,
            "OEBPS/content.opf" to opf,
            "OEBPS/nav.xhtml" to nav,
            "OEBPS/Styles/book.css" to "html { -epub-writing-mode: vertical-rl; writing-mode: vertical-rl; }",
            "OEBPS/Images/cover.jpg" to "JPEG",
            "OEBPS/Text/ch 1.xhtml" to "<html><body><p>本文</p></body></html>",
            "OEBPS/Text/ch2.xhtml" to "<html><body><p>本文2</p></body></html>",
        )
        val pkg = EpubParser.parse(MapReader(files))
        assertEquals("吾輩は猫である", pkg.title)
        assertEquals("夏目漱石", pkg.author)
        assertEquals("ja", pkg.language)
        assertEquals(listOf("OEBPS/Text/ch 1.xhtml", "OEBPS/Text/ch2.xhtml"), pkg.spine.map { it.path })
        assertEquals("OEBPS/Images/cover.jpg", pkg.coverPath)
        assertTrue(pkg.rtl)
        assertTrue(pkg.vertical)
        assertFalse(pkg.fixedLayout)
        assertEquals(3, pkg.toc.size)
        assertEquals("第一章 猫", pkg.toc[0].title)
        assertEquals(0, pkg.toc[0].depth)
        assertEquals("一の二", pkg.toc[1].title)
        assertEquals(1, pkg.toc[1].depth)
        assertEquals("s2", pkg.toc[1].fragment)
        assertEquals("OEBPS/Text/ch 1.xhtml", pkg.toc[1].path)
    }

    @Test
    fun parsesEpub2WithNestedNcxAndMetaCover() {
        val opf = """<?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
                <dc:title>Pride and Prejudice</dc:title>
                <dc:creator opf:role="aut">Jane Austen</dc:creator>
                <meta name="cover" content="img1"/>
              </metadata>
              <manifest>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="img1" href="images/front.png" media-type="image/png"/>
                <item id="a" href="a.html" media-type="application/xhtml+xml"/>
                <item id="b" href="b.html" media-type="application/xhtml+xml"/>
              </manifest>
              <spine toc="ncx"><itemref idref="a"/><itemref idref="b"/></spine>
            </package>"""
        val ncx = """<?xml version="1.0"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <navMap>
                <navPoint id="p1" playOrder="1"><navLabel><text>Volume I</text></navLabel><content src="a.html"/>
                  <navPoint id="p2" playOrder="2"><navLabel><text>Chapter 1</text></navLabel><content src="a.html#c1"/></navPoint>
                </navPoint>
                <navPoint id="p3" playOrder="3"><navLabel><text>Chapter 2</text></navLabel><content src="b.html"/></navPoint>
              </navMap>
            </ncx>"""
        val files = mapOf(
            "META-INF/container.xml" to container,
            "OEBPS/content.opf" to opf,
            "OEBPS/toc.ncx" to ncx,
            "OEBPS/images/front.png" to "PNG",
            "OEBPS/a.html" to "<html><body><p>It is a truth</p></body></html>",
            "OEBPS/b.html" to "<html><body><p>Chapter 2</p></body></html>",
        )
        val pkg = EpubParser.parse(MapReader(files))
        assertEquals("Pride and Prejudice", pkg.title)
        assertEquals("Jane Austen", pkg.author)
        assertEquals("OEBPS/images/front.png", pkg.coverPath)
        assertFalse(pkg.vertical)
        assertFalse(pkg.rtl)
        assertEquals(listOf("Volume I", "Chapter 1", "Chapter 2"), pkg.toc.map { it.title })
        assertEquals(listOf(0, 1, 0), pkg.toc.map { it.depth })
        assertEquals("c1", pkg.toc[1].fragment)
    }

    @Test(expected = com.ruhuiliu.ereader.book.epub.EpubFormatException::class)
    fun rejectsZipWithoutContainer() {
        EpubParser.parse(MapReader(mapOf("mimetype" to "application/epub+zip")))
    }
}

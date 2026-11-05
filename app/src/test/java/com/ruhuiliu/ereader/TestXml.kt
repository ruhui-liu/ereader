package com.ruhuiliu.ereader

import com.ruhuiliu.ereader.book.xml.XmlDom
import org.kxml2.io.KXmlParser

/** Android's XmlPullParserFactory is a stub in JVM unit tests; use kXML2 directly. */
object TestXml {
    fun install() {
        XmlDom.parserProvider = { KXmlParser() }
    }
}

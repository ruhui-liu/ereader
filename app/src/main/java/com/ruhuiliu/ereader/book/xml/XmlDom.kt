package com.ruhuiliu.ereader.book.xml

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream

/** A tiny, lenient DOM built on XmlPullParser. Good enough for OPF / NCX / nav / container.xml. */
class XElement(
    val name: String,
    val namespace: String,
    private val attrs: Map<String, String>,
) {
    val children = mutableListOf<Any>() // XElement or String

    /** Attribute by local name, regardless of namespace. */
    fun attr(localName: String): String? = attrs[localName]

    /** Attribute by namespace + local name. */
    fun attr(namespace: String, localName: String): String? = attrs["{$namespace}$localName"]

    fun elements(): List<XElement> = children.filterIsInstance<XElement>()

    fun child(localName: String): XElement? = elements().firstOrNull { it.name == localName }

    fun children(localName: String): List<XElement> = elements().filter { it.name == localName }

    fun descendants(): Sequence<XElement> = sequence {
        for (c in elements()) {
            yield(c)
            yieldAll(c.descendants())
        }
    }

    fun descendants(localName: String): Sequence<XElement> = descendants().filter { it.name == localName }

    fun text(): String = buildString { appendText(this@XElement, this) }

    private fun appendText(e: XElement, sb: StringBuilder) {
        for (c in e.children) {
            when (c) {
                is String -> sb.append(c)
                is XElement -> appendText(c, sb)
            }
        }
    }
}

object XmlDom {
    /** Overridable for JVM unit tests (where Android's parser factory is a stub). */
    @Volatile
    var parserProvider: () -> XmlPullParser = {
        XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
    }

    private val HTML_ENTITIES = mapOf(
        "nbsp" to "\u00A0", "copy" to "©", "reg" to "®", "trade" to "™", "mdash" to "—", "ndash" to "–",
        "hellip" to "…", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "laquo" to "«",
        "raquo" to "»", "middot" to "·", "bull" to "•", "emsp" to "\u2003", "ensp" to "\u2002",
        "thinsp" to "\u2009", "zwnj" to "\u200C", "zwj" to "\u200D", "shy" to "\u00AD", "times" to "×",
        "deg" to "°", "sect" to "§", "para" to "¶", "dagger" to "†", "Dagger" to "‡", "prime" to "′",
    )

    fun parse(bytes: ByteArray): XElement {
        var start = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            start = 3
        }
        val parser = parserProvider()
        runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true) }
        runCatching { parser.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        parser.setInput(ByteArrayInputStream(bytes, start, bytes.size - start), null)
        for ((k, v) in HTML_ENTITIES) runCatching { parser.defineEntityReplacementText(k, v) }

        val stack = ArrayDeque<XElement>()
        var root: XElement? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val attrs = HashMap<String, String>()
                    for (i in 0 until parser.attributeCount) {
                        var local = parser.getAttributeName(i) ?: continue
                        val ns = parser.getAttributeNamespace(i).orEmpty()
                        // Without namespace processing a name may still carry a prefix ("epub:type").
                        val colon = local.indexOf(':')
                        if (ns.isEmpty() && colon > 0) local = local.substring(colon + 1)
                        val value = parser.getAttributeValue(i) ?: ""
                        attrs[local] = value
                        if (ns.isNotEmpty()) attrs["{$ns}$local"] = value
                    }
                    var name = parser.name ?: ""
                    val colon = name.indexOf(':')
                    if (colon > 0) name = name.substring(colon + 1)
                    val el = XElement(name, parser.namespace.orEmpty(), attrs)
                    stack.lastOrNull()?.children?.add(el)
                    if (root == null) root = el
                    stack.addLast(el)
                }
                XmlPullParser.END_TAG -> stack.removeLastOrNull()
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> {
                    val t = parser.text
                    if (t != null) stack.lastOrNull()?.children?.add(t)
                }
            }
            event = try {
                parser.nextToken()
            } catch (e: Exception) {
                // Lenient: return what we have so far on malformed input.
                if (root != null) break else throw e
            }
        }
        return root ?: throw IllegalArgumentException("Empty XML document")
    }
}

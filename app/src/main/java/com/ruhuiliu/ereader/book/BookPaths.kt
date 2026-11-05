package com.ruhuiliu.ereader.book

import java.io.ByteArrayOutputStream

/** Path helpers for resources inside a book container (zip entry names, relative hrefs). */
object BookPaths {
    /** Splits "a/b.xhtml#frag" into ("a/b.xhtml", "frag"). */
    fun splitFragment(href: String): Pair<String, String?> {
        val i = href.indexOf('#')
        return if (i < 0) href to null else href.substring(0, i) to href.substring(i + 1).ifEmpty { null }
    }

    /** Percent-decodes a URL path (does NOT treat '+' as space, unlike URLDecoder). */
    fun percentDecode(s: String): String {
        if (!s.contains('%')) return s
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length && isHex(s[i + 1]) && isHex(s[i + 2])) {
                out.write(s.substring(i + 1, i + 3).toInt(16))
                i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return out.toString("UTF-8")
    }

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    /** Directory part of a path ("OEBPS/Text/ch1.xhtml" -> "OEBPS/Text/"). */
    fun dirOf(path: String): String {
        val i = path.lastIndexOf('/')
        return if (i < 0) "" else path.substring(0, i + 1)
    }

    /**
     * Resolves [href] (relative, possibly percent-encoded, possibly with a fragment) against the
     * file [basePath]. Returns a normalized container path without fragment.
     */
    fun resolve(basePath: String, href: String): String {
        val (pathPart, _) = splitFragment(href.trim())
        val decoded = percentDecode(pathPart)
        if (decoded.isEmpty()) return normalize(basePath)
        val joined = if (decoded.startsWith("/")) decoded.substring(1) else dirOf(basePath) + decoded
        return normalize(joined)
    }

    fun normalize(path: String): String {
        val out = ArrayDeque<String>()
        for (seg in path.replace('\\', '/').split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> out.removeLastOrNull()
                else -> out.addLast(seg)
            }
        }
        return out.joinToString("/")
    }

    /** Percent-encodes each path segment so it can be appended to a URL. */
    fun encodePath(path: String): String = path.split('/').joinToString("/") { seg ->
        buildString {
            for (b in seg.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xFF
                val ch = c.toChar()
                if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch in "-._~!$&'()*+,;=:@") append(ch)
                else append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
            }
        }
    }

    fun mimeTypeForPath(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "xhtml", "xht" -> "application/xhtml+xml"
            "html", "htm" -> "text/html"
            "xml", "opf", "ncx" -> "application/xml"
            "css" -> "text/css"
            "js" -> "text/javascript"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "bmp" -> "image/bmp"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "mp3" -> "audio/mpeg"
            "mp4", "m4v" -> "video/mp4"
            "m4a" -> "audio/mp4"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}

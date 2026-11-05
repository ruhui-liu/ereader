package com.ruhuiliu.ereader.book.txt

import com.ruhuiliu.ereader.book.BookContent
import com.ruhuiliu.ereader.book.Chapter
import com.ruhuiliu.ereader.book.HtmlText
import com.ruhuiliu.ereader.book.TocItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * A plain-text book stored as normalized UTF-8 ([textFile]) plus a chapter index ([indexFile])
 * holding byte ranges. Chapters are rendered to simple HTML on demand.
 */
class TxtBook(private val textFile: File, indexFile: File) : BookContent {

    private class Entry(val title: String, val start: Long, val length: Int)

    private val entries: List<Entry> = JSONArray(indexFile.readText()).let { arr ->
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(o.getString("t"), o.getLong("s"), o.getInt("l"))
        }
    }

    override val chapters: List<Chapter> = entries.mapIndexed { i, e ->
        Chapter(i, "$CHAPTER_DIR/$i.html", "text/html", e.length.toLong(), e.title)
    }

    override val toc: List<TocItem> = entries.mapIndexed { i, e -> TocItem(e.title.ifEmpty { "第 ${i + 1} 节" }, i, null, 0) }

    private fun rawText(index: Int): String {
        val e = entries[index]
        val buf = ByteArray(e.length)
        RandomAccessFile(textFile, "r").use { raf ->
            raf.seek(e.start)
            raf.readFully(buf)
        }
        return String(buf, Charsets.UTF_8)
    }

    override fun read(path: String): ByteArray? {
        if (!path.startsWith("$CHAPTER_DIR/")) return null
        val index = path.removePrefix("$CHAPTER_DIR/").removeSuffix(".html").toIntOrNull() ?: return null
        if (index !in entries.indices) return null
        return renderHtml(entries[index].title, rawText(index)).toByteArray(Charsets.UTF_8)
    }

    override fun mimeType(path: String) = "text/html"

    override fun chapterText(index: Int): String = rawText(index)

    companion object {
        const val CHAPTER_DIR = "__txt"
        private val LEADING_SPACE = Regex("^[\\s\\u3000\\u00A0]+")

        fun renderHtml(title: String, body: String): String = buildString(body.length + body.length / 4 + 256) {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>")
            append(HtmlText.escape(title))
            append("</title></head><body>")
            var titleDone = title.isEmpty()
            for (rawLine in body.split('\n')) {
                val line = LEADING_SPACE.replace(rawLine, "").trimEnd()
                if (line.isEmpty()) continue
                if (!titleDone && line == title.trim()) {
                    append("<h2 class=\"yd-txt-title\">").append(HtmlText.escape(line)).append("</h2>")
                    titleDone = true
                    continue
                }
                titleDone = true
                append("<p>").append(HtmlText.escape(line)).append("</p>")
            }
            append("</body></html>")
        }

        /** Writes the normalized UTF-8 text and the chapter index for a decoded text. */
        fun write(text: String, textFile: File, indexFile: File) {
            val parts = TxtChapterSplitter.split(text)
            val arr = JSONArray()
            textFile.outputStream().buffered().use { out ->
                var offset = 0L
                for (p in parts) {
                    val bytes = text.substring(p.start, p.end).toByteArray(Charsets.UTF_8)
                    out.write(bytes)
                    arr.put(JSONObject().put("t", p.title).put("s", offset).put("l", bytes.size))
                    offset += bytes.size
                }
            }
            indexFile.writeText(arr.toString())
        }
    }
}

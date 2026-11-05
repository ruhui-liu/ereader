package com.ruhuiliu.ereader.book.txt

/** Splits a plain-text novel into chapters using common Chinese / Japanese / English heading patterns. */
object TxtChapterSplitter {
    data class Part(val title: String, val start: Int, val end: Int)

    private const val NUM = "[0-9０-９零〇一二三四五六七八九十百千万萬两兩壹贰貳叁參肆伍陆陸柒捌玖拾佰仟]+"
    private val PATTERNS = listOf(
        // 第一章 / 第12回 / 第三卷 / 第5話
        Regex("^第\\s*$NUM\\s*[章节節回卷集部篇话話幕册冊][\\s\\p{P}\\p{S}\u3000]*.{0,40}$"),
        // 序章 / 楔子 / 终章 / 后记 / 番外 / プロローグ ...
        Regex("^(序章|序言|序|楔子|引子|前言|尾声|尾聲|终章|終章|后记|後記|番外.{0,30}|あとがき|まえがき|プロローグ|エピローグ|间章|間章|幕间|幕間)([\\s\u3000:：·・\\-—].{0,40})?$"),
        // Chapter 1 / Chapter Twelve: Title / Part 2 / Book III / Prologue / Epilogue
        Regex("^(?i:chapter|part|book)\\s+([0-9]+|(?i:one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty))\\b([\\s.:：\\-—]+.{0,60})?$"),
        Regex("^(?i:chapter|part|book)\\s+[IVXLCDM]+\\b([\\s.:：\\-—]+.{0,60})?$"),
        Regex("^(?i:prologue|epilogue|interlude|afterword|preface)([\\s.:：\\-—]+.{0,60})?$"),
    )

    private const val MAX_TITLE = 50
    private const val TARGET_CHUNK = 12_000
    private const val MAX_CHAPTER = 60_000

    fun isHeading(line: String): Boolean {
        val t = line.trim { it.isWhitespace() || it == '\u3000' }
        if (t.isEmpty() || t.length > MAX_TITLE) return false
        return PATTERNS.any { it.matches(t) }
    }

    fun split(text: String): List<Part> {
        // Collect heading lines with their offsets.
        val heads = mutableListOf<Pair<Int, String>>()
        var pos = 0
        while (pos <= text.length) {
            val nl = text.indexOf('\n', pos).let { if (it < 0) text.length else it }
            val line = text.substring(pos, nl)
            if (isHeading(line)) heads += pos to line.trim { it.isWhitespace() || it == '\u3000' }
            pos = nl + 1
        }

        // Drop headings that are immediately followed by another heading (tables of contents).
        val real = heads.filterIndexed { i, (start, title) ->
            val bodyEnd = if (i + 1 < heads.size) heads[i + 1].first else text.length
            val body = text.substring(minOf(text.length, start + title.length + 1).coerceAtMost(bodyEnd), bodyEnd)
            body.count { !it.isWhitespace() && it != '\u3000' } >= 20
        }

        val parts = mutableListOf<Part>()
        if (real.size >= 2) {
            val first = real.first().first
            if (text.substring(0, first).any { !it.isWhitespace() && it != '\u3000' }) {
                parts += Part("开篇", 0, first)
            }
            real.forEachIndexed { i, (start, title) ->
                val end = if (i + 1 < real.size) real[i + 1].first else text.length
                parts += Part(title, start, end)
            }
        } else {
            parts += Part("", 0, text.length)
        }

        // Break up very long (or undivided) parts at line boundaries.
        val out = mutableListOf<Part>()
        for (p in parts) {
            val len = p.end - p.start
            if (len <= MAX_CHAPTER && p.title.isNotEmpty()) {
                out += p
                continue
            }
            var s = p.start
            var n = 1
            while (s < p.end) {
                var e = minOf(p.end, s + TARGET_CHUNK)
                if (e < p.end) {
                    val nl = text.indexOf('\n', e)
                    e = if (nl < 0 || nl >= p.end) p.end else nl + 1
                }
                val title = when {
                    p.title.isNotEmpty() && n == 1 -> p.title
                    p.title.isNotEmpty() -> "${p.title}（$n）"
                    else -> "第 ${out.size + 1} 节"
                }
                out += Part(title, s, e)
                s = e
                n++
            }
        }
        return out.ifEmpty { listOf(Part("正文", 0, text.length)) }
    }
}

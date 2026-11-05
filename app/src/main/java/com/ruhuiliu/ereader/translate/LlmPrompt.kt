package com.ruhuiliu.ereader.translate

import com.ruhuiliu.ereader.data.settings.Languages
import org.json.JSONArray

/** Prompting + response parsing shared by the LLM-based engines. */
object LlmPrompt {
    const val MAX_ITEMS = 40
    const val MAX_CHARS = 3_000

    fun system(source: String?, target: String, extra: String): String = buildString {
        append("You are a professional literary translator. Translate the paragraphs of a book ")
        append(if (source != null) "from ${Languages.englishName(source)} " else "")
        append("into ${Languages.englishName(target)}.\n\n")
        append("The user message is a JSON array of strings; each string is one paragraph, in reading order.\n")
        append("Reply with ONLY a JSON array of strings with exactly the same number of elements, ")
        append("where element i is the translation of input element i. No commentary, no code fences.\n\n")
        append("Guidelines:\n")
        append("- Translate faithfully and naturally, keeping the tone, register and style of the original.\n")
        append("- Keep character names and terms consistent across paragraphs.\n")
        append("- Never merge, split, skip or explain paragraphs. Keep line breaks inside a paragraph.\n")
        append("- If a paragraph is already in the target language or is only a symbol or number, return it unchanged.\n")
        if (extra.isNotBlank()) {
            append("\nAdditional instructions from the reader:\n")
            append(extra.trim())
            append('\n')
        }
    }

    fun user(texts: List<String>): String = JSONArray(texts).toString()

    /** Extracts a JSON string array from a model reply; null if it can't be parsed. */
    fun parseArray(reply: String): List<String>? {
        var s = reply.trim()
        if (s.startsWith("```")) s = s.substringAfter('\n').substringBeforeLast("```")
        val start = s.indexOf('[')
        val end = s.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        return runCatching {
            val arr = JSONArray(s.substring(start, end + 1))
            List(arr.length()) { i -> arr.opt(i)?.toString().orEmpty() }
        }.getOrNull()
    }

    /**
     * Calls [call] with the batch; if the reply doesn't contain exactly one translation per input,
     * splits the batch in halves and retries, down to single paragraphs.
     */
    suspend fun translateWithSplitting(texts: List<String>, call: suspend (List<String>) -> String): List<String> {
        val reply = call(texts)
        val parsed = parseArray(reply)
        if (parsed != null && parsed.size == texts.size) return parsed
        if (texts.size == 1) {
            return listOf(parsed?.joinToString("\n")?.ifBlank { null } ?: reply.trim())
        }
        val mid = texts.size / 2
        return translateWithSplitting(texts.subList(0, mid), call) + translateWithSplitting(texts.subList(mid, texts.size), call)
    }
}

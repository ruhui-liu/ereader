package com.ruhuiliu.ereader.translate

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmPromptTest {
    @Test
    fun parsesArraysWithFencesAndChatter() {
        assertEquals(listOf("你好", "世界"), LlmPrompt.parseArray("```json\n[\"你好\", \"世界\"]\n```"))
        assertEquals(listOf("一"), LlmPrompt.parseArray("Here you go: [\"一\"] Hope it helps"))
        assertNull(LlmPrompt.parseArray("no json here"))
    }

    @Test
    fun systemPromptNamesLanguages() {
        val p = LlmPrompt.system("ja", "zh-CN", "人名保留原文")
        assertTrue(p.contains("Japanese"))
        assertTrue(p.contains("Simplified Chinese"))
        assertTrue(p.contains("人名保留原文"))
    }

    @Test
    fun splitsBatchWhenModelReturnsWrongCount() = runBlocking {
        val calls = mutableListOf<Int>()
        val result = LlmPrompt.translateWithSplitting(listOf("a", "b", "c", "d")) { batch ->
            calls += batch.size
            // Misbehave on the full batch: merge everything into one element.
            if (batch.size == 4) "[\"abcd\"]" else JSONArray(batch.map { it.uppercase() }).toString()
        }
        assertEquals(listOf("A", "B", "C", "D"), result)
        assertEquals(listOf(4, 2, 2), calls)
    }
}

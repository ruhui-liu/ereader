package com.ruhuiliu.ereader.translate

/** A translation backend. Implementations translate a batch of paragraphs, preserving order. */
interface TranslationEngine {
    /** Stable signature used as part of the translation cache key (engine + model). */
    val signature: String
    val maxBatchItems: Int
    val maxBatchChars: Int

    /**
     * @param source source language code or null for auto-detect
     * @param target target language code (zh-CN, zh-TW, en, ja, ko...)
     */
    suspend fun translate(texts: List<String>, source: String?, target: String): List<String>

    fun close() {}
}

class TranslationException(message: String, cause: Throwable? = null) : Exception(message, cause)

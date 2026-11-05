package com.ruhuiliu.ereader.translate

import com.ruhuiliu.ereader.book.LanguageGuess
import com.ruhuiliu.ereader.data.db.TranslationCacheDao
import com.ruhuiliu.ereader.data.db.TranslationCacheEntity
import com.ruhuiliu.ereader.data.settings.Engines
import com.ruhuiliu.ereader.data.settings.SettingsRepository
import com.ruhuiliu.ereader.data.settings.TranslationSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

/** Creates the configured engine and adds a persistent translation cache in front of it. */
class TranslationService(
    private val settings: SettingsRepository,
    private val cache: TranslationCacheDao,
) {
    private var current: Pair<TranslationSettings, TranslationEngine>? = null
    private val lock = Mutex()

    suspend fun engine(): TranslationEngine = lock.withLock {
        val s = settings.translationSettings.first()
        current?.let { (cfg, engine) -> if (cfg == s) return engine else engine.close() }
        val engine = create(s)
        current = s to engine
        engine
    }

    suspend fun targetLang(): String = settings.translationSettings.first().targetLang

    private fun create(s: TranslationSettings): TranslationEngine = when (s.engine) {
        Engines.GOOGLE -> GoogleCloudEngine(s.googleKey)
        Engines.DEEPL -> DeepLEngine(s.deeplKey)
        Engines.AZURE -> AzureEngine(s.azureKey, s.azureRegion)
        Engines.OPENAI -> OpenAiCompatibleEngine(s.openaiBaseUrl, s.openaiKey, s.openaiModel, s.llmExtraPrompt)
        Engines.CLAUDE -> ClaudeEngine(s.claudeKey, s.claudeModel, s.llmExtraPrompt)
        else -> MlKitEngine()
    }

    private fun key(engine: TranslationEngine, source: String?, target: String, text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("${engine.signature}|${source ?: "auto"}|$target|".toByteArray())
        md.update(text.toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** True if [text] needs no translation (symbols only, or already in the target language). */
    fun shouldSkip(text: String, target: String): Boolean =
        text.isBlank() || LanguageGuess.isUntranslatable(text) || LanguageGuess.alreadyInTarget(text, target)

    /** Returns cached translations for the given texts (index -> translation). */
    suspend fun cached(texts: List<String>, source: String?): Map<Int, String> {
        val engine = engine()
        val target = targetLang()
        val keys = texts.map { key(engine, source, target, it) }
        val found = keys.chunked(500).flatMap { cache.getAll(it) }.associate { it.key to it.translated }
        return keys.withIndex().mapNotNull { (i, k) -> found[k]?.let { i to it } }.toMap()
    }

    /** Translates a batch (no batching logic here; callers respect the engine limits) and caches it. */
    suspend fun translate(texts: List<String>, source: String?): List<String> {
        val engine = engine()
        val target = targetLang()
        val result = engine.translate(texts, source, target)
        if (result.size != texts.size) throw TranslationException("翻译结果数量不匹配")
        cache.putAll(texts.indices.map { TranslationCacheEntity(key(engine, source, target, texts[it]), result[it]) })
        return result
    }

    /** Translates a single piece of text (e.g. the current selection), using the cache. */
    suspend fun translateOne(text: String, source: String?): String {
        cached(listOf(text), source)[0]?.let { return it }
        return translate(listOf(text), source).first()
    }

    suspend fun forget(texts: List<String>, source: String?) {
        val engine = engine()
        val target = targetLang()
        cache.deleteAll(texts.map { key(engine, source, target, it) })
    }

    suspend fun clearCache() = cache.clear()
    suspend fun cacheCount() = cache.count()
}

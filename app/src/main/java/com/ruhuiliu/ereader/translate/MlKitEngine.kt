package com.ruhuiliu.ereader.translate

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.ruhuiliu.ereader.book.LanguageGuess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * Google ML Kit on-device translation. Free and offline after a one-time ~30 MB model download
 * per language. Chinese output is Simplified Chinese only.
 */
class MlKitEngine : TranslationEngine {
    override val signature = "mlkit"
    override val maxBatchItems = 8
    override val maxBatchChars = 4_000

    private val translators = HashMap<String, Translator>()
    private val mutex = Mutex()
    private val languageId by lazy { LanguageIdentification.getClient() }

    private fun mlkitCode(code: String): String? = when {
        code.startsWith("zh") -> TranslateLanguage.CHINESE
        else -> TranslateLanguage.fromLanguageTag(code.substringBefore('-'))
    }

    private suspend fun detect(text: String): String? {
        // Script heuristics are reliable for CJK; ML Kit language-id separates Latin-script languages.
        val guess = LanguageGuess.guess(text)
        if (guess != null && guess != "en") return guess
        val id = runCatching { languageId.identifyLanguage(text.take(1000)).await() }.getOrNull()
        return id?.takeIf { it != "und" } ?: guess
    }

    private suspend fun translator(src: String, tgt: String): Translator = mutex.withLock {
        translators.getOrPut("$src>$tgt") {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build())
        }.also { t ->
            try {
                t.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            } catch (e: Exception) {
                throw TranslationException("ML Kit 翻译模型下载失败（需要能访问 Google 服务）：${e.message}", e)
            }
        }
    }

    override suspend fun translate(texts: List<String>, source: String?, target: String): List<String> {
        val tgt = mlkitCode(target) ?: throw TranslationException("ML Kit 不支持目标语言 $target")
        val batchLang = source ?: detect(texts.joinToString("\n"))
        return texts.map { text ->
            val lang = if (source == null && texts.size > 1) detect(text) ?: batchLang else batchLang
            val src = lang?.let { mlkitCode(it) } ?: throw TranslationException("无法识别源语言，请在翻译设置中手动指定")
            if (src == tgt) text else translator(src, tgt).translate(text).await()
        }
    }

    override fun close() {
        translators.values.forEach { runCatching { it.close() } }
        translators.clear()
    }

    companion object {
        /** Languages whose models are already downloaded. */
        suspend fun downloadedModels(): Set<String> =
            RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java).await()
                .map { it.language }.toSet()

        suspend fun deleteModel(language: String) {
            RemoteModelManager.getInstance().deleteDownloadedModel(TranslateRemoteModel.Builder(language).build()).await()
        }

        suspend fun downloadModel(language: String) {
            RemoteModelManager.getInstance()
                .download(TranslateRemoteModel.Builder(language).build(), DownloadConditions.Builder().build()).await()
        }
    }
}

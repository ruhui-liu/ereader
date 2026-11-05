package com.ruhuiliu.ereader.data.settings

/** Appearance and behaviour of the reader. */
data class ReaderSettings(
    val fontSize: Int = 19,
    val lineHeight: Float = 1.75f,
    val paragraphSpacing: Float = 0.6f,
    val marginH: Int = 22,
    val marginV: Int = 30,
    /** "book" (publisher font), "serif", "sans", "mono", or "custom:<file name>". */
    val fontFamily: String = "serif",
    /** light / sepia / green / dark / black */
    val theme: String = "sepia",
    /** "paged" or "scroll". */
    val pageMode: String = "paged",
    /** "justify" or "start". */
    val textAlign: String = "justify",
    val indent: Boolean = true,
    /** Override the publisher's font sizes, line spacing and colours. */
    val overrideStyles: Boolean = true,
    /** Show ruby annotations (furigana). */
    val showRuby: Boolean = true,
    val volumeKeys: Boolean = true,
    val keepScreenOn: Boolean = true,
    val pageAnimation: Boolean = true,
    /** Font scale of the translation relative to the original text. */
    val translationScale: Float = 0.92f,
)

object Engines {
    const val MLKIT = "mlkit"
    const val GOOGLE = "google"
    const val DEEPL = "deepl"
    const val AZURE = "azure"
    const val OPENAI = "openai"
    const val CLAUDE = "claude"

    val all = listOf(MLKIT, GOOGLE, DEEPL, AZURE, OPENAI, CLAUDE)

    fun label(id: String) = when (id) {
        MLKIT -> "Google ML Kit（离线，免费）"
        GOOGLE -> "Google Cloud Translation"
        DEEPL -> "DeepL"
        AZURE -> "Microsoft Azure 翻译"
        OPENAI -> "OpenAI 兼容接口（DeepSeek / 通义 / Kimi / Ollama…）"
        CLAUDE -> "Anthropic Claude"
        else -> id
    }

    fun shortLabel(id: String) = when (id) {
        MLKIT -> "ML Kit"
        GOOGLE -> "Google"
        DEEPL -> "DeepL"
        AZURE -> "Azure"
        OPENAI -> "OpenAI 兼容"
        CLAUDE -> "Claude"
        else -> id
    }
}

data class TranslationSettings(
    val engine: String = Engines.MLKIT,
    /** zh-CN, zh-TW, en, ja, ko, ... */
    val targetLang: String = "zh-CN",
    /** Translation mode for newly opened books: 0 off, 1 bilingual, 2 translation only. */
    val defaultMode: Int = 0,
    val googleKey: String = "",
    val deeplKey: String = "",
    val azureKey: String = "",
    val azureRegion: String = "",
    val openaiBaseUrl: String = "https://api.deepseek.com/v1",
    val openaiKey: String = "",
    val openaiModel: String = "deepseek-chat",
    val claudeKey: String = "",
    val claudeModel: String = "claude-opus-5-5",
    /** Extra instructions appended to the LLM translation prompt. */
    val llmExtraPrompt: String = "",
)

/** Languages offered in the UI. Codes are BCP-47-ish and mapped per engine. */
object Languages {
    val targets = listOf(
        "zh-CN" to "简体中文",
        "zh-TW" to "繁體中文",
        "en" to "English",
        "ja" to "日本語",
        "ko" to "한국어",
    )
    val sources = listOf(
        "auto" to "自动检测",
        "en" to "English 英语",
        "ja" to "日本語 日语",
        "ko" to "한국어 韩语",
        "fr" to "Français 法语",
        "de" to "Deutsch 德语",
        "es" to "Español 西班牙语",
        "ru" to "Русский 俄语",
        "zh-CN" to "中文",
    )

    fun name(code: String?): String =
        (targets + sources).firstOrNull { it.first == code }?.second ?: (code ?: "自动检测")

    /** English name used in LLM prompts. */
    fun englishName(code: String): String = when (code) {
        "zh-CN", "zh", "zh-Hans" -> "Simplified Chinese (简体中文)"
        "zh-TW", "zh-Hant" -> "Traditional Chinese (繁體中文)"
        "en" -> "English"
        "ja" -> "Japanese"
        "ko" -> "Korean"
        "fr" -> "French"
        "de" -> "German"
        "es" -> "Spanish"
        "ru" -> "Russian"
        else -> code
    }
}

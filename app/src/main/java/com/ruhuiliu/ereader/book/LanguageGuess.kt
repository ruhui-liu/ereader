package com.ruhuiliu.ereader.book

/** Cheap script-based language guess, good enough to tell ja / zh / ko / Latin-script text apart. */
object LanguageGuess {
    fun guess(text: String): String? {
        var kana = 0
        var han = 0
        var hangul = 0
        var latin = 0
        var cyrillic = 0
        for (ch in text) {
            when (ch.code) {
                in 0x3040..0x30FF, in 0x31F0..0x31FF, in 0xFF66..0xFF9D -> kana++
                in 0x4E00..0x9FFF, in 0x3400..0x4DBF, in 0xF900..0xFAFF -> han++
                in 0xAC00..0xD7AF, in 0x1100..0x11FF, in 0x3130..0x318F -> hangul++
                in 0x0041..0x005A, in 0x0061..0x007A, in 0x00C0..0x024F -> latin++
                in 0x0400..0x04FF -> cyrillic++
            }
        }
        val total = kana + han + hangul + latin + cyrillic
        if (total == 0) return null
        return when {
            kana > 0 && kana * 20 >= han -> "ja"
            hangul * 2 >= total -> "ko"
            han * 2 >= total -> "zh"
            cyrillic * 2 >= total -> "ru"
            latin * 2 >= total -> "en" // Latin script; refined by ML Kit language-id when needed.
            else -> null
        }
    }

    /** True if [text] has nothing worth translating (numbers, punctuation, symbols only). */
    fun isUntranslatable(text: String): Boolean = text.none { Character.isLetter(it) }

    /**
     * True if the text already appears to be in the target language, so translating it is pointless
     * (e.g. a "第一章" heading in a Japanese book when translating to Chinese).
     */
    fun alreadyInTarget(text: String, target: String): Boolean {
        val g = guess(text) ?: return false
        return when {
            target.startsWith("zh") -> g == "zh"
            else -> g == target.substringBefore('-')
        }
    }
}

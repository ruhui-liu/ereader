package com.ruhuiliu.ereader.reader

import androidx.compose.ui.graphics.Color

data class ReaderTheme(
    val id: String,
    val name: String,
    val bg: Color,
    val fg: Color,
    val translation: Color,
    val link: Color,
    val isDark: Boolean,
) {
    companion object {
        val all = listOf(
            ReaderTheme("light", "白色", Color(0xFFFFFFFF), Color(0xFF1F1F1F), Color(0xFF2F6DB3), Color(0xFF2F6DB3), false),
            ReaderTheme("sepia", "纸张", Color(0xFFF5ECD7), Color(0xFF4A3B2A), Color(0xFF9A4E22), Color(0xFF8A5A2B), false),
            ReaderTheme("green", "护眼", Color(0xFFCCE8CF), Color(0xFF1F3324), Color(0xFF2B6A8C), Color(0xFF2E6B4F), false),
            ReaderTheme("dark", "夜间", Color(0xFF1E1F22), Color(0xFFC9C9C9), Color(0xFF8AB4F8), Color(0xFF8AB4F8), true),
            ReaderTheme("black", "纯黑", Color(0xFF000000), Color(0xFFA8A8A8), Color(0xFF7FA7D9), Color(0xFF7FA7D9), true),
        )

        fun byId(id: String) = all.firstOrNull { it.id == id } ?: all[1]
    }
}

fun Color.toCss(): String {
    val argb = (alpha * 255).toInt() shl 24 or ((red * 255).toInt() shl 16) or ((green * 255).toInt() shl 8) or (blue * 255).toInt()
    return "#%06X".format(argb and 0xFFFFFF)
}

package com.ruhuiliu.ereader.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(
    primary = Color(0xFF3B5B7E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4FF),
    secondary = Color(0xFF8A5A2B),
    secondaryContainer = Color(0xFFF3E1CC),
    tertiary = Color(0xFFC8553D),
    background = Color(0xFFFBF8F3),
    surface = Color(0xFFFBF8F3),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA3C9FE),
    secondary = Color(0xFFE4BF94),
    tertiary = Color(0xFFFFB4A1),
)

@Composable
fun EReaderTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

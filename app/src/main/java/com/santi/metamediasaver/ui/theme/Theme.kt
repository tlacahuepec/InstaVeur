package com.santi.metamediasaver.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightScheme =
    lightColorScheme(
        primary = Color(0xFF0F766E),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFCCFBF1),
        onPrimaryContainer = Color(0xFF042F2E),
        secondary = Color(0xFF4F46E5),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFE0E7FF),
        onSecondaryContainer = Color(0xFF312E81),
        tertiary = Color(0xFFF97316),
        onTertiary = Color.White,
        background = Color(0xFFF8FAFC),
        onBackground = Color(0xFF0F172A),
        surface = Color.White,
        onSurface = Color(0xFF0F172A),
        surfaceVariant = Color(0xFFE2E8F0),
        onSurfaceVariant = Color(0xFF475569),
        error = Color(0xFFB42318),
    )

private val DarkScheme =
    darkColorScheme(
        primary = Color(0xFF5EEAD4),
        onPrimary = Color(0xFF042F2E),
        primaryContainer = Color(0xFF115E59),
        onPrimaryContainer = Color(0xFFCCFBF1),
        secondary = Color(0xFFC7D2FE),
        onSecondary = Color(0xFF312E81),
        tertiary = Color(0xFFFDBA74),
        onTertiary = Color(0xFF431407),
        background = Color(0xFF111827),
        onBackground = Color(0xFFF8FAFC),
        surface = Color(0xFF1F2937),
        onSurface = Color(0xFFF8FAFC),
        surfaceVariant = Color(0xFF334155),
        onSurfaceVariant = Color(0xFFCBD5E1),
        error = Color(0xFFFCA5A5),
    )

@Composable
fun MetaMediaSaverTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme: ColorScheme = if (darkTheme) DarkScheme else LightScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content,
    )
}

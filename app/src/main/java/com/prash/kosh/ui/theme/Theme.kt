package com.prash.kosh.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

val Income = Color(0xFF1B7F4B)
val Expense = Color(0xFFC0392B)
val Neutral = Color(0xFF5B6B7F)
val Invest = Color(0xFF2E5AAC)

private val Light = lightColorScheme(
    primary = Color(0xFF0F5132), onPrimary = Color.White,
    primaryContainer = Color(0xFFD3EBDD), onPrimaryContainer = Color(0xFF062A19),
    secondary = Color(0xFF2E5AAC), secondaryContainer = Color(0xFFDCE6F8),
    background = Color(0xFFF7F8FA), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFEEF1F4),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8FD3AE), onPrimary = Color(0xFF00391E),
    primaryContainer = Color(0xFF14532F), onPrimaryContainer = Color(0xFFD3EBDD),
    secondary = Color(0xFFA9C2F0), secondaryContainer = Color(0xFF223F73),
    background = Color(0xFF101413), surface = Color(0xFF171C1A), surfaceVariant = Color(0xFF232A27),
)

/** [mode] is SYSTEM (follow the phone), LIGHT or DARK. */
@Composable
fun KoshTheme(mode: String = "SYSTEM", content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isDark(mode)) Dark else Light, content = content)
}

@Composable
fun isDark(mode: String): Boolean = when (mode) {
    "DARK" -> true
    "LIGHT" -> false
    else -> isSystemInDarkTheme()
}

/** Lightens a fixed accent on dark backgrounds so amounts stay easy to read. */
@Composable
fun Color.forTheme(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) lerp(this, Color.White, 0.38f) else this

/** Fixed palette for category charts; order matches the share ranking. */
val ChartPalette = listOf(
    Color(0xFF2E7D5B), Color(0xFF2E5AAC), Color(0xFFD08A1E), Color(0xFFB54A6B), Color(0xFF6B5BB5),
    Color(0xFF1F8A9E), Color(0xFF8A6D3B), Color(0xFF9AA5B1),
)

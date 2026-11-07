package com.ai.assistance.operit.rpg.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/** Narrative body text stays serif; UI text remains the system sans-serif. */
val NarrationFontFamily = FontFamily.Serif

private val LightColors = lightColorScheme(
    primary = D20Accent,
    onPrimary = Color.White,
    secondary = D20InkMuted,
    onSecondary = Color.White,
    background = D20Paper,
    onBackground = D20Ink,
    surface = D20Surface,
    onSurface = D20Ink,
    surfaceVariant = Color(0xFFEFEAE1),
    onSurfaceVariant = D20InkMuted,
    outline = D20Divider,
    outlineVariant = D20Divider,
)

private val DarkColors = darkColorScheme(
    primary = D20DarkAccent,
    onPrimary = Color(0xFF2A1610),
    secondary = D20DarkInkMuted,
    onSecondary = D20DarkPaper,
    background = D20DarkPaper,
    onBackground = D20DarkInk,
    surface = D20DarkSurface,
    onSurface = D20DarkInk,
    surfaceVariant = Color(0xFF302C28),
    onSurfaceVariant = D20DarkInkMuted,
    outline = D20DarkDivider,
    outlineVariant = D20DarkDivider,
)

private val D20Typography = Typography().let { base ->
    Typography(
        bodyLarge = base.bodyLarge.copy(fontFamily = FontFamily.SansSerif),
        bodyMedium = base.bodyMedium.copy(fontFamily = FontFamily.SansSerif),
        bodySmall = base.bodySmall.copy(fontFamily = FontFamily.SansSerif),
        titleLarge = base.titleLarge.copy(fontFamily = FontFamily.SansSerif),
        titleMedium = base.titleMedium.copy(fontFamily = FontFamily.SansSerif),
        labelMedium = base.labelMedium.copy(fontFamily = FontFamily.SansSerif),
    )
}

@Composable
fun D20Theme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = D20Typography,
        content = content,
    )
}

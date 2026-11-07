package com.ai.assistance.operit.rpg.ui.paper

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Paper surface palette shared by every screen. */
val Paper = Color(0xFFF6F2E9)
val PaperInk = Color(0xFF343936)
val PaperMuted = Color(0xFF73766F)
val PaperLine = Color(0xFFDFD9CD)
val PaperFill = Color(0xFFEEE9DF)
val PaperError = Color(0xFF925A54)
val PaperOnAccent = Color(0xFFF3EEE4)
val PaperScrim = Color(0xFF1F221E)

/** WCAG contrast ratio between two opaque colors. */
fun paperContrast(foreground: Color, background: Color): Double {
    val a = foreground.luminance().toDouble()
    val b = background.luminance().toDouble()
    return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
}

data class PaperTone(
    val id: String,
    val label: String,
    val color: Color,
    val strong: Color,
) {
    val onColor: Color get() = PaperOnAccent
}

val PaperTones = listOf(
    PaperTone("sage", "鼠尾草", Color(0xFF596F60), Color(0xFF596F60)),
    PaperTone("slate", "石板蓝", Color(0xFF586E76), Color(0xFF586E76)),
    PaperTone("rose", "烟玫瑰", Color(0xFF825F65), Color(0xFF825F65)),
    PaperTone("sand", "灰茶褐", Color(0xFF786448), Color(0xFF786448)),
    PaperTone("plum", "暮丁香", Color(0xFF716178), Color(0xFF716178)),
    PaperTone("clay", "陶土棕", Color(0xFF865F4F), Color(0xFF865F4F)),
)

fun paperTone(id: String): PaperTone =
    PaperTones.firstOrNull { it.id == id } ?: PaperTones.first { it.id == "sage" }

val LocalPaperTone = staticCompositionLocalOf { paperTone("sage") }
val LocalPaperSetTone = staticCompositionLocalOf<(String) -> Unit> { {} }
val LocalPaperFramedActions = staticCompositionLocalOf { false }
val LocalPaperTextColor = staticCompositionLocalOf { PaperInk }

val PaperAccent: Color
    @Composable @ReadOnlyComposable get() = LocalPaperTone.current.color
val PaperAccentStrong: Color
    @Composable @ReadOnlyComposable get() = LocalPaperTone.current.strong
val PaperBubble: Color
    @Composable @ReadOnlyComposable get() = LocalPaperTone.current.color
val PaperOnAccentColor: Color
    @Composable @ReadOnlyComposable get() = LocalPaperTone.current.onColor
val PaperAccentSoft: Color
    @Composable @ReadOnlyComposable get() = LocalPaperTone.current.color.copy(alpha = 0.18f).compositeOver(Paper)

private fun paperText(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = weight,
)

private val PaperTypography = Typography(
    displayLarge = paperText(32, 40),
    displayMedium = paperText(28, 36),
    displaySmall = paperText(24, 32),
    headlineLarge = paperText(22, 30, FontWeight.Medium),
    headlineMedium = paperText(20, 28, FontWeight.SemiBold),
    headlineSmall = paperText(20, 28, FontWeight.SemiBold),
    titleLarge = paperText(20, 28, FontWeight.SemiBold),
    titleMedium = paperText(17, 24, FontWeight.Medium),
    titleSmall = paperText(15, 22, FontWeight.Medium),
    bodyLarge = paperText(18, 29),
    bodyMedium = paperText(18, 29),
    bodySmall = paperText(14, 21),
    labelLarge = paperText(16, 22, FontWeight.Medium),
    labelMedium = paperText(14, 20),
    labelSmall = paperText(12, 16),
)

private fun paperColorScheme(tone: PaperTone) = lightColorScheme(
    primary = tone.color,
    onPrimary = tone.onColor,
    primaryContainer = tone.color.copy(alpha = 0.18f).compositeOver(Paper),
    onPrimaryContainer = PaperInk,
    secondary = PaperMuted,
    onSecondary = Paper,
    secondaryContainer = PaperFill,
    onSecondaryContainer = PaperInk,
    background = Paper,
    onBackground = PaperInk,
    surface = Paper,
    onSurface = PaperInk,
    surfaceVariant = PaperFill,
    onSurfaceVariant = PaperMuted,
    outline = PaperLine,
    outlineVariant = PaperLine,
    error = PaperError,
    onError = PaperOnAccent,
)

@Composable
fun PaperTheme(
    tone: PaperTone = paperTone("sage"),
    setTone: (String) -> Unit = {},
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalPaperTone provides tone,
        LocalPaperSetTone provides setTone,
    ) {
        MaterialTheme(
            colorScheme = paperColorScheme(tone),
            typography = PaperTypography,
            content = content,
        )
    }
}

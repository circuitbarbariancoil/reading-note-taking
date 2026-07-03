package com.readingnotes.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 和風 (Japanese) visual system. Restrained: paper background (生成り), ink text
 * (墨), a single indigo accent (藍鼠). UI chrome uses sans; reading/titles use a
 * serif family so it harmonizes with the Noto Serif JP used inside the WebView.
 */

// 生成り paper tones
val Paper = Color(0xFFF4EFE3)
val PaperPanel = Color(0xFFEDE6D6)
val Hairline = Color(0xFFDAD2C0)
// 墨 ink
val Sumi = Color(0xFF211E1A)
val SumiSoft = Color(0xFF6B655C)
// 藍鼠 indigo accent
val Accent = Color(0xFF3C5468)
val AccentSoft = Color(0xFFE1E6EA)

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = AccentSoft,
    onPrimaryContainer = Accent,
    secondary = SumiSoft,
    onSecondary = Color.White,
    background = Paper,
    onBackground = Sumi,
    surface = Paper,
    onSurface = Sumi,
    surfaceVariant = PaperPanel,
    onSurfaceVariant = SumiSoft,
    outline = Hairline,
    outlineVariant = Hairline,
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 22.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 19.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light, fontSize = 15.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light, fontSize = 14.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 11.sp),
)

@Composable
fun ReadingNotesTheme(content: @Composable () -> Unit) {
    // Paper/ink theme only for now; dark mode deferred.
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = LightColors,
        typography = AppTypography,
        content = content,
    )
}

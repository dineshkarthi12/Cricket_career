package com.cricketcareer.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/**
 * The one place a colour is named.
 *
 * No composable below this file may write a colour literal — CLAUDE.md's rule
 * about magic numbers applies to hex codes as much as to tuning constants, and
 * a stray `Color(0xFF2F86E0)` in a screen is how a redesign becomes a
 * three-day grep.
 *
 * A floodlit night: navy chrome, a blue header wash, gold for the player's own
 * line and for anything he did himself.
 */
object Night {
    val Background = Color(0xFF0D1A2B)
    val HeaderTop = Color(0xFF1F4874)
    val HeaderBottom = Color(0xFF132743)
    val Card = Color(0xFF16273D)
    val CardRaised = Color(0xFF1B3049)
    val Line = Color(0xFF274565)
    val Ink = Color(0xFFEAF1F8)
    val Soft = Color(0xFF9DB3C9)
    val Muted = Color(0xFF6D8299)
    val Gold = Color(0xFFF0B429)
    val GoldDim = Color(0xFF8A6512)
    val Blue = Color(0xFF2F86E0)
    val Green = Color(0xFF35C46A)
    val Red = Color(0xFFE24A3B)
}

/**
 * The extras Material 3's scheme has no slot for.
 *
 * Passed down a composition local rather than referenced as a global, so a
 * preview or a test can hand a screen a different palette without a static
 * being mutated underneath it.
 */
data class CricketColors(
    val gold: Color = Night.Gold,
    val goldDim: Color = Night.GoldDim,
    val soft: Color = Night.Soft,
    val muted: Color = Night.Muted,
    val line: Color = Night.Line,
    val cardRaised: Color = Night.CardRaised,
    val headerTop: Color = Night.HeaderTop,
    val headerBottom: Color = Night.HeaderBottom,
    val wicket: Color = Night.Red,
    val boundary: Color = Night.Gold,
    val good: Color = Night.Green,
)

val LocalCricketColors: ProvidableCompositionLocal<CricketColors> =
    staticCompositionLocalOf { CricketColors() }

private val DarkScheme = darkColorScheme(
    primary = Night.Blue,
    onPrimary = Color.White,
    secondary = Night.Gold,
    onSecondary = Color(0xFF1D1403),
    background = Night.Background,
    onBackground = Night.Ink,
    surface = Night.Card,
    onSurface = Night.Ink,
    surfaceVariant = Night.CardRaised,
    onSurfaceVariant = Night.Soft,
    error = Night.Red,
    outline = Night.Line,
)

// The game is a night game. A light scheme exists so the system setting is
// honoured rather than ignored, but the design is the dark one.
private val LightScheme = lightColorScheme(
    primary = Night.Blue,
    secondary = Night.GoldDim,
    error = Night.Red,
)

/**
 * Numbers are monospaced and everything else is not.
 *
 * A scorecard whose columns do not line up is unreadable, and a proportional
 * font guarantees they will not: "111" is narrower than "888" in almost every
 * typeface that is not monospaced.
 */
object CricketType {
    val Score = TextStyle(fontSize = 25.sp, fontWeight = FontWeight.Bold)
    val Figure = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
    val Label = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp)
    val Body = TextStyle(fontSize = 13.sp)
    val Commentary = TextStyle(fontSize = 12.5f.sp, lineHeight = 17.sp)
}

@Composable
fun CricketDynastyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalCricketColors provides CricketColors()) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = Typography(),
            content = content,
        )
    }
}

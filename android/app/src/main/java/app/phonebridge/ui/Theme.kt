package app.phonebridge.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.phonebridge.Fmt
import app.phonebridge.R
import app.phonebridge.Settings

/*
 * Brand fallback (used when wallpaper colours are off or unavailable): lapis lazuli and saffron gold from Persian
 * manuscript illumination, on a parchment surface. Dynamic colour replaces it on Android 12+ by default.
 */
private val Light = lightColorScheme(
    primary = Color(0xFF2B44A8), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE1FF), onPrimaryContainer = Color(0xFF0B1A5E),
    secondary = Color(0xFF00686E), onSecondary = Color.White,
    secondaryContainer = Color(0xFFA6EEF3), onSecondaryContainer = Color(0xFF002022),
    tertiary = Color(0xFF7A5900), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDFA0), onTertiaryContainer = Color(0xFF261A00),
    error = Color(0xFFBA1A1A), errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBF8F1), onBackground = Color(0xFF1C1B17),
    surface = Color(0xFFFBF8F1), onSurface = Color(0xFF1C1B17),
    surfaceVariant = Color(0xFFE6E1D3), onSurfaceVariant = Color(0xFF4A4639),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF6F2E9),
    surfaceContainer = Color(0xFFF0ECE2), surfaceContainerHigh = Color(0xFFEAE6DC), surfaceContainerHighest = Color(0xFFE4E0D6),
    outline = Color(0xFF7B7668), outlineVariant = Color(0xFFCDC6B4),
    inverseSurface = Color(0xFF31302B), inverseOnSurface = Color(0xFFF4F0E7), inversePrimary = Color(0xFFB9C3FF),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFB9C3FF), onPrimary = Color(0xFF13298A),
    primaryContainer = Color(0xFF2B44A8), onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Color(0xFF8AD3D9), onSecondary = Color(0xFF00363A),
    secondaryContainer = Color(0xFF004F54), onSecondaryContainer = Color(0xFFA6EEF3),
    tertiary = Color(0xFFF1C04A), onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF5C4300), onTertiaryContainer = Color(0xFFFFDFA0),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF12131A), onBackground = Color(0xFFE4E1EA),
    surface = Color(0xFF12131A), onSurface = Color(0xFFE4E1EA),
    surfaceVariant = Color(0xFF45464F), onSurfaceVariant = Color(0xFFC6C5D0),
    surfaceContainerLowest = Color(0xFF0D0E14), surfaceContainerLow = Color(0xFF1A1B22),
    surfaceContainer = Color(0xFF1E1F27), surfaceContainerHigh = Color(0xFF292A32), surfaceContainerHighest = Color(0xFF34343D),
    outline = Color(0xFF90909A), outlineVariant = Color(0xFF45464F),
    inverseSurface = Color(0xFFE4E1EA), inverseOnSurface = Color(0xFF2F3038), inversePrimary = Color(0xFF2B44A8),
)

/** Vazirmatn (SIL OFL 1.1, bundled; licence in assets/OFL-Vazirmatn.txt) covers Persian and Latin in one voice. */
val Vazirmatn = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_semibold, FontWeight.SemiBold),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
)

private fun TextStyle.v() = copy(fontFamily = Vazirmatn)

private val Base = Typography()
private val Type = Typography(
    displayLarge = Base.displayLarge.v(), displayMedium = Base.displayMedium.v(), displaySmall = Base.displaySmall.v(),
    headlineLarge = Base.headlineLarge.v(), headlineMedium = Base.headlineMedium.v().copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = Base.headlineSmall.v().copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.v().copy(fontWeight = FontWeight.SemiBold), titleMedium = Base.titleMedium.v().copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.v(),
    bodyLarge = Base.bodyLarge.v(), bodyMedium = Base.bodyMedium.v(), bodySmall = Base.bodySmall.v(),
    labelLarge = Base.labelLarge.v(), labelMedium = Base.labelMedium.v(), labelSmall = Base.labelSmall.v(),
    displayLargeEmphasized = Base.displayLargeEmphasized.v(), displayMediumEmphasized = Base.displayMediumEmphasized.v(),
    displaySmallEmphasized = Base.displaySmallEmphasized.v(), headlineLargeEmphasized = Base.headlineLargeEmphasized.v(),
    headlineMediumEmphasized = Base.headlineMediumEmphasized.v(), headlineSmallEmphasized = Base.headlineSmallEmphasized.v(),
    titleLargeEmphasized = Base.titleLargeEmphasized.v(), titleMediumEmphasized = Base.titleMediumEmphasized.v(),
    titleSmallEmphasized = Base.titleSmallEmphasized.v(), bodyLargeEmphasized = Base.bodyLargeEmphasized.v(),
    bodyMediumEmphasized = Base.bodyMediumEmphasized.v(), bodySmallEmphasized = Base.bodySmallEmphasized.v(),
    labelLargeEmphasized = Base.labelLargeEmphasized.v(), labelMediumEmphasized = Base.labelMediumEmphasized.v(),
    labelSmallEmphasized = Base.labelSmallEmphasized.v(),
)

/**
 * Samsung's wallpaper palette (One UI, seen on an A30s / Android 16) hands the dark scheme a light tertiaryContainer
 * with a dark "on" colour: pale yellow cards on a black screen. A dark scheme's containers must be the darker of the pair.
 */
fun darkFixed(c: ColorScheme): ColorScheme {
    fun Color.lum() = 0.2126f * red + 0.7152f * green + 0.0722f * blue
    fun pair(bg: Color, fg: Color) = if (bg.lum() > fg.lum()) fg to bg else bg to fg
    val (p, onP) = pair(c.primaryContainer, c.onPrimaryContainer)
    val (s, onS) = pair(c.secondaryContainer, c.onSecondaryContainer)
    val (t, onT) = pair(c.tertiaryContainer, c.onTertiaryContainer)
    return c.copy(primaryContainer = p, onPrimaryContainer = onP, secondaryContainer = s, onSecondaryContainer = onS,
        tertiaryContainer = t, onTertiaryContainer = onT)
}

/** Number/date formatting for the current language and the owner's digit/calendar choices. */
val LocalFmt = staticCompositionLocalOf { Fmt(persianDigits = false, jalali = false, fa = false) }

@Composable
fun isFa() = LocalConfiguration.current.locales[0].language == "fa"

@Composable
fun BridgeTheme(s: Settings, content: @Composable () -> Unit) {
    val dark = when (s.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val ctx = LocalContext.current
    val scheme: ColorScheme = when {
        s.dynamicColor && Build.VERSION.SDK_INT >= 31 -> if (dark) darkFixed(dynamicDarkColorScheme(ctx)) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    val fa = isFa()
    androidx.compose.runtime.CompositionLocalProvider(
        LocalFmt provides Fmt(persianDigits = s.persianDigits ?: fa, jalali = s.jalali ?: fa, fa = fa),
        LocalDark provides dark,
    ) {
        MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(), typography = Type, content = content)
    }
}

val LocalDark = staticCompositionLocalOf { false }

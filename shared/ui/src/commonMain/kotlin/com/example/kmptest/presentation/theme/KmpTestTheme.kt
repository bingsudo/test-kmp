package com.example.kmptest.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.kmptest.presentation.platform.LocalPlatform
import com.example.kmptest.domain.model.ThemeSetting

/** The brand blue (also the colour of the app icon). */
val BrandBlue = Color(0xFF0A74FF)
val BrandBlueDeep = Color(0xFF0040C8)
val BrandBlueLight = Color(0xFF5AB4FF)
val SuccessGreen = Color(0xFF22A558)

private val LightScheme = lightColorScheme(
    primary = BrandBlue, onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E7FF), onPrimaryContainer = Color(0xFF001C44),
    secondary = Color(0xFF3D6BB8), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3EDFF), onSecondaryContainer = Color(0xFF0B2A5C),
    tertiary = Color(0xFF008FBF), onTertiary = Color.White,
    background = Color(0xFFF4F8FF), onBackground = Color(0xFF0B1B33),
    surface = Color(0xFFF4F8FF), onSurface = Color(0xFF0B1B33),
    surfaceVariant = Color(0xFFDDE7F6), onSurfaceVariant = Color(0xFF475A78),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF8FAFF),
    surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFEAF1FD), surfaceContainerHighest = Color(0xFFE0EAFA),
    outline = Color(0xFF8197B8), outlineVariant = Color(0xFFD0DCF0),
)

/** Dark navy; used for the explicit "Dark" choice. */
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF4DA3FF), onPrimary = Color(0xFF00234F),
    primaryContainer = Color(0xFF0B3C86), onPrimaryContainer = Color(0xFFD7E7FF),
    secondary = Color(0xFF8DB5F0), onSecondary = Color(0xFF0B2A5C),
    secondaryContainer = Color(0xFF1B3A6B), onSecondaryContainer = Color(0xFFDCE8FF),
    tertiary = Color(0xFF5CC8F2), onTertiary = Color(0xFF00344A),
    background = Color(0xFF0B1220), onBackground = Color(0xFFE6EEFA),
    surface = Color(0xFF0B1220), onSurface = Color(0xFFE6EEFA),
    surfaceVariant = Color(0xFF263449), onSurfaceVariant = Color(0xFFA9B9D1),
    surfaceContainerLowest = Color(0xFF070C16), surfaceContainerLow = Color(0xFF101A2B),
    surfaceContainer = Color(0xFF141F33), surfaceContainerHigh = Color(0xFF1B2840), surfaceContainerHighest = Color(0xFF233250),
    outline = Color(0xFF6B7F9E), outlineVariant = Color(0xFF2A3A55),
)

/** Pure black (follows a dark system theme); same blue accents. */
private val BlackScheme = DarkScheme.copy(
    background = Color.Black, surface = Color.Black,
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF07090E),
    surfaceContainer = Color(0xFF0E131C), surfaceContainerHigh = Color(0xFF151C29), surfaceContainerHighest = Color(0xFF1C2535),
    surfaceVariant = Color(0xFF1E2837), outlineVariant = Color(0xFF222E42),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(14.dp), medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp),
)

private val AppTypography = Typography().let {
    it.copy(
        titleLarge = it.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
        titleMedium = it.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = it.titleSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
        labelLarge = it.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    )
}

/**
 * SYSTEM follows the system: light, or pure black when the system is dark. LIGHT / DARK / BLACK force a scheme.
 */
@Composable
fun KmpTestTheme(theme: ThemeSetting, fontScale: Float, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val scheme: ColorScheme = when (theme) {
        ThemeSetting.SYSTEM -> if (systemDark) BlackScheme else LightScheme
        ThemeSetting.LIGHT -> LightScheme
        ThemeSetting.DARK -> DarkScheme
        ThemeSetting.BLACK -> BlackScheme
    }
    val dark = scheme !== LightScheme
    val platform = LocalPlatform.current
    SideEffect { platform.setSystemBarsDark(dark) }
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * fontScale)) {
        MaterialTheme(colorScheme = scheme, shapes = AppShapes, typography = AppTypography, content = content)
    }
}

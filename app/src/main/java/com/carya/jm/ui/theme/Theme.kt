package com.carya.jm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    // Primary - 黑色
    primary = PrimaryLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E8E8),
    onPrimaryContainer = PrimaryLight,

    // Secondary - 深灰
    secondary = Color(0xFF555555),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDEDED),
    onSecondaryContainer = Color(0xFF333333),

    // Tertiary - 中灰
    tertiary = Color(0xFF777777),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEFEFEF),
    onTertiaryContainer = Color(0xFF444444),

    // Background
    background = BackgroundLight,
    onBackground = TextPrimaryLight,

    // Surface
    surface = SurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = Color(0xFFF1F1F1),
    onSurfaceVariant = TextSecondaryLight,
    surfaceDim = Color(0xFFE5E5E5),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F7F7),
    surfaceContainer = Color(0xFFF2F2F2),
    surfaceContainerHigh = Color(0xFFECECEC),
    surfaceContainerHighest = Color(0xFFE6E6E6),

    // Outline
    outline = BorderLight,
    outlineVariant = Color(0xFFE0E0E0),

    // Error
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    // Inverse
    inversePrimary = Color(0xFFC4C4C4),
    inverseSurface = Color(0xFF303030),
    inverseOnSurface = Color(0xFFF5F5F5),

    // Scrim
    scrim = Color.Black,
)

private val DarkColorScheme = darkColorScheme(
    // Primary - 白色
    primary = PrimaryDark,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF3A3A3A),
    onPrimaryContainer = PrimaryDark,

    // Secondary - 浅灰
    secondary = Color(0xFFBBBBBB),
    onSecondary = Color(0xFF2A2A2A),
    secondaryContainer = Color(0xFF2E2E2E),
    onSecondaryContainer = Color(0xFFDDDDDD),

    // Tertiary - 中灰
    tertiary = Color(0xFF999999),
    onTertiary = Color(0xFF222222),
    tertiaryContainer = Color(0xFF2A2A2A),
    onTertiaryContainer = Color(0xFFCCCCCC),

    // Background
    background = BackgroundDark,
    onBackground = TextPrimaryDark,

    // Surface
    surface = SurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = Color(0xFF242424),
    onSurfaceVariant = TextSecondaryDark,
    surfaceDim = Color(0xFF0F0F0F),
    surfaceBright = Color(0xFF2A2A2A),
    surfaceContainerLowest = Color(0xFF0A0A0A),
    surfaceContainerLow = Color(0xFF141414),
    surfaceContainer = Color(0xFF1A1A1A),
    surfaceContainerHigh = Color(0xFF202020),
    surfaceContainerHighest = Color(0xFF282828),

    // Outline
    outline = BorderDark,
    outlineVariant = Color(0xFF3A3A3A),

    // Error
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    // Inverse
    inversePrimary = Color(0xFF555555),
    inverseSurface = Color(0xFFE5E5E5),
    inverseOnSurface = Color(0xFF111111),

    // Scrim
    scrim = Color.Black,
)

@Composable
fun JMTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) {
            DarkColorScheme
        } else {
            LightColorScheme
        },
        typography = Typography,
        content = content,
    )
}

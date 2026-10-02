package app.alertpower.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Deep navy surfaces with an electric cyan accent. Violet for secondary, green for "armed". */
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF22D3EE),
    onPrimary = Color(0xFF00262D),
    primaryContainer = Color(0xFF0B3C47),
    onPrimaryContainer = Color(0xFFA5F3FC),
    secondary = Color(0xFFA78BFA),
    onSecondary = Color(0xFF1E1140),
    secondaryContainer = Color(0xFF2E1F5E),
    onSecondaryContainer = Color(0xFFE9D5FF),
    tertiary = Color(0xFF34D399),
    onTertiary = Color(0xFF00291A),
    tertiaryContainer = Color(0xFF064E3B),
    onTertiaryContainer = Color(0xFFA7F3D0),
    error = Color(0xFFF87171),
    onError = Color(0xFF3B0A0A),
    errorContainer = Color(0xFF4C1515),
    onErrorContainer = Color(0xFFFECACA),
    background = Color(0xFF070B12),
    onBackground = Color(0xFFE6EDF5),
    surface = Color(0xFF070B12),
    onSurface = Color(0xFFE6EDF5),
    surfaceVariant = Color(0xFF16202E),
    onSurfaceVariant = Color(0xFF9FB0C3),
    outline = Color(0xFF3A4D66),
    outlineVariant = Color(0xFF1F2B3A),
    surfaceContainerLowest = Color(0xFF04070C),
    surfaceContainerLow = Color(0xFF0C1119),
    surfaceContainer = Color(0xFF101723),
    surfaceContainerHigh = Color(0xFF162030),
    surfaceContainerHighest = Color(0xFF1C283A),
    inverseSurface = Color(0xFFE6EDF5),
    inverseOnSurface = Color(0xFF0C1119),
    inversePrimary = Color(0xFF0E7490),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0891B2),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCFF7FF),
    onPrimaryContainer = Color(0xFF003A44),
    secondary = Color(0xFF7C3AED),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDE9FE),
    onSecondaryContainer = Color(0xFF2E1065),
    tertiary = Color(0xFF059669),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD1FAE5),
    onTertiaryContainer = Color(0xFF064E3B),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    background = Color(0xFFF4F7FB),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFF4F7FB),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFF94A3B8),
    outlineVariant = Color(0xFFCBD5E1),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFCFE),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEEF3F8),
    surfaceContainerHighest = Color(0xFFE3EAF2),
    inverseSurface = Color(0xFF0F172A),
    inverseOnSurface = Color(0xFFF4F7FB),
    inversePrimary = Color(0xFF67E8F9),
)

private val AppTypography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = base.labelSmall.copy(letterSpacing = 1.sp),
    )
}

@Composable
fun AlertPowerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = AppTypography,
        content = content,
    )
}

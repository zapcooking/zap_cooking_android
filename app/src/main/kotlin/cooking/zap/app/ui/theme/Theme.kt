package cooking.zap.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils

val LocalWispColors = androidx.compose.runtime.staticCompositionLocalOf {
    WispColors(
        backgroundColor = Color.Unspecified,
        zapColor = Color.Unspecified,
        repostColor = Color.Unspecified,
        bookmarkColor = Color.Unspecified,
        paidColor = Color.Unspecified
    )
}

data class WispColors(
    val backgroundColor: Color,
    val zapColor: Color,
    val repostColor: Color,
    val bookmarkColor: Color,
    val paidColor: Color
)

object WispThemeColors {
    val backgroundColor: Color @Composable get() = LocalWispColors.current.backgroundColor
    val zapColor: Color @Composable get() = LocalWispColors.current.zapColor
    val repostColor: Color @Composable get() = LocalWispColors.current.repostColor
    val bookmarkColor: Color @Composable get() = LocalWispColors.current.bookmarkColor
    val paidColor: Color @Composable get() = LocalWispColors.current.paidColor
}

@Composable
fun wispSwitchColors(): SwitchColors = SwitchDefaults.colors(
    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
    uncheckedBorderColor = MaterialTheme.colorScheme.outline
)

private fun lightenColor(color: Color, fraction: Float = 0.3f): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    hsl[1] = (hsl[1] * 0.7f).coerceIn(0f, 1f)
    hsl[2] = (hsl[2] + (1f - hsl[2]) * fraction).coerceIn(0f, 1f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun darkenColor(color: Color, fraction: Float = 0.6f): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    hsl[2] = (hsl[2] * (1f - fraction)).coerceIn(0f, 1f)
    return Color(ColorUtils.HSLToColor(hsl))
}

@Composable
fun WispTheme(
    isDarkTheme: Boolean = true,
    isLargeText: Boolean = false,
    content: @Composable () -> Unit
) {
    // One palette, two cuts. The accent color and the sixteen alternate
    // schemes are gone along with the picker that chose between them, so
    // there is nothing left to branch on but light versus dark.
    val colors = if (isDarkTheme) Themes.brand.dark else Themes.brand.light
    val primaryContainer = remember(colors.primary, isDarkTheme) {
        if (isDarkTheme) darkenColor(colors.primary, 0.6f) else lightenColor(colors.primary, 0.7f)
    }
    val onPrimaryContainer = remember(colors.primary, isDarkTheme) {
        if (isDarkTheme) lightenColor(colors.primary, 0.5f) else darkenColor(colors.primary, 0.4f)
    }

    // Zap Cooking brand danger (web src/app.css --color-danger): #dc2626
    // light / #ef4444 dark. Material 3's default `error` renders pinkish in
    // dark and a muted brick red in light; setting `error` explicitly
    // propagates the brand red to every `MaterialTheme.colorScheme.error`
    // consumer (logout, alerts, destructive labels).
    val dangerColor = if (isDarkTheme) Color(0xFFEF4444) else Color(0xFFDC2626)

    val colorScheme = if (isDarkTheme) {
        darkColorScheme(
            primary = colors.primary,
            onPrimary = Color.White,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = colors.secondary,
            background = colors.background,
            surface = colors.surface,
            surfaceVariant = colors.surfaceVariant,
            onBackground = colors.onBackground,
            onSurface = colors.onSurface,
            onSurfaceVariant = colors.onSurfaceVariant,
            outline = colors.outline,
            error = dangerColor,
            onError = Color.White
        )
    } else {
        lightColorScheme(
            primary = colors.primary,
            onPrimary = Color.White,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = colors.secondary,
            background = colors.background,
            surface = colors.surface,
            surfaceVariant = colors.surfaceVariant,
            onBackground = colors.onBackground,
            onSurface = colors.onSurface,
            onSurfaceVariant = colors.onSurfaceVariant,
            outline = colors.outline,
            error = dangerColor,
            onError = Color.White
        )
    }

    val wispColors = WispColors(
        backgroundColor = colors.background,
        zapColor = colors.zapColor,
        repostColor = colors.repostColor,
        bookmarkColor = colors.bookmarkColor,
        paidColor = colors.paidColor
    )

    val typography = remember(isLargeText) { buildWispTypography(isLargeText) }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        content = {
            CompositionLocalProvider(LocalWispColors provides wispColors) {
                content()
            }
        }
    )
}

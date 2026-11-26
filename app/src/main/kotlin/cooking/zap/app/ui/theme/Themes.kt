package cooking.zap.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The Zap Cooking palette, in a light and a dark cut.
 *
 * There is exactly one palette. The app used to ship sixteen selectable color
 * schemes plus a free-form accent picker; settings now offers only
 * System/Light/Dark, so the alternates and the custom accent are gone and the
 * brand renders the same for everyone.
 *
 * Tokens mirror the web app's `src/app.css` — seed orange #ec4700 light /
 * #ff5722 dark, with surfaces, text, and outline from the same palette.
 */
object Themes {
    val brand = ThemePreset(
        dark = ThemeColors(
            primary = Color(0xFFFF5722),
            secondary = Color(0xFFFF8A65),
            background = Color(0xFF111827),
            surface = Color(0xFF1F2937),
            surfaceVariant = Color(0xFF374151),
            onBackground = Color(0xFFF3F4F6),
            onSurface = Color(0xFFF3F4F6),
            onSurfaceVariant = Color(0xFFD1D5DB),
            outline = Color(0xFF4B5563),
            zapColor = Color(0xFFFF5722),
            repostColor = Color(0xFF4CAF50),
            bookmarkColor = Color(0xFFFF5722),
            paidColor = Color(0xFFFFD54F)
        ),
        light = ThemeColors(
            primary = Color(0xFFEC4700),
            secondary = Color(0xFFFF8A50),
            background = Color(0xFFFFFFFF),
            surface = Color(0xFFF9FAFB),
            surfaceVariant = Color(0xFFF3F4F6),
            onBackground = Color(0xFF111827),
            onSurface = Color(0xFF111827),
            onSurfaceVariant = Color(0xFF4B5563),
            outline = Color(0xFFE5E7EB),
            zapColor = Color(0xFFEC4700),
            repostColor = Color(0xFF2E7D32),
            bookmarkColor = Color(0xFFEC4700),
            paidColor = Color(0xFFC9A000)
        )
    )
}

data class ThemePreset(
    val dark: ThemeColors,
    val light: ThemeColors
)

data class ThemeColors(
    val primary: Color,
    val secondary: Color,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onBackground: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val zapColor: Color,
    val repostColor: Color,
    val bookmarkColor: Color,
    val paidColor: Color
)

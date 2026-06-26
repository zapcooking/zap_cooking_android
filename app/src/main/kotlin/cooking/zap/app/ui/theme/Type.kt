package cooking.zap.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import cooking.zap.app.R

// Both fonts are variable (single TTF spanning weights 100–900). On API 26+ we
// select a weight via the wght variation axis; minSdk is 26 so this always applies.
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variableFont(resId: Int, weight: FontWeight) = Font(
    resId = resId,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
)

/**
 * Display / heading font — mirrors the web `--font-display: Geist`.
 * Used for Material titles, headlines, and display styles.
 */
val WispDisplayFont = FontFamily(
    variableFont(R.font.geist_variable, FontWeight.Normal),
    variableFont(R.font.geist_variable, FontWeight.Medium),
    variableFont(R.font.geist_variable, FontWeight.SemiBold),
    variableFont(R.font.geist_variable, FontWeight.Bold),
)

/**
 * Body / UI font — mirrors the web `--font-sans: Albert Sans`.
 * Used for body and label styles, and anywhere screens previously forced sans-serif.
 */
val WispBodyFont = FontFamily(
    variableFont(R.font.albert_sans, FontWeight.Light),
    variableFont(R.font.albert_sans, FontWeight.Normal),
    variableFont(R.font.albert_sans, FontWeight.Medium),
    variableFont(R.font.albert_sans, FontWeight.SemiBold),
    variableFont(R.font.albert_sans, FontWeight.Bold),
)

// Web headings use Tailwind `tracking-tight` (-0.025em) + `font-semibold`.
private val TIGHT = (-0.025).em

/**
 * App typography: Geist on headings/titles (semibold, tight tracking), Albert Sans
 * on body/labels. Every Material style gets an explicit family so nothing falls back
 * to the platform default (Roboto). Sizes preserve the previously-tuned scale.
 */
fun buildWispTypography(isLargeText: Boolean): Typography {
    val d = Typography()
    val display = WispDisplayFont
    val body = WispBodyFont

    fun head(style: TextStyle) = style.copy(fontFamily = display, letterSpacing = TIGHT)

    return if (!isLargeText) {
        Typography(
            displayLarge = head(d.displayLarge),
            displayMedium = head(d.displayMedium),
            displaySmall = head(d.displaySmall),
            headlineLarge = head(d.headlineLarge),
            headlineMedium = head(d.headlineMedium),
            headlineSmall = head(d.headlineSmall),
            titleLarge = TextStyle(fontFamily = display, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = TIGHT),
            titleMedium = TextStyle(fontFamily = display, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = TIGHT),
            titleSmall = d.titleSmall.copy(fontFamily = display),
            bodyLarge = TextStyle(fontFamily = body, fontSize = 15.sp, lineHeight = 22.sp),
            bodyMedium = TextStyle(fontFamily = body, fontSize = 14.sp),
            bodySmall = TextStyle(fontFamily = body, fontSize = 12.sp),
            labelLarge = d.labelLarge.copy(fontFamily = body),
            labelMedium = d.labelMedium.copy(fontFamily = body),
            labelSmall = TextStyle(fontFamily = body, fontSize = 11.sp),
        )
    } else {
        Typography(
            displayLarge = head(d.displayLarge),
            displayMedium = head(d.displayMedium),
            displaySmall = head(d.displaySmall),
            headlineLarge = head(d.headlineLarge),
            headlineMedium = head(d.headlineMedium),
            headlineSmall = head(d.headlineSmall),
            titleLarge = TextStyle(fontFamily = display, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = TIGHT),
            titleMedium = TextStyle(fontFamily = display, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = TIGHT),
            titleSmall = d.titleSmall.copy(fontFamily = display),
            bodyLarge = TextStyle(fontFamily = body, fontSize = 17.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontFamily = body, fontSize = 16.sp),
            bodySmall = TextStyle(fontFamily = body, fontSize = 14.sp),
            labelLarge = d.labelLarge.copy(fontFamily = body),
            labelMedium = d.labelMedium.copy(fontFamily = body),
            labelSmall = TextStyle(fontFamily = body, fontSize = 13.sp),
        )
    }
}

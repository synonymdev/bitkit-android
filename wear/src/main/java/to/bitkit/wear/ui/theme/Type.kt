package to.bitkit.wear.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Typography
import to.bitkit.wear.R

val InterTightFontFamily = FontFamily(
    Font(R.font.inter_tight_black, FontWeight.Black),
    Font(R.font.inter_tight_bold, FontWeight.Bold),
    Font(R.font.inter_tight_extra_bold, FontWeight.ExtraBold),
    Font(R.font.inter_tight_medium, FontWeight.Medium),
    Font(R.font.inter_tight_regular, FontWeight.Normal),
    Font(R.font.inter_tight_semi_bold, FontWeight.SemiBold),
)

/** Text styles copied from the phone's `AppTextStyles` in app/src/main/java/to/bitkit/ui/theme/Type.kt. */
object AppTextStyles {
    /** The large balance, as on the phone; the balance scales it down to fit a small round screen. */
    val Display = TextStyle(
        fontWeight = FontWeight.Black,
        fontSize = 44.sp,
        lineHeight = 44.sp,
        letterSpacing = (-1).sp,
        fontFamily = InterTightFontFamily,
    )

    /** The small balance row, as on the phone, where it is uppercased. */
    val CaptionM = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.8.sp,
        fontFamily = InterTightFontFamily,
    )
}

/** Wear's type scale, which honours the user's font size, in Inter Tight like the phone. */
val WearTypography = Typography(defaultFontFamily = InterTightFontFamily)

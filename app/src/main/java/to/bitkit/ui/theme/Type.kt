package to.bitkit.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import to.bitkit.R

val InterFontFamily = FontFamily(
    Font(R.font.inter_tight_black, FontWeight.Black),
    Font(R.font.inter_tight_bold, FontWeight.Bold),
    Font(R.font.inter_tight_extra_bold, FontWeight.ExtraBold),
    Font(R.font.inter_tight_medium, FontWeight.Medium),
    Font(R.font.inter_tight_regular, FontWeight.Normal),
    Font(R.font.inter_tight_semi_bold, FontWeight.SemiBold),
)

val DamionFontFamily = FontFamily(
    Font(R.font.damion_regular, FontWeight.Normal),
)

val Typography = Typography(
    // Default Text:
    bodyLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
    ),
    labelLarge = AppTextStyles.BodySSB, // Buttons Text
)

object AppTextStyles {
    val Title = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.4.sp,
        fontFamily = InterFontFamily,
    )
    val Subtitle = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        letterSpacing = 0.4.sp,
        fontFamily = InterFontFamily,
    )
    val BodyS = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.4.sp,
        fontFamily = InterFontFamily,
        textAlign = TextAlign.Start,
    )
    val BodyMSB = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.4.sp,
        fontFamily = InterFontFamily,
        textAlign = TextAlign.Start,
    )
    val BodySSB = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.4.sp,
        fontFamily = InterFontFamily,
        textAlign = TextAlign.Start,
    )
}

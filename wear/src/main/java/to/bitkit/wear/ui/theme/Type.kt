package to.bitkit.wear.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.wear.compose.material3.Typography
import to.bitkit.wear.R

val InterTightFontFamily = FontFamily(
    Font(R.font.inter_tight_bold, FontWeight.Bold),
    Font(R.font.inter_tight_medium, FontWeight.Medium),
    Font(R.font.inter_tight_regular, FontWeight.Normal),
    Font(R.font.inter_tight_semi_bold, FontWeight.SemiBold),
)

/** Wear's type scale, which honours the user's font size, in Inter Tight like the phone. */
val WearTypography = Typography(defaultFontFamily = InterTightFontFamily)

package to.bitkit.ui.screens.widgets.weather

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import to.bitkit.ui.theme.InterFontFamily

internal val WeatherFeeValueTextStyle = TextStyle(
    fontWeight = FontWeight.Bold,
    fontSize = 30.sp,
    lineHeight = 30.sp,
    letterSpacing = (-1).sp,
    fontFamily = InterFontFamily,
)

@Composable
internal fun WeatherFeeValueText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = WeatherFeeValueTextStyle,
        color = color,
        modifier = modifier
    )
}

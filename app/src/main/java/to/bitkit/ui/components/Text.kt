package to.bitkit.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.toUpperCase
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

@Composable
fun Display(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 44.sp,
    fontWeight: FontWeight = FontWeight.Black,
    lineHeight: Float = 44f,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text = text.uppercase(),
        style = TextStyle(
            fontSize = fontSize,
            fontWeight = fontWeight,
            letterSpacing = (-1).sp,
            lineHeight = lineHeight.sp,
            fontFamily = FontFamily.Default,
            color = color
        ),
        modifier = modifier
    )
}

@Composable
fun Display(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 44.sp,
    fontWeight: FontWeight = FontWeight.Black,
    lineHeight: Float = 44f,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text = text.toUpperCase(),
        style = TextStyle(
            fontSize = fontSize,
            fontWeight = fontWeight,
            lineHeight = lineHeight.sp,
            letterSpacing = (-1).sp,
            fontFamily = FontFamily.Default,
            color = color
        ),
        modifier = modifier
    )
}

@Composable
fun Headline(
    text: String,
    lineHeight: Float = 30f,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text = text,
        style = TextStyle(
            fontSize = 30.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-1).sp,
            lineHeight = lineHeight.sp,
            fontFamily = FontFamily.Default,
            color = color
        ),
    )
}

@Composable
fun Title(
    text: String,
    lineHeight: Float = 26f,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text = text,
        style = TextStyle(
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.4.sp,
            lineHeight = lineHeight.sp,
            fontFamily = FontFamily.Default,
            color = color
        ),
    )
}

@Composable
fun Subtitle(
    text: String,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text = text,
        style = TextStyle(
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.4.sp,
            fontFamily = FontFamily.Default,
            color = color
        )
    )
}

@Composable
fun BodyM(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    BodyM(
        text = AnnotatedString(text),
        color = color,
        modifier = modifier,
    )
}

@Composable
fun BodyM(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text = text,
        style = TextStyle(
            fontSize = 17.sp,
            fontWeight = FontWeight.Normal,
            lineHeight = 22.sp,
            letterSpacing = 0.4.sp,
            fontFamily = FontFamily.Default,
            color = color
        ),
        textAlign = TextAlign.Start,
        modifier = modifier,
    )
}

@Composable
fun BodyMSB(
    text: String,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text = text,
        style = TextStyle(
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 22.sp,
            letterSpacing = 0.4.sp,
            fontFamily = FontFamily.Default,
            color = color
        ),
        textAlign = TextAlign.Start
    )
}

@Composable
fun BodyMB(
    text: String,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text = text,
        style = TextStyle(
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 22.sp,
            letterSpacing = 0.4.sp,
            fontFamily = FontFamily.Default,
            color = color
        ),
        textAlign = TextAlign.Start
    )
}

@Composable
fun BodyS(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text = text,
        style = TextStyle(
            fontSize = 15.sp,
            fontWeight = FontWeight.Normal,
            lineHeight = 20.sp,
            letterSpacing = 0.4.sp,
            fontFamily = FontFamily.Default,
            color = color,
        ),
        textAlign = TextAlign.Start,
        modifier = modifier,
    )
}

@Composable
fun BodySSB(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    BodySSB(
        text = AnnotatedString(text),
        color = color,
        modifier = modifier,
    )
}

@Composable
fun BodySSB(
    text: AnnotatedString,
    color: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = TextStyle(
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 20.sp,
            letterSpacing = 0.4.sp,
            fontFamily = FontFamily.Default,
            color = color,
        ),
        textAlign = TextAlign.Start,
        modifier = modifier,
    )
}

@Composable
fun Text13UP(
    text: String,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text = text,
        style = TextStyle(
            fontSize = 13.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.4.sp,
            fontFamily = FontFamily.Default,
            color = color
        ),
        textAlign = TextAlign.Start
    )
}

@Composable
fun Caption(
    text: String,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text = text,
        style = TextStyle(
            fontSize = 13.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.4.sp,
            fontFamily = FontFamily.Default,
            color = color
        ),
        textAlign = TextAlign.Start
    )
}

@Composable
fun Footnote(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(
        text = text,
        style = TextStyle(
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.4.sp,
            fontFamily = FontFamily.Default,
            color = color
        ),
        textAlign = TextAlign.Start,
        modifier = modifier,
    )
}

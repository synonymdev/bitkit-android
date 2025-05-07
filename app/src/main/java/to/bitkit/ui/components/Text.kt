package to.bitkit.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.toUpperCase
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.theme.InterFontFamily

@Composable
fun Display(
    text: String,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Black,
    fontSize: TextUnit = 44.sp,
    lineHeight: TextUnit = 44.sp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text = text.uppercase(),
        style = TextStyle(
            fontWeight = fontWeight,
            fontSize = fontSize,
            lineHeight = lineHeight,
            letterSpacing = (-1).sp,
            fontFamily = InterFontFamily,
            color = color,
        ),
        modifier = modifier,
    )
}

@Composable
fun Display(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Black,
    fontSize: TextUnit = 44.sp,
    lineHeight: TextUnit = 44.sp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text = text.toUpperCase(),
        style = TextStyle(
            fontWeight = fontWeight,
            fontSize = fontSize,
            lineHeight = lineHeight,
            letterSpacing = (-1).sp,
            fontFamily = InterFontFamily,
            color = color,
        ),
        modifier = modifier,
    )
}

@Composable
fun Headline(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    lineHeight: TextUnit = 30.sp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text = text.toUpperCase(),
        style = TextStyle(
            fontWeight = FontWeight.Black,
            fontSize = 30.sp,
            lineHeight = lineHeight,
            letterSpacing = (-1).sp,
            fontFamily = InterFontFamily,
            color = color,
        ),
        modifier = modifier,
    )
}

@Composable
fun Title(
    text: String,
    modifier: Modifier = Modifier,
    lineHeight: TextUnit = 26.sp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text = text,
        style = TextStyle(
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            lineHeight = lineHeight,
            letterSpacing = 0.4.sp,
            fontFamily = InterFontFamily,
            color = color,
        ),
        modifier = modifier,
    )
}

@Composable
fun Subtitle(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text = text,
        style = TextStyle(
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            letterSpacing = 0.4.sp,
            fontFamily = InterFontFamily,
            color = color,
        ),
        modifier = modifier,
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
            fontWeight = FontWeight.Normal,
            fontSize = 17.sp,
            lineHeight = 22.sp,
            letterSpacing = 0.4.sp,
            fontFamily = InterFontFamily,
            color = color,
            textAlign = TextAlign.Start,
        ),
        modifier = modifier,
    )
}

@Composable
fun BodyMSB(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text = text,
        style = TextStyle(
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
            lineHeight = 22.sp,
            letterSpacing = 0.4.sp,
            fontFamily = InterFontFamily,
            color = color,
            textAlign = TextAlign.Start,
        ),
        modifier = modifier,
    )
}

@Composable
fun BodyMB(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text = text,
        style = TextStyle(
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            lineHeight = 22.sp,
            letterSpacing = 0.4.sp,
            fontFamily = InterFontFamily,
            color = color,
            textAlign = TextAlign.Start,
        ),
        modifier = modifier,
    )
}

@Composable
fun BodyS(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    textAlign: TextAlign = TextAlign.Start,
) {
    BodyS(
        text = AnnotatedString(text),
        modifier = modifier,
        color = color,
        textAlign = textAlign,
    )
}

@Composable
fun BodyS(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    textAlign: TextAlign = TextAlign.Start,
) {
    Text(
        text = text,
        style = TextStyle(
            fontWeight = FontWeight.Normal,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.4.sp,
            fontFamily = InterFontFamily,
            color = color,
            textAlign = textAlign,
        ),
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
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text = text,
        style = TextStyle(
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.4.sp,
            fontFamily = InterFontFamily,
            color = color,
            textAlign = TextAlign.Start,
        ),
        modifier = modifier,
    )
}


@Composable
fun BodySB(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text = text,
        style = TextStyle(
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.4.sp,
            fontFamily = InterFontFamily,
            color = color,
            textAlign = TextAlign.Start,
        ),
        modifier = modifier,
    )
}


@Composable
fun Text13Up(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text = text.uppercase(),
        style = TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.4.sp,
            fontFamily = InterFontFamily,
            color = color,
            textAlign = TextAlign.Start,
        ),
        modifier = modifier,
    )
}

@Composable
fun Caption(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text = text,
        style = TextStyle(
            fontWeight = FontWeight.Normal,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.4.sp,
            fontFamily = InterFontFamily,
            color = color,
            textAlign = TextAlign.Start,
        ),
        modifier = modifier,
    )
}

@Composable
fun CaptionB(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text = text,
        style = TextStyle(
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.4.sp,
            fontFamily = InterFontFamily,
            color = color,
            textAlign = TextAlign.Start,
        ),
        modifier = modifier,
    )
}

@Composable
fun Caption13Up(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text = text.uppercase(),
        style = TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.4.sp,
            fontFamily = InterFontFamily,
            color = color,
            textAlign = TextAlign.Start,
        ),
        modifier = modifier,
    )
}

@Composable
fun Footnote(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Colors.White32,
) {
    Text(
        text = text,
        style = TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.4.sp,
            fontFamily = InterFontFamily,
            color = color,
            textAlign = TextAlign.Start,
        ),
        modifier = modifier,
    )
}

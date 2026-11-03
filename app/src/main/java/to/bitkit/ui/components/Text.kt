
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.toUpperCase
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import to.bitkit.ui.theme.AppTextStyles
import to.bitkit.ui.theme.Colors

@Composable
fun Display(
    text: String,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Black,
    fontSize: TextUnit = 44.sp,
    color: Color = MaterialTheme.colorScheme.primary,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = if (maxLines == 1) TextOverflow.Ellipsis else TextOverflow.Clip,
    textAlign: TextAlign? = null,
) {
    Text(
        text = text.uppercase(),
        style = AppTextStyles.Display.merge(
            fontWeight = fontWeight,
            fontSize = fontSize,
            color = color,
        ),
        maxLines = maxLines,
        overflow = overflow,
        textAlign = textAlign,
        modifier = modifier
    )
}

@Composable
fun Display(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 44.sp,
    color: Color = MaterialTheme.colorScheme.primary,
    letterSpacing: TextUnit = (-1).sp,
) {
    Text(
        text = text.toUpperCase(),
        style = AppTextStyles.Display.merge(
            fontSize = fontSize,
            lineHeight = fontSize,
            letterSpacing = letterSpacing,
            color = color,
        ),
        modifier = modifier
    )
}

@Composable
fun Display34(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = if (maxLines == 1) TextOverflow.Ellipsis else TextOverflow.Clip,
) {
    Text(
        text = text,
        style = AppTextStyles.Display34.merge(
            color = color,
        ),
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier
    )
}

@Composable
fun Headline(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    textAlign: TextAlign = TextAlign.Start,
) {
    Text(
        text = text.toUpperCase(),
        style = AppTextStyles.Headline.merge(
            color = color,
            textAlign = textAlign,
        ),
        modifier = modifier
    )
}

@Composable
fun Headline20(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text = text,
        style = AppTextStyles.Headline.merge(
            fontSize = 20.sp,
            lineHeight = 20.sp,
            letterSpacing = (-.5).sp,
            color = color,
        ),
        modifier = modifier
    )
}

@Composable
fun Headline24(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text = text.toUpperCase(),
        style = AppTextStyles.Headline.merge(
            fontSize = 24.sp,
            lineHeight = 24.sp,
            color = color,
        ),
        modifier = modifier
    )
}

@Composable
fun Title(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    textAlign: TextAlign = TextAlign.Unspecified,
    style: TextStyle = AppTextStyles.Title,
) {
    Text(
        text = text,
        maxLines = maxLines,
        overflow = overflow,
        style = style.merge(
            color = color,
            textAlign = textAlign,
        ),
        modifier = modifier
    )
}

@Composable
fun Subtitle(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    textAlign: TextAlign = TextAlign.Start,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = if (maxLines == 1) TextOverflow.Ellipsis else TextOverflow.Clip,
    style: TextStyle = AppTextStyles.Subtitle,
) {
    Text(
        text = text,
        style = style.merge(
            color = color,
            textAlign = textAlign,
        ),
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier
    )
}

@Composable
fun BodyM(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    textAlign: TextAlign = TextAlign.Start,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    overflow: TextOverflow = if (maxLines == 1) TextOverflow.Ellipsis else TextOverflow.Clip,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    style: TextStyle = AppTextStyles.BodyM,
) {
    BodyM(
        text = AnnotatedString(text),
        style = style,
        letterSpacing = letterSpacing,
        color = color,
        modifier = modifier,
        textAlign = textAlign,
        maxLines = maxLines,
        minLines = minLines,
        overflow = overflow,
    )
}

@Composable
fun BodyM(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    textAlign: TextAlign = TextAlign.Start,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    overflow: TextOverflow = if (maxLines == 1) TextOverflow.Ellipsis else TextOverflow.Clip,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    style: TextStyle = AppTextStyles.BodyM,
) {
    Text(
        text = text,
        style = style.merge(
            letterSpacing = letterSpacing,
            color = color,
            textAlign = textAlign,
        ),
        maxLines = maxLines,
        minLines = minLines,
        overflow = overflow,
        modifier = modifier
    )
}

@Composable
fun BodyMSB(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign = TextAlign.Start,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    style: TextStyle = AppTextStyles.BodyMSB,
) {
    BodyMSB(
        text = AnnotatedString(text),
        style = style,
        letterSpacing = letterSpacing,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier,
        textAlign = textAlign,
    )
}

@Composable
fun BodyMSB(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign = TextAlign.Start,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    style: TextStyle = AppTextStyles.BodyMSB,
) {
    Text(
        text = text,
        style = style.merge(
            letterSpacing = letterSpacing,
            color = color,
            textAlign = textAlign,
        ),
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier
    )
}

@Composable
fun BodyMB(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign = TextAlign.Start,
) {
    Text(
        text = text,
        style = AppTextStyles.BodyMB.merge(
            color = color,
            textAlign = textAlign,
        ),
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier
    )
}

@Composable
fun BodyS(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    textAlign: TextAlign = TextAlign.Start,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    letterSpacing: TextUnit = TextUnit.Unspecified,
) {
    BodyS(
        text = AnnotatedString(text),
        letterSpacing = letterSpacing,
        color = color,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier
    )
}

@Composable
fun BodyS(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    textAlign: TextAlign = TextAlign.Start,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    letterSpacing: TextUnit = TextUnit.Unspecified,
) {
    Text(
        text = text,
        style = AppTextStyles.BodyS.merge(
            letterSpacing = letterSpacing,
            color = color,
            textAlign = textAlign,
        ),
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier
    )
}

@Composable
fun BodySSB(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    style: TextStyle = AppTextStyles.BodySSB,
) {
    BodySSB(
        text = AnnotatedString(text),
        style = style,
        color = color,
        modifier = modifier,
        maxLines = maxLines,
        overflow = overflow,
    )
}

@Composable
fun BodySSB(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    style: TextStyle = AppTextStyles.BodySSB,
) {
    Text(
        text = text,
        style = style.merge(
            color = color,
        ),
        modifier = modifier,
        maxLines = maxLines,
        overflow = overflow,
    )
}

@Composable
fun BodySB(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    textAlign: TextAlign = TextAlign.Start,
) {
    Text(
        text = text,
        style = AppTextStyles.BodySB.merge(
            color = color,
            textAlign = textAlign,
        ),
        modifier = modifier
    )
}

@Composable
fun Text13Up(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    textAlign: TextAlign = TextAlign.Start,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = if (maxLines == 1) TextOverflow.Ellipsis else TextOverflow.Clip,
) {
    Text(
        text = text.uppercase(),
        style = AppTextStyles.CaptionM.merge(
            color = color,
            textAlign = textAlign,
        ),
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier
    )
}

@Composable
fun Caption(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    textAlign: TextAlign = TextAlign.Start,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = if (maxLines == 1) TextOverflow.Ellipsis else TextOverflow.Clip,
) {
    Text(
        text = text,
        style = AppTextStyles.Caption.merge(
            color = color,
            textAlign = textAlign,
        ),
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier
    )
}

@Composable
fun CaptionM(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    textAlign: TextAlign = TextAlign.Start,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = if (maxLines == 1) TextOverflow.Ellipsis else TextOverflow.Clip,
) {
    Text(
        text = text,
        style = AppTextStyles.CaptionM.merge(
            color = color,
            textAlign = textAlign,
        ),
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier
    )
}

@Composable
fun CaptionB(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    textAlign: TextAlign = TextAlign.Start,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = if (maxLines == 1) TextOverflow.Ellipsis else TextOverflow.Clip,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
    style: TextStyle = AppTextStyles.CaptionB,
) {
    CaptionB(
        text = AnnotatedString(text),
        style = style,
        modifier = modifier,
        color = color,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = overflow,
        letterSpacing = letterSpacing,
        lineHeight = lineHeight,
    )
}

@Composable
fun CaptionB(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    textAlign: TextAlign = TextAlign.Start,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = if (maxLines == 1) TextOverflow.Ellipsis else TextOverflow.Clip,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
    style: TextStyle = AppTextStyles.CaptionB,
) {
    Text(
        text = text,
        style = style.merge(
            letterSpacing = letterSpacing,
            lineHeight = lineHeight,
            color = color,
            textAlign = textAlign,
        ),
        modifier = modifier,
        maxLines = maxLines,
        overflow = overflow,
    )
}

@Composable
fun Caption13Up(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    textAlign: TextAlign = TextAlign.Start,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    style: TextStyle = AppTextStyles.CaptionM,
) {
    Text(
        text = text.uppercase(),
        style = style.merge(
            color = color,
            textAlign = textAlign,
        ),
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier
    )
}

@Composable
fun Footnote(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Colors.White32,
    textAlign: TextAlign = TextAlign.Start,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = if (maxLines == 1) TextOverflow.Ellipsis else TextOverflow.Clip,
) {
    Text(
        text = text,
        style = AppTextStyles.FootnoteM.merge(
            color = color,
            textAlign = textAlign,
        ),
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier
    )
}

@file:Suppress("TooManyFunctions")

package to.bitkit.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
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
) {
    Text(
        text = text.uppercase(),
        style = AppTextStyles.Display.merge(
            fontWeight = fontWeight,
            fontSize = fontSize,
            color = color,
        ),
        modifier = modifier,
    )
}

@Composable
fun Display(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text = text.toUpperCase(),
        style = AppTextStyles.Display.merge(
            color = color,
        ),
        modifier = modifier,
    )
}

@Composable
fun Headline(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text = text.toUpperCase(),
        style = AppTextStyles.Headline.merge(
            color = color,
        ),
        modifier = modifier,
    )
}

@Composable
fun Headline20(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text = text.toUpperCase(),
        style = AppTextStyles.Headline.merge(
            fontSize = 20.sp,
            lineHeight = 20.sp,
            letterSpacing = (-.5).sp,
            color = color,
        ),
        modifier = modifier,
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
) {
    Text(
        text = text,
        maxLines = maxLines,
        overflow = overflow,
        style = AppTextStyles.Title.merge(
            color = color,
            textAlign = textAlign,
        ),
        modifier = modifier,
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
) {
    Text(
        text = text,
        style = AppTextStyles.Subtitle.merge(
            color = color,
            textAlign = textAlign,
        ),
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier,
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
) {
    BodyM(
        text = AnnotatedString(text),
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
) {
    Text(
        text = text,
        style = AppTextStyles.BodyM.merge(
            color = color,
            textAlign = textAlign,
        ),
        maxLines = maxLines,
        minLines = minLines,
        overflow = overflow,
        modifier = modifier,
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
) {
    BodyMSB(
        text = AnnotatedString(text),
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
) {
    Text(
        text = text,
        style = AppTextStyles.BodyMSB.merge(
            color = color,
            textAlign = textAlign,
        ),
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier,
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
        style = AppTextStyles.BodyS.merge(
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
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    BodySSB(
        text = AnnotatedString(text),
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
) {
    Text(
        text = text,
        style = AppTextStyles.BodySSB.merge(
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
        modifier = modifier,
    )
}

@Composable
fun Text13Up(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    textAlign: TextAlign = TextAlign.Start,
) {
    Text(
        text = text.uppercase(),
        style = AppTextStyles.CaptionM.merge(
            color = color,
            textAlign = textAlign,
        ),
        modifier = modifier,
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
        modifier = modifier,
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
) {
    CaptionB(
        text = AnnotatedString(text),
        modifier = modifier,
        color = color,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = overflow,
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
) {
    Text(
        text = text,
        style = AppTextStyles.CaptionB.merge(
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
) {
    Text(
        text = text.uppercase(),
        style = AppTextStyles.CaptionM.merge(
            color = color,
            textAlign = textAlign,
        ),
        modifier = modifier,
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
        modifier = modifier,
    )
}

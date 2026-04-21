package to.bitkit.appwidget.ui.components

import androidx.compose.runtime.Composable
import androidx.glance.GlanceModifier
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import to.bitkit.appwidget.ui.theme.GlanceTextStyles

@Composable
fun Subtitle(
    text: String,
    modifier: GlanceModifier = GlanceModifier,
    color: ColorProvider? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(text = text, modifier = modifier, style = GlanceTextStyles.subtitle.withColor(color), maxLines = maxLines)
}

@Composable
fun BodyMSB(
    text: String,
    modifier: GlanceModifier = GlanceModifier,
    color: ColorProvider? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(text = text, modifier = modifier, style = GlanceTextStyles.bodyMSB.withColor(color), maxLines = maxLines)
}

@Composable
fun BodySSB(
    text: String,
    modifier: GlanceModifier = GlanceModifier,
    color: ColorProvider? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(text = text, modifier = modifier, style = GlanceTextStyles.bodySSB.withColor(color), maxLines = maxLines)
}

@Composable
fun BodySB(
    text: String,
    modifier: GlanceModifier = GlanceModifier,
    color: ColorProvider? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(text = text, modifier = modifier, style = GlanceTextStyles.bodySB.withColor(color), maxLines = maxLines)
}

@Composable
fun CaptionB(
    text: String,
    modifier: GlanceModifier = GlanceModifier,
    color: ColorProvider? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(text = text, modifier = modifier, style = GlanceTextStyles.captionB.withColor(color), maxLines = maxLines)
}

@Composable
fun FootnoteM(
    text: String,
    modifier: GlanceModifier = GlanceModifier,
    color: ColorProvider? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(text = text, modifier = modifier, style = GlanceTextStyles.footnoteM.withColor(color), maxLines = maxLines)
}

private fun TextStyle.withColor(color: ColorProvider?): TextStyle =
    if (color != null) copy(color = color) else this

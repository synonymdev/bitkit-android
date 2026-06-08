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
    Text(text = text, style = GlanceTextStyles.subtitle.withColor(color), maxLines = maxLines, modifier = modifier)
}

@Composable
fun BodyM(
    text: String,
    modifier: GlanceModifier = GlanceModifier,
    color: ColorProvider? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(text = text, style = GlanceTextStyles.bodyM.withColor(color), maxLines = maxLines, modifier = modifier)
}

@Composable
fun BodyMSB(
    text: String,
    modifier: GlanceModifier = GlanceModifier,
    color: ColorProvider? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(text = text, style = GlanceTextStyles.bodyMSB.withColor(color), maxLines = maxLines, modifier = modifier)
}

@Composable
fun BodySSB(
    text: String,
    modifier: GlanceModifier = GlanceModifier,
    color: ColorProvider? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(text = text, style = GlanceTextStyles.bodySSB.withColor(color), maxLines = maxLines, modifier = modifier)
}

@Composable
fun BodySB(
    text: String,
    modifier: GlanceModifier = GlanceModifier,
    color: ColorProvider? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(text = text, style = GlanceTextStyles.bodySB.withColor(color), maxLines = maxLines, modifier = modifier)
}

@Composable
fun CaptionB(
    text: String,
    modifier: GlanceModifier = GlanceModifier,
    color: ColorProvider? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(text = text, style = GlanceTextStyles.captionB.withColor(color), maxLines = maxLines, modifier = modifier)
}

@Composable
fun FootnoteM(
    text: String,
    modifier: GlanceModifier = GlanceModifier,
    color: ColorProvider? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(text = text, style = GlanceTextStyles.footnoteM.withColor(color), maxLines = maxLines, modifier = modifier)
}

private fun TextStyle.withColor(color: ColorProvider?): TextStyle =
    if (color != null) copy(color = color) else this

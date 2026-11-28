package to.bitkit.wear.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import to.bitkit.wear.ui.screens.home.DisplayAmount
import to.bitkit.wear.ui.theme.AppTextStyles
import to.bitkit.wear.ui.theme.Colors

/** The space between the large amount and its symbol, as on the phone. */
private val SYMBOL_SPACING = 8.dp

/** The large amount's size on a [SMALL_SCREEN_DP] screen. */
private const val LARGE_SP_SMALL_SCREEN = 26f

/** The large amount's size on a [LARGE_SCREEN_DP] screen; sizes in between are interpolated. */
private const val LARGE_SP_LARGE_SCREEN = 30f

/** The width of the small round screen. */
private const val SMALL_SCREEN_DP = 192f

/** The width of the large round screen. */
private const val LARGE_SCREEN_DP = 227f

/** The smallest size of the small row: Wear's minimum for essential text (WO-V14). */
private const val MIN_SMALL_ROW_SP = 12f

/** The gap between the small and the large row on a [SMALL_SCREEN_DP] screen; the phone's 12dp is too wide here. */
private const val ROW_GAP_DP_SMALL_SCREEN = 3f

/** The gap between the small and the large row on a [LARGE_SCREEN_DP] screen; sizes in between are interpolated. */
private const val ROW_GAP_DP_LARGE_SCREEN = 4f

@Composable
private fun screenProgress(): Float {
    val widthDp = LocalConfiguration.current.screenWidthDp.toFloat()
    return ((widthDp - SMALL_SCREEN_DP) / (LARGE_SCREEN_DP - SMALL_SCREEN_DP)).coerceIn(0f, 1f)
}

@Composable
private fun amountScale(): Float {
    val largeSp = LARGE_SP_SMALL_SCREEN + (LARGE_SP_LARGE_SCREEN - LARGE_SP_SMALL_SCREEN) * screenProgress()
    return largeSp / AppTextStyles.Display.fontSize.value
}

/** The gap between a [SmallAmountRow] and the [LargeAmountRow] below it, for this screen's size. */
@Composable
fun amountRowGap(): Dp =
    (ROW_GAP_DP_SMALL_SCREEN + (ROW_GAP_DP_LARGE_SCREEN - ROW_GAP_DP_SMALL_SCREEN) * screenProgress()).dp

/**
 * An amount in the phone's large balance style (`Display`), with its symbol drawn apart, scaled for the round screen
 * and shrunk further only when it would not fit [maxWidth].
 */
@Composable
fun LargeAmountRow(
    amount: DisplayAmount,
    maxWidth: Dp,
    amountTag: String,
    symbolTag: String,
    modifier: Modifier = Modifier,
) {
    val style = rememberFittedDisplayStyle(amount, maxWidth)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        if (!amount.isSymbolSuffix) {
            LargeSymbol(
                symbol = amount.symbol,
                style = style,
                modifier = Modifier
                    .padding(end = SYMBOL_SPACING)
                    .testTag(symbolTag)
            )
        }
        Text(
            text = amount.text.uppercase(),
            style = style,
            maxLines = 1,
            modifier = Modifier.testTag(amountTag)
        )
        if (amount.isSymbolSuffix) {
            LargeSymbol(
                symbol = amount.symbol,
                style = style,
                modifier = Modifier
                    .padding(start = SYMBOL_SPACING)
                    .testTag(symbolTag)
            )
        }
    }
}

@Composable
private fun LargeSymbol(
    symbol: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    Text(
        text = symbol.uppercase(),
        style = style.merge(fontWeight = FontWeight.ExtraBold, color = Colors.White64),
        maxLines = 1,
        modifier = modifier
    )
}

@Composable
private fun rememberFittedDisplayStyle(amount: DisplayAmount, maxWidth: Dp): TextStyle {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val scale = amountScale()
    val base = AppTextStyles.Display.merge(
        color = Colors.White,
        fontSize = AppTextStyles.Display.fontSize * scale,
        lineHeight = AppTextStyles.Display.lineHeight * scale,
        letterSpacing = AppTextStyles.Display.letterSpacing * scale,
    )
    return remember(amount, maxWidth, density, base) {
        val symbolStyle = base.merge(fontWeight = FontWeight.ExtraBold)
        val width = measurer.measure(amount.text.uppercase(), base, maxLines = 1).size.width +
            measurer.measure(amount.symbol.uppercase(), symbolStyle, maxLines = 1).size.width +
            with(density) { SYMBOL_SPACING.toPx() }
        val fit = with(density) { maxWidth.toPx() } / width
        if (fit >= 1f) {
            base
        } else {
            base.merge(
                fontSize = base.fontSize * fit,
                lineHeight = base.lineHeight * fit,
                letterSpacing = base.letterSpacing * fit,
            )
        }
    }
}

/** An amount in the phone's small balance row style (`CaptionM`), uppercased and dimmed. */
@Composable
fun SmallAmountRow(
    amount: DisplayAmount,
    amountTag: String,
    modifier: Modifier = Modifier,
) {
    val scale = amountScale()
    val fontSize = (AppTextStyles.CaptionM.fontSize.value * scale).coerceAtLeast(MIN_SMALL_ROW_SP)
    val style = AppTextStyles.CaptionM.merge(
        color = Colors.White64,
        fontSize = fontSize.sp,
        lineHeight = AppTextStyles.CaptionM.lineHeight * (fontSize / AppTextStyles.CaptionM.fontSize.value),
        letterSpacing = AppTextStyles.CaptionM.letterSpacing * scale,
    )
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
    ) {
        if (!amount.isSymbolSuffix) Text(text = amount.symbol.uppercase(), style = style)
        Text(
            text = amount.text.uppercase(),
            style = style,
            modifier = Modifier.testTag(amountTag)
        )
        if (amount.isSymbolSuffix) Text(text = amount.symbol.uppercase(), style = style)
    }
}

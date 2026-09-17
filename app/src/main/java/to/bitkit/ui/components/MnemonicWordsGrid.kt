package to.bitkit.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.EaseOutQuart
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import to.bitkit.ui.theme.AppTextStyles
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import kotlin.math.roundToInt

/** Font size a recovery phrase word starts from before shrinking to fit its row. */
private val WORD_MAX_FONT_SIZE = AppTextStyles.BodyMSB.fontSize

/** Smallest font size a recovery phrase word shrinks to. */
private val WORD_MIN_FONT_SIZE = 12.sp

/** Step used when shrinking recovery phrase words to fit. */
private val WORD_FONT_SIZE_STEP = 0.5.sp

/** Horizontal gap between the two word columns. */
private val COLUMN_GAP = 32.dp

/** Horizontal gap between a word number and the word. */
private val LABEL_GAP = 8.dp

@Composable
fun MnemonicWordsGrid(
    actualWords: ImmutableList<String>,
    showMnemonic: Boolean,
    modifier: Modifier = Modifier,
    blurDurationMs: Int = 800,
    crossfadeDurationMs: Int = 600,
) {
    val placeholderWords = remember(actualWords) { List(actualWords.size) { "secret" } }
    val blurRadius by animateFloatAsState(
        targetValue = if (showMnemonic) 0f else 10f,
        animationSpec = tween(blurDurationMs, easing = EaseOutQuart),
        label = "blurRadius"
    )
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .blur(radius = blurRadius.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
            .alpha(alpha = 1f - blurRadius * 0.075f)
    ) {
        val wordFit = rememberWordFontFit(
            actualWords = actualWords,
            placeholderWords = placeholderWords,
            constraints = constraints,
        )
        Crossfade(
            targetState = showMnemonic,
            animationSpec = tween(crossfadeDurationMs),
            label = "crossfade",
        ) { isRevealed ->
            val wordsShown = if (isRevealed && actualWords.isNotEmpty()) actualWords else placeholderWords
            val half = wordsShown.size / 2

            Row(
                horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    wordsShown.take(half).forEachIndexed { index, word ->
                        WordItem(
                            number = index + 1,
                            word = word,
                            fit = wordFit,
                        )
                    }
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    wordsShown.drop(half).forEachIndexed { index, word ->
                        WordItem(
                            number = half + index + 1,
                            word = word,
                            fit = wordFit,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberWordFontFit(
    actualWords: List<String>,
    placeholderWords: List<String>,
    constraints: Constraints,
): MnemonicFontFit {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(actualWords, placeholderWords, constraints.maxWidth, constraints.hasBoundedWidth, density) {
        if (!constraints.hasBoundedWidth) return@remember MnemonicFontFit(WORD_MAX_FONT_SIZE, fits = true)
        val columnGapPx = with(density) { COLUMN_GAP.roundToPx() }
        val labelGapPx = with(density) { LABEL_GAP.roundToPx() }
        val budgetPx: (Int) -> Int = { number ->
            val labelWidthPx = textMeasurer.measure(
                text = "$number.",
                style = AppTextStyles.BodyMSB,
                maxLines = 1,
                softWrap = false,
                density = density,
            ).size.width
            mnemonicWordBudgetPx(constraints.maxWidth, columnGapPx, labelWidthPx, labelGapPx)
        }
        val measurePx: (String, TextUnit) -> Int = { word, fontSize ->
            textMeasurer.measure(
                text = word,
                style = AppTextStyles.BodyMSB.copy(fontSize = fontSize),
                maxLines = 1,
                softWrap = false,
                density = density,
            ).size.width
        }
        val fits = listOf(actualWords, placeholderWords).map { fitMnemonicFontSize(it, budgetPx, measurePx) }
        MnemonicFontFit(
            fontSize = fits.minBy { it.fontSize.value }.fontSize,
            fits = fits.all { it.fits },
        )
    }
}

/**
 * Font size shared by every word in the grid. When [fits] is false a word is still too wide at the
 * minimum size, so words wrap instead of running past their column.
 */
internal data class MnemonicFontFit(
    val fontSize: TextUnit,
    val fits: Boolean,
)

/**
 * Returns the largest font size, stepping down from 17sp to 12sp in 0.5sp steps, at which every word
 * fits its row, so the whole grid shares one size. Falls back to 12sp with `fits = false` when a word
 * does not fit even at 12sp.
 *
 * [wordBudgetPx] receives the 1-based word number and [measureWordPx] the word and candidate size.
 */
internal fun fitMnemonicFontSize(
    words: List<String>,
    wordBudgetPx: (Int) -> Int,
    measureWordPx: (String, TextUnit) -> Int,
): MnemonicFontFit {
    val budgets = words.indices.map { wordBudgetPx(it + 1) }
    val steps = ((WORD_MAX_FONT_SIZE.value - WORD_MIN_FONT_SIZE.value) / WORD_FONT_SIZE_STEP.value).roundToInt()
    for (index in 0..steps) {
        val fontSize = (WORD_MAX_FONT_SIZE.value - index * WORD_FONT_SIZE_STEP.value).sp
        val allFit = words.indices.all { measureWordPx(words[it], fontSize) <= budgets[it] }
        if (allFit) return MnemonicFontFit(fontSize, fits = true)
    }
    return MnemonicFontFit(WORD_MIN_FONT_SIZE, fits = false)
}

/** Returns the width left for a word in one of the two grid columns after its number label. */
internal fun mnemonicWordBudgetPx(
    gridWidthPx: Int,
    columnGapPx: Int,
    labelWidthPx: Int,
    labelGapPx: Int,
): Int = ((gridWidthPx - columnGapPx) / 2 - labelWidthPx - labelGapPx).coerceAtLeast(0)

@Composable
private fun WordItem(
    number: Int,
    word: String,
    fit: MnemonicFontFit,
) {
    Row {
        BodyMSB(
            text = "$number.",
            color = Colors.White64,
            maxLines = 1,
            modifier = Modifier.alignByBaseline()
        )
        HorizontalSpacer(LABEL_GAP)
        Text(
            text = word,
            style = AppTextStyles.BodyMSB.copy(color = Colors.White, fontSize = fit.fontSize),
            maxLines = if (fit.fits) 1 else Int.MAX_VALUE,
            softWrap = !fit.fits,
            overflow = TextOverflow.Visible,
            modifier = Modifier
                .weight(1f)
                .alignByBaseline()
        )
    }
}

private val previewWords = List(8) { "word${it + 1}" }.toImmutableList()

private val previewLongWords = listOf("abstract", "research", "awesome", "category")

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        MnemonicWordsGrid(
            actualWords = previewWords,
            showMnemonic = true,
        )
    }
}

@Preview
@Composable
private fun PreviewHidden() {
    AppThemeSurface {
        MnemonicWordsGrid(
            actualWords = previewWords,
            showMnemonic = false,
        )
    }
}

@Preview(widthDp = 375)
@Composable
private fun PreviewLongWords12() {
    AppThemeSurface {
        MnemonicWordsGrid(
            actualWords = List(12) { previewLongWords[it % previewLongWords.size] }.toImmutableList(),
            showMnemonic = true,
            modifier = Modifier.padding(horizontal = 64.dp)
        )
    }
}

@Preview(widthDp = 375)
@Composable
private fun PreviewLongWords24() {
    AppThemeSurface {
        MnemonicWordsGrid(
            actualWords = List(24) { previewLongWords[it % previewLongWords.size] }.toImmutableList(),
            showMnemonic = true,
            modifier = Modifier.padding(horizontal = 64.dp)
        )
    }
}

@Preview(widthDp = 375, fontScale = 1.3f)
@Composable
private fun PreviewLongWords12FontScale() {
    AppThemeSurface {
        MnemonicWordsGrid(
            actualWords = List(12) { previewLongWords[it % previewLongWords.size] }.toImmutableList(),
            showMnemonic = true,
            modifier = Modifier.padding(horizontal = 64.dp)
        )
    }
}

@Preview(widthDp = 375, fontScale = 1.3f)
@Composable
private fun PreviewLongWords24FontScale() {
    AppThemeSurface {
        MnemonicWordsGrid(
            actualWords = List(24) { previewLongWords[it % previewLongWords.size] }.toImmutableList(),
            showMnemonic = true,
            modifier = Modifier.padding(horizontal = 64.dp)
        )
    }
}

@Preview(widthDp = 360, fontScale = 2f)
@Composable
private fun PreviewLongWords12FontScaleMax() {
    AppThemeSurface {
        MnemonicWordsGrid(
            actualWords = List(12) { previewLongWords[it % previewLongWords.size] }.toImmutableList(),
            showMnemonic = true,
            modifier = Modifier.padding(horizontal = 64.dp)
        )
    }
}

package to.bitkit.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.EaseOutQuart
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

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
    Box(
        modifier = modifier
            .fillMaxWidth()
            .blur(radius = blurRadius.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
            .alpha(alpha = 1f - blurRadius * 0.075f)
    ) {
        Crossfade(
            targetState = showMnemonic,
            animationSpec = tween(crossfadeDurationMs),
            label = "crossfade",
        ) { isRevealed ->
            val wordsShown = if (isRevealed && actualWords.isNotEmpty()) actualWords else placeholderWords
            val half = wordsShown.size / 2

            Row(
                horizontalArrangement = Arrangement.spacedBy(32.dp),
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
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WordItem(
    number: Int,
    word: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BodyMSB(text = "$number.", color = Colors.White64)
        Spacer(modifier = Modifier.width(8.dp))
        BodyMSB(text = word, color = Colors.White)
    }
}

private val previewWords = List(8) { "word${it + 1}" }.toImmutableList()

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

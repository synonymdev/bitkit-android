package to.bitkit.wear.ui.screens.restore

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import androidx.wear.compose.material3.TextButtonDefaults
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import kotlinx.collections.immutable.ImmutableList
import to.bitkit.wear.R
import to.bitkit.wear.ui.components.CenteredScreen
import to.bitkit.wear.ui.components.WearPreview

/** Asks how many words the recovery phrase has: one tap on a length starts typing. */
@Composable
fun RestoreStartScreen(
    viewModel: RestoreViewModel,
    modifier: Modifier = Modifier,
) {
    Content(
        wordCounts = RestoreViewModel.WORD_COUNTS,
        onWordCountSelect = viewModel::onWordCountSelect,
        modifier = modifier,
    )
}

@Composable
private fun Content(
    wordCounts: ImmutableList<Int>,
    onWordCountSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    CenteredScreen(modifier = modifier) {
        Text(
            text = stringResource(R.string.restore__title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("RestoreTitle")
        )
        ButtonGroup {
            wordCounts.forEach { count ->
                val interactionSource = remember { MutableInteractionSource() }
                val description = pluralStringResource(R.plurals.restore__length_words, count, count)
                TextButton(
                    onClick = { onWordCountSelect(count) },
                    shapes = TextButtonDefaults.animatedShapes(),
                    colors = TextButtonDefaults.filledTonalTextButtonColors(),
                    interactionSource = interactionSource,
                    modifier = Modifier
                        .size(TextButtonDefaults.LargeButtonSize)
                        .animateWidth(interactionSource)
                        .semantics { contentDescription = description }
                        .testTag("RestoreWords$count")
                ) {
                    Text(text = "$count", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

@WearPreviewDevices
@Composable
private fun Preview() {
    WearPreview {
        Content(wordCounts = RestoreViewModel.WORD_COUNTS, onWordCountSelect = {})
    }
}

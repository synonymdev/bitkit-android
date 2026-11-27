package to.bitkit.wear.ui.screens.restore

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import kotlinx.collections.immutable.ImmutableList
import to.bitkit.wear.R
import to.bitkit.wear.ui.components.ListScreen
import to.bitkit.wear.ui.components.WearPreview

/** Asks how many words the recovery phrase has. */
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
    ListScreen(modifier = modifier) { spec ->
        item {
            ListHeader(
                transformation = SurfaceTransformation(spec),
                modifier = Modifier.transformedHeight(this, spec)
            ) {
                Text(
                    text = stringResource(R.string.restore__title),
                    modifier = Modifier.testTag("RestoreTitle")
                )
            }
        }
        items(wordCounts.size) { index ->
            val count = wordCounts[index]
            Button(
                onClick = { onWordCountSelect(count) },
                label = { Text(pluralStringResource(R.plurals.restore__length_words, count, count)) },
                transformation = SurfaceTransformation(spec),
                modifier = Modifier
                    .fillMaxWidth()
                    .transformedHeight(this, spec)
                    .testTag("RestoreWords$count")
            )
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

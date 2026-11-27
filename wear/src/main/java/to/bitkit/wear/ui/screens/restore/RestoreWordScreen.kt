package to.bitkit.wear.ui.screens.restore

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import kotlinx.collections.immutable.persistentListOf
import to.bitkit.wear.R
import to.bitkit.wear.security.Bip39
import to.bitkit.wear.ui.components.ListScreen
import to.bitkit.wear.ui.components.SecretTextField
import to.bitkit.wear.ui.components.WearPreview

/**
 * Types the word at [index], counted from 0, of the recovery phrase. It shows this one word only: the phrase is
 * never on screen as a whole.
 */
@Composable
fun RestoreWordScreen(
    index: Int,
    viewModel: RestoreViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val entry = uiState.entries.getOrNull(index) ?: return
    Content(
        index = index,
        wordCount = uiState.entries.size,
        entry = entry,
        onWordChange = { viewModel.onWordChange(index, it) },
        onSuggestionClick = { viewModel.onSuggestionSelect(index, it) },
        onNextClick = { viewModel.onWordConfirm(index) },
        modifier = modifier,
    )
}

@Composable
private fun Content(
    index: Int,
    wordCount: Int,
    entry: WordEntry,
    onWordChange: (String) -> Unit,
    onSuggestionClick: (String) -> Unit,
    onNextClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val inputFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    // Typing shows at once from this copy; the view model's sanitized state follows a frame later.
    var text by remember(index) { mutableStateOf(entry.input) }
    LaunchedEffect(index) {
        if (entry.input.isEmpty()) inputFocus.requestFocus() else listFocus.requestFocus()
    }
    ListScreen(
        rotaryFocusRequester = listFocus,
        edgeButton = {
            EdgeButton(
                onClick = onNextClick,
                enabled = entry.isWord,
                buttonSize = EdgeButtonSize.Small,
                modifier = Modifier.testTag("RestoreNext")
            ) {
                Text(stringResource(R.string.common__next))
            }
        },
        modifier = modifier,
    ) { spec ->
        item {
            ListHeader(
                transformation = SurfaceTransformation(spec),
                modifier = Modifier.transformedHeight(this, spec)
            ) {
                Text(
                    text = stringResource(R.string.restore__word_progress, index + 1, wordCount),
                    modifier = Modifier.testTag("RestoreProgress")
                )
            }
        }
        item {
            SecretTextField(
                value = text,
                onValueChange = {
                    text = Bip39.sanitize(it)
                    onWordChange(text)
                },
                placeholder = stringResource(R.string.restore__word_placeholder, index + 1),
                onDone = {
                    keyboard?.hide()
                    listFocus.requestFocus()
                    onNextClick()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .transformedHeight(this, spec)
                    .focusRequester(inputFocus)
                    .testTag("RestoreWordInput")
            )
        }
        if (entry.input.length >= Bip39.MIN_PREFIX_LENGTH && entry.suggestions.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.restore__word_no_match),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, spec)
                        .testTag("RestoreNoMatch")
                )
            }
        }
        items(entry.suggestions.size, key = { entry.suggestions[it] }) { position ->
            val word = entry.suggestions[position]
            FilledTonalButton(
                onClick = { onSuggestionClick(word) },
                label = {
                    Text(text = word, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                },
                transformation = SurfaceTransformation(spec),
                modifier = Modifier
                    .fillMaxWidth()
                    .transformedHeight(this, spec)
                    .testTag("RestoreSuggestion-$word")
            )
        }
    }
}

@WearPreviewDevices
@Composable
private fun Preview() {
    WearPreview {
        Content(
            index = 2,
            wordCount = 12,
            entry = WordEntry(input = "ab", suggestions = persistentListOf("abandon", "ability", "able", "about")),
            onWordChange = {},
            onSuggestionClick = {},
            onNextClick = {},
        )
    }
}

package to.bitkit.ui.screens.wallets.send

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import to.bitkit.R
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.TagButton
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.theme.TRANSITION_SCREEN_MS
import to.bitkit.viewmodels.AddTagUiState
import to.bitkit.viewmodels.TagsViewModel

@Composable
fun AddTagScreen(
    onBack: () -> Unit,
    onTagSelected: (String) -> Unit,
    tqgInputTestTag: String,
    addButtonTestTag: String? = null,
    viewModel: TagsViewModel = hiltViewModel(),
) {
    val uiState: AddTagUiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.loadTagSuggestions()
    }

    AddTagContent(
        uiState = uiState,
        onTagSelected = onTagSelected,
        onTagConfirmed = { tag -> onTagSelected(tag) },
        onInputUpdated = { newText -> viewModel.onInputUpdated(newText) },
        onBack = onBack,
        tagInputTestTag = tqgInputTestTag,
        addButtonTestTag = addButtonTestTag,
    )
}

@Composable
fun AddTagContent(
    uiState: AddTagUiState,
    onTagSelected: (String) -> Unit,
    onTagConfirmed: (String) -> Unit,
    onInputUpdated: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    focusOnShow: Boolean = false,
    tagInputTestTag: String? = null,
    addButtonTestTag: String? = null,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusOnShow) {
        if (focusOnShow) {
            delay(TRANSITION_SCREEN_MS)
            focusRequester.requestFocus()
        }
    }
    Column(
        modifier = modifier
            .gradientBackground()
            .navigationBarsPadding()
    ) {
        SheetTopBar(stringResource(R.string.wallet__tags_add), onBack = onBack)
        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            VerticalSpacer(16.dp)
            if (uiState.tagsSuggestions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Caption13Up(text = stringResource(R.string.wallet__tags_previously), color = Colors.White64)
                Spacer(modifier = Modifier.height(16.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    uiState.tagsSuggestions.map { tagText ->
                        TagButton(
                            tagText,
                            onClick = { onTagSelected(tagText) },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Caption13Up(text = stringResource(R.string.wallet__tags_new), color = Colors.White64)
            Spacer(modifier = Modifier.height(16.dp))
            TextInput(
                placeholder = stringResource(R.string.wallet__tags_new_enter),
                value = uiState.tagInput,
                onValueChange = onInputUpdated,
                maxLines = 1,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = {
                    onTagConfirmed(uiState.tagInput)
                }),
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .fillMaxWidth()
                    .then(tagInputTestTag?.let { Modifier.testTag(it) } ?: Modifier)
            )

            Spacer(modifier = Modifier.height(16.dp))
            Spacer(modifier = Modifier.weight(1f))
            PrimaryButton(
                text = stringResource(R.string.wallet__tags_add_button),
                onClick = { onTagConfirmed(uiState.tagInput) },
                enabled = uiState.tagInput.isNotBlank(),
                modifier = Modifier
                    .then(addButtonTestTag?.let { Modifier.testTag(it) } ?: Modifier)
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            AddTagContent(
                uiState = AddTagUiState(
                    tagsSuggestions = listOf("Lunch", "Mom", "Dad", "Dinner", "Tip", "Gift")
                ),
                onTagSelected = {},
                onInputUpdated = {},
                onTagConfirmed = {},
                onBack = {},
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewEmpty() {
    AppThemeSurface {
        BottomSheetPreview {
            AddTagContent(
                uiState = AddTagUiState(tagInput = "Lunch"),
                onTagSelected = {},
                onInputUpdated = {},
                onTagConfirmed = {},
                onBack = {},
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}

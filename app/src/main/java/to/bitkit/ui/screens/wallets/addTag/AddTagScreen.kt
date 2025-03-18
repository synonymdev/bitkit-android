package to.bitkit.ui.screens.wallets.addTag

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.TagButton
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.theme.AppTextFieldDefaults
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.TagsViewmodel


@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddTagScreen(
    viewModel: TagsViewmodel,
    onBack: () -> Unit,
    onTagSelected: (String) -> Unit,
    ) {
    val uiState: AddTagUIState by viewModel.uiState.collectAsState()

    AddTagScreen(
        uiState = uiState,
        onTagSelected = onTagSelected,
        onTagConfirmed = { tag -> viewModel.addTag(tag)},
        onInputUpdated = { newText -> viewModel.onInputUpdated(newText) },
        onBack = onBack
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddTagScreen(
    uiState: AddTagUIState,
    onTagSelected: (String) -> Unit,
    onTagConfirmed: (String) -> Unit,
    onInputUpdated: (String) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {

        SheetTopBar(stringResource(R.string.wallet__tags_add)) {
            onBack()
        }

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
        ) {
            if (uiState.tagsSuggestions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Caption13Up(text = stringResource(R.string.wallet__tags_previously), color = Colors.White64)
                Spacer(modifier = Modifier.height(16.dp))
            }
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
                        isSelected = false,
                        onClick = { onTagSelected(tagText) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Caption13Up(text = stringResource(R.string.wallet__tags_new), color = Colors.White64)
            Spacer(modifier = Modifier.height(16.dp))
            TextField(
                placeholder = { Text(stringResource(R.string.wallet__tags_new_enter)) },
                value = uiState.tagInput,
                onValueChange = onInputUpdated,
                maxLines = 1,
                singleLine = true,
                colors = AppTextFieldDefaults.noIndicatorColors,
                shape = MaterialTheme.shapes.small,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = {
                    onTagConfirmed(uiState.tagInput)
                }),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Preview(showSystemUi = true, showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        AddTagScreen(
            uiState = AddTagUIState(
                tagsSuggestions = listOf("Lunch", "Mom", "Dad", "Dinner", "Tip", "Gift")
            ),
            onTagSelected = {},
            onInputUpdated = {},
            onTagConfirmed = {}
        ) { }
    }
}

@Preview(showSystemUi = true, showBackground = true, name = "No tags")
@Composable
private fun Preview2() {
    AppThemeSurface {
        AddTagScreen(
            uiState = AddTagUIState(),
            onTagSelected = {},
            onInputUpdated = {},
            onTagConfirmed = {}) { }
    }
}

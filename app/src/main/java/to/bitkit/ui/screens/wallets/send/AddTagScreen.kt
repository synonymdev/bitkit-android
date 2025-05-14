package to.bitkit.ui.screens.wallets.send

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import to.bitkit.R
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.TagButton
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.theme.AppShapes
import to.bitkit.ui.theme.AppTextFieldDefaults
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.AddTagUiState
import to.bitkit.viewmodels.TagsViewModel


@Composable
fun AddTagScreen(
    viewModel: TagsViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onTagSelected: (String) -> Unit,
) {
    val uiState: AddTagUiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadTagSuggestions()
    }

    AddTagContent(
        uiState = uiState,
        onTagSelected = onTagSelected,
        onTagConfirmed = { tag ->
            onTagSelected(tag)
        },
        onInputUpdated = { newText -> viewModel.onInputUpdated(newText) },
        onBack = onBack,
        modifier = Modifier.fillMaxSize()
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddTagContent(
    uiState: AddTagUiState,
    onTagSelected: (String) -> Unit,
    onTagConfirmed: (String) -> Unit,
    onInputUpdated: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.navigationBarsPadding()
    ) {

        SheetTopBar(stringResource(R.string.wallet__tags_add)) {
            onBack()
        }
        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
        ) {
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
                            isSelected = false,
                            onClick = { onTagSelected(tagText) },
                        )
                    }
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
                colors = AppTextFieldDefaults.semiTransparent,
                shape = AppShapes.small,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = {
                    onTagConfirmed(uiState.tagInput)
                }),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
            Spacer(modifier = Modifier.weight(1f))
            PrimaryButton(
                text = stringResource(R.string.wallet__tags_add_button),
                onClick = { onTagConfirmed(uiState.tagInput) },
                enabled = uiState.tagInput.isNotBlank(),
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Preview(showSystemUi = true, showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        AddTagContent(
            uiState = AddTagUiState(
                tagsSuggestions = listOf("Lunch", "Mom", "Dad", "Dinner", "Tip", "Gift")
            ),
            onTagSelected = {},
            onInputUpdated = {},
            onTagConfirmed = {},
            onBack = {}
        )
    }
}

@Preview(showSystemUi = true, showBackground = true, name = "No tags")
@Composable
private fun Preview2() {
    AppThemeSurface {
        AddTagContent(
            uiState = AddTagUiState(tagInput = "Lunch"),
            onTagSelected = {},
            onInputUpdated = {},
            onTagConfirmed = {},
            onBack = {}
        )
    }
}

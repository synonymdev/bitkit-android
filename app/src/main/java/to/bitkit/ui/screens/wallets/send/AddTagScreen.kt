package to.bitkit.ui.screens.wallets.send

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddTagScreen(
    tags: List<String>,
    onTagSelected: (String) -> Unit,
    onTagAdded: (String) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        var inputText by remember { mutableStateOf("") }


        SheetTopBar(stringResource(R.string.wallet__tags_add)) {
            onBack()
        }

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
        ) {
            if (tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Caption13Up(text = stringResource(R.string.wallet__tags_previously), color = Colors.White64)
                Spacer(modifier = Modifier.height(16.dp))
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                tags.map { tagText ->
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
                value = inputText,
                onValueChange = { newText -> inputText = newText },
                maxLines = 1,
                singleLine = true,
                colors = AppTextFieldDefaults.noIndicatorColors,
                shape = MaterialTheme.shapes.small,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = {
                    onTagAdded(inputText)
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
            tags = listOf("Lunch", "Mom", "Dad", "Dinner", "Tip", "Gift"),
            onTagSelected = {},
            onTagAdded = {}) { }
    }
}

@Preview(showSystemUi = true, showBackground = true, name = "No tags")
@Composable
private fun Preview2() {
    AppThemeSurface {
        AddTagScreen(
            tags = listOf(),
            onTagSelected = {},
            onTagAdded = {}) { }
    }
}

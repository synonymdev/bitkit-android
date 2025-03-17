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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.scaffold.SheetTopBar
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
        SheetTopBar(stringResource(R.string.wallet__tags_add)) {
            onBack()
        }

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
        ) {

            Spacer(modifier = Modifier.height(16.dp))
            if (tags.isNotEmpty()) {
                Caption13Up(text = stringResource(R.string.wallet__tags_previously), color = Colors.White64)
                Spacer(modifier = Modifier.height(16.dp))
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tags.map { tagText ->
                    PrimaryButton( //TODO REPLACE WITH THE RIGHT COMPONENT
                        tagText,
                        onClick = { onTagSelected(tagText) },
                        size = ButtonSize.Small,
                        fullWidth = false
                    )
                }
            }
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

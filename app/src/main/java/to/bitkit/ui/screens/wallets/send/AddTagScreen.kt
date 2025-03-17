package to.bitkit.ui.screens.wallets.send

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import to.bitkit.R
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.theme.AppThemeSurface

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
        SheetTopBar(stringResource(R.string.wallet__tags_previously)) {
            onBack()
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

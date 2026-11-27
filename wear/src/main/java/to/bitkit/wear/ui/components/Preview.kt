package to.bitkit.wear.ui.components

import androidx.compose.runtime.Composable
import androidx.wear.compose.material3.AppScaffold
import to.bitkit.wear.ui.theme.BitkitWearTheme

@Composable
fun WearPreview(content: @Composable () -> Unit) {
    BitkitWearTheme {
        AppScaffold {
            content()
        }
    }
}

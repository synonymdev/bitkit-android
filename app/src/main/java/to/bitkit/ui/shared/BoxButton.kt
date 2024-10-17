package to.bitkit.ui.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun BoxButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable RowScope.() -> Unit,
) {
    Box(
        contentAlignment = contentAlignment,
        modifier = modifier
            .clickable(enabled, onClick = onClick)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            content()
        }
    }
}

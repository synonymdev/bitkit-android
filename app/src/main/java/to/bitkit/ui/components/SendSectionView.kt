package to.bitkit.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import to.bitkit.ui.theme.Colors

@Composable
fun SendSectionView(
    caption: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Caption13Up(text = caption, color = Colors.White64)
        VerticalSpacer(8.dp)
        content()
        HorizontalDivider(modifier = Modifier.fillMaxWidth())
    }
}

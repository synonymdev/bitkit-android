package to.bitkit.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import to.bitkit.ui.theme.Colors

@Composable
fun LinkRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        VerticalSpacer(16.dp)
        Text13Up(text = label, color = Colors.White64)
        VerticalSpacer(8.dp)
        BodySSB(text = value)
        VerticalSpacer(16.dp)
        HorizontalDivider()
    }
}

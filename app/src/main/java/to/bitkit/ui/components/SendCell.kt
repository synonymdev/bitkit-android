package to.bitkit.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun SendCell(
    caption: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Caption13Up(text = caption, color = Colors.White64)
        VerticalSpacer(8.dp)
        content()
        VerticalSpacer(16.dp)
        HorizontalDivider(modifier = Modifier.fillMaxWidth())
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        SendCell(caption = "AMOUNT") {
            Text("0.001 BTC", color = Colors.White)
        }
    }
}

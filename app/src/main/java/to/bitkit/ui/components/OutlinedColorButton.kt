package to.bitkit.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.ui.theme.AppShapes
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun OutlinedColorButton(
    onClick: () -> Unit,
    color: Color,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(28.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = color,
            disabledContentColor = color,
        ),
        enabled = enabled,
        shape = AppShapes.small,
        contentPadding = PaddingValues(8.dp, 4.dp),
        border = BorderStroke(1.dp, color),
    ) {
        content()
    }
}

@Preview
@Composable
private fun OutlinedColorButtonPreview() {
    AppThemeSurface {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedColorButton(
                onClick = { },
                color = Color.Blue,
            ) {
                Text("Blue Button")
            }

            OutlinedColorButton(
                onClick = { },
                color = Color.Red,
            ) {
                Text("Red Button")
            }

            OutlinedColorButton(
                onClick = { },
                color = Colors.Purple,
                enabled = false,
            ) {
                Text("Disabled Purple")
            }
        }
    }
}

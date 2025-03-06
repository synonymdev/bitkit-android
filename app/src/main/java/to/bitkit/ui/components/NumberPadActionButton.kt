package to.bitkit.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun NumberPadActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Colors.Brand,
) {
    Surface(
        color = Colors.White10,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
            .clickable(
                onClick = { onClick() }
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 5.dp, horizontal = 8.dp)
        ) {
            Caption13Up(
                text = text,
                color = color,
            )
        }
    }
}

@Preview
@Composable
private fun NumberPadActionButtonPreview() {
    AppThemeSurface {
        Box(modifier = Modifier.padding(16.dp)) {
            NumberPadActionButton(
                text = "Action",
                onClick = {},
            )
        }
    }
}

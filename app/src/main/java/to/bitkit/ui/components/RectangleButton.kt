package to.bitkit.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.theme.AppShapes
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun RectangleButton(
    label: String,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit = {},
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = Colors.White10,
        ),
        enabled = enabled,
        shape = AppShapes.smallButton,
        contentPadding = PaddingValues(24.dp),
        modifier = modifier
            .alpha(if (enabled) 1f else 0.5f)
            .height(80.dp)
            .fillMaxWidth()
    ) {
        icon()
        Spacer(modifier = Modifier.width(16.dp))
        BodyMSB(text = label, color = Colors.White)
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Preview
@Composable
private fun RectangleButtonPreview() {
    AppThemeSurface {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            RectangleButton(
                label = "Button",
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_scan),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(28.dp),
                    )
                }
            )
            RectangleButton(
                label = "Button Disabled",
                enabled = false,
                icon = {
                    Icon(
                        imageVector = Icons.Default.ContentPaste,
                        contentDescription = null,
                        tint = Colors.Brand,
                        modifier = Modifier.size(28.dp),
                    )
                }
            )
        }
    }
}

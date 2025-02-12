package to.bitkit.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import to.bitkit.ui.shared.util.DarkModePreview
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun RectangleButton(
    label: String,
    icon: ImageVector,
    iconColor: Color = Colors.Brand,
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
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(20.dp),
        modifier = modifier
            .alpha(if (enabled) 1f else 0.5f)
            .fillMaxWidth()
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = iconColor,
            modifier = Modifier.size(28.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        BodyMSB(text = label, color = Colors.White)
        Spacer(modifier = Modifier.weight(1f))
    }
}

@DarkModePreview
@Composable
private fun SendButtonPreview() {
    AppThemeSurface {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            RectangleButton("Button 1", icon = Icons.Default.Person)
            RectangleButton("Button 2", icon = Icons.Default.ContentPaste, enabled = false)
            RectangleButton("Button 3", icon = Icons.Outlined.Edit)
            RectangleButton("Button 4", icon = Icons.Default.CenterFocusWeak)
        }
    }
}

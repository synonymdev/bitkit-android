package to.bitkit.ui.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape

@Composable
fun FullWidthTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    colors: ButtonColors = ButtonDefaults.textButtonColors(
        contentColor = MaterialTheme.colorScheme.onSurface,
    ),
    content: @Composable (RowScope.() -> Unit),
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        colors = colors,
        shape = RectangleShape,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = horizontalArrangement,
            content = content,
        )
    }
}

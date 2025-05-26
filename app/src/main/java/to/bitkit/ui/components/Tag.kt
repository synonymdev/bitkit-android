package to.bitkit.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.AppShapes
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun TagButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    displayIconClose: Boolean = false,
    icon: Painter = painterResource(R.drawable.ic_x),
) {
    val borderColor = if (isSelected) Colors.Brand else Colors.White16
    val textColor = if (isSelected) Colors.Brand else MaterialTheme.colorScheme.onSurface

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .wrapContentWidth()
            .border(width = 1.dp, color = borderColor, shape = AppShapes.small)
            .clickableAlpha { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        BodySSB(
            text = text,
            color = textColor,
        )

        if (displayIconClose) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = Colors.White64,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TagButton("Selected", onClick = {}, isSelected = true)
            TagButton("Not Selected", onClick = {})
            TagButton("Selected With icon close", onClick = {}, isSelected = true, displayIconClose = true)
            TagButton("Not Selected With icon close", onClick = {}, displayIconClose = true)
            TagButton("Icon trash", onClick = {}, displayIconClose = true, icon = painterResource(R.drawable.ic_trash))
        }
    }
}

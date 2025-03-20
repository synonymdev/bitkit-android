package to.bitkit.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun TagButton(
    text: String,
    isSelected: Boolean,
    displayIconClose: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (isSelected) Colors.Brand else Colors.White16
    val textColor = if (isSelected) Colors.Brand else MaterialTheme.colorScheme.onSurface

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .wrapContentWidth()
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            color = textColor,
            fontWeight = FontWeight.Medium,
        )

        if (displayIconClose) {
            Icon(
                painter = painterResource(R.drawable.ic_x),
                contentDescription = null,
                tint = Colors.White
            )
        }
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TagButton("Selected", isSelected = true, onClick = {})
            TagButton("Not Selected", isSelected = false, onClick = {})
            TagButton("Selected With icon close", displayIconClose = true, isSelected = true, onClick = {})
            TagButton("Not Selected With icon close", displayIconClose = true, isSelected = false, onClick = {})
        }
    }
}

package to.bitkit.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.bitkit.ui.theme.Colors

@Composable
fun LabelText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Caption13Up(
        text = text,
        modifier = modifier.padding(vertical = 4.dp),
    )
}

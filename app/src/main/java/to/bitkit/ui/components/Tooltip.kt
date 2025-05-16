package to.bitkit.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import to.bitkit.ui.theme.Colors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Tooltip(
    text: String,
    tooltipState: TooltipState,
    modifier: Modifier = Modifier,
    content: @Composable (() -> Unit)
) {

    TooltipBox(
        modifier = modifier,
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip(
                caretSize = DpSize(
                    width = 16.dp,
                    height = 12.dp
                ),
                containerColor = Colors.Black92,
                contentColor = Colors.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
            ) {
                CaptionB(
                    text,
                    color = Colors.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 37.dp, vertical = 24.dp)
                )
            }
        },
        state = tooltipState,
        content = content
    )
}

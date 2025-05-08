package to.bitkit.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.bitkit.R
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

const val KEY_DELETE = "delete"

private val matrix = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
)

@Composable
private fun NumberButton(
    text: String,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .clickableAlpha(0.2f) { onPress() }
    ) {
        Text(
            text = text,
            fontSize = 24.sp,
            textAlign = TextAlign.Center,
            color = Colors.White,
        )
    }
}

@Composable
fun NumberPadSimple(
    onPress: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        matrix.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                row.forEach { number ->
                    NumberButton(
                        text = number,
                        onPress = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPress(number)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            Box(modifier = Modifier.weight(1f))
            NumberButton(
                text = "0",
                onPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onPress("0")
                },
                modifier = Modifier.weight(1f)
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clickableAlpha(0.2f) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPress(KEY_DELETE)
                    }
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_backspace),
                    contentDescription = stringResource(R.string.common__delete),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        NumberPadSimple(
            onPress = {},
            modifier = Modifier.height(310.dp)
        )
    }
}

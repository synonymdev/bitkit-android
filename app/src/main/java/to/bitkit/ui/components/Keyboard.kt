package to.bitkit.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.bitkit.R
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.theme.InterFontFamily

val buttonHeight = 75.dp // 75 * 4 = 300 height
val buttonHaptic = HapticFeedbackType.LongPress

@Composable
fun Keyboard(
    onClick: (String) -> Unit,
    onClickBackspace: () -> Unit,
    modifier: Modifier = Modifier,
    isDecimal: Boolean = true,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        userScrollEnabled = false,
        modifier = modifier,
    ) {
        item { KeyboardButton(text = "1", onClick = onClick) }
        item { KeyboardButton(text = "2", onClick = onClick) }
        item { KeyboardButton(text = "3", onClick = onClick) }
        item { KeyboardButton(text = "4", onClick = onClick) }
        item { KeyboardButton(text = "5", onClick = onClick) }
        item { KeyboardButton(text = "6", onClick = onClick) }
        item { KeyboardButton(text = "7", onClick = onClick) }
        item { KeyboardButton(text = "8", onClick = onClick) }
        item { KeyboardButton(text = "9", onClick = onClick) }
        item { KeyboardButton(text = if (isDecimal) "." else "000", onClick = onClick) }
        item { KeyboardButton(text = "0", onClick = onClick) }
        item {
            ButtonBox(
                onClick = onClickBackspace,
                modifier = Modifier.testTag("KeyboardButton_backspace"),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_backspace),
                    contentDescription = stringResource(R.string.common__delete),
                )
            }
        }
    }
}

@Composable
private fun KeyboardButton(
    text: String,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ButtonBox(
        onClick = { onClick(text) },
        modifier = modifier.testTag("KeyboardButton_$text"),
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 24.sp,
                lineHeight = 44.sp,
                letterSpacing = (-0.1).sp,
                textAlign = TextAlign.Center,
                color = Colors.White,
            ),
        )
    }
}

@Composable
private fun ButtonBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (BoxScope.() -> Unit),
) {
    val haptic = LocalHapticFeedback.current
    Box(
        content = content,
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(buttonHeight)
            .fillMaxSize()
            .clickableAlpha(0.2f) {
                haptic.performHapticFeedback(buttonHaptic)
                onClick()
            },
    )
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
            Keyboard(
                onClick = {},
                onClickBackspace = {},
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview2() {
    AppThemeSurface {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
            Keyboard(
                isDecimal = false,
                onClick = {},
                onClickBackspace = {},
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Preview(showBackground = true, device = Devices.PIXEL_TABLET)
@Composable
private fun Preview3() {
    AppThemeSurface {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
            Keyboard(
                isDecimal = false,
                onClick = {},
                onClickBackspace = {},
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

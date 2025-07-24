package to.bitkit.ui.components

import androidx.annotation.DrawableRes
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.bitkit.R
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

private val buttonHeight = 75.dp // 75 * 4 = 300 height
val keyButtonHaptic = HapticFeedbackType.VirtualKey

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
        item { KeyTextButton(text = "1", onClick = onClick) }
        item { KeyTextButton(text = "2", onClick = onClick) }
        item { KeyTextButton(text = "3", onClick = onClick) }
        item { KeyTextButton(text = "4", onClick = onClick) }
        item { KeyTextButton(text = "5", onClick = onClick) }
        item { KeyTextButton(text = "6", onClick = onClick) }
        item { KeyTextButton(text = "7", onClick = onClick) }
        item { KeyTextButton(text = "8", onClick = onClick) }
        item { KeyTextButton(text = "9", onClick = onClick) }
        item { KeyTextButton(text = if (isDecimal) "." else "000", onClick = onClick) }
        item { KeyTextButton(text = "0", onClick = onClick) }
        item {
            KeyIconButton(
                icon = R.drawable.ic_backspace,
                contentDescription = stringResource(R.string.common__delete),
                onClick = onClickBackspace,
                modifier = Modifier.testTag("KeyboardButton_backspace"),
            )
        }
    }
}

@Composable
fun KeyIconButton(
    @DrawableRes icon: Int,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    KeyButtonBox(
        onClick = onClick,
        modifier = modifier,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
        )
    }
}

@Composable
fun KeyTextButton(
    text: String,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    KeyButtonBox(
        onClick = { onClick(text) },
        modifier = modifier.testTag("KeyboardButton_$text"),
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
private fun KeyButtonBox(
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
                haptic.performHapticFeedback(keyButtonHaptic)
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

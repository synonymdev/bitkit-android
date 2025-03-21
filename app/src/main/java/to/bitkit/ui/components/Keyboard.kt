package to.bitkit.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.theme.InterFontFamily

@Composable
fun Keyboard(
    onClick: (String) -> Unit,
    isDecimal: Boolean = true,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        verticalArrangement = Arrangement.spacedBy(34.dp),
        columns = GridCells.Fixed(3),
        modifier = modifier
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
        item { KeyboardButton(text = "", onClick = onClick) }
    }
}

@Composable
private fun KeyboardButton(
    text: String,
    onClick: (String) -> Unit
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
        modifier = Modifier.clickable(
            onClick = {
                onClick(text)
            },
            onClickLabel = text
        ).testTag("KeyboardButton_$text"),
    )
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
            Keyboard(modifier = Modifier.fillMaxWidth().padding(41.dp), onClick = {})
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview2() {
    AppThemeSurface {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
            Keyboard(isDecimal = false, modifier = Modifier.fillMaxWidth().padding(41.dp), onClick = {})
        }
    }
}

@Preview(showBackground = true, device = Devices.PIXEL_TABLET)
@Composable
private fun Preview3() {
    AppThemeSurface {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
            Keyboard(isDecimal = false, modifier = Modifier.fillMaxWidth().padding(41.dp), onClick = {})
        }
    }
}

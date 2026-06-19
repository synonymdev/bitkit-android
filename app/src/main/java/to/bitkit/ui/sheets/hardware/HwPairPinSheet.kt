package to.bitkit.ui.sheets.hardware

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.KEY_DELETE
import to.bitkit.ui.components.NumberPad
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

private const val PAIRING_CODE_LENGTH = 6
private val PAIRING_CELL_WIDTH = 32.dp

@Composable
fun HwPairCodeSheet(
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var code by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }

    // Dismissing the sheet without submitting (e.g. swipe down) cancels the pending
    // pairing request so the connect attempt does not hang until its timeout.
    DisposableEffect(Unit) {
        onDispose { if (!submitted) onCancel() }
    }

    Content(
        code = code,
        onKeyPress = { key ->
            when {
                key == KEY_DELETE -> code = code.dropLast(1)
                code.length < PAIRING_CODE_LENGTH -> {
                    code += key
                    if (code.length == PAIRING_CODE_LENGTH) {
                        submitted = true
                        onSubmit(code)
                    }
                }
            }
        },
        modifier = modifier
    )
}

@Composable
private fun Content(
    code: String,
    onKeyPress: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .testTag("HwPairScreen")
    ) {
        SheetTopBar(titleText = stringResource(R.string.hardware__pairing_title))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 32.dp)
        ) {
            BodyM(stringResource(R.string.hardware__pairing_text), color = Colors.White64)
            FillHeight()
            // Fixed-width cells so digits replace dots without the row shifting.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(PAIRING_CODE_LENGTH) { index ->
                    val digit = code.getOrNull(index)?.toString()
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.width(PAIRING_CELL_WIDTH)
                    ) {
                        Display(
                            text = digit ?: "•",
                            color = if (digit != null) Colors.White else Colors.White32,
                        )
                    }
                }
            }
            FillHeight()
        }
        NumberPad(
            onPress = onKeyPress,
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                code = "123",
                onKeyPress = {},
                modifier = Modifier.sheetHeight()
            )
        }
    }
}

package to.bitkit.ui.sheets.hardware

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.GradientCircularProgressIndicator
import to.bitkit.ui.components.KEY_DELETE
import to.bitkit.ui.components.NumberPad
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

private const val PAIRING_CODE_LENGTH = 6
private const val PAIRING_CHAR_COLLAPSED_SCALE_X = 0.15f
private const val PAIRING_CHAR_COLLAPSED_SCALE_Y = 0.85f
private val PAIRING_CELL_WIDTH = 32.dp
private val PAIRING_CELL_SPACING = 8.dp

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
        submitting = submitted,
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
    modifier: Modifier = Modifier,
    submitting: Boolean = false,
    onKeyPress: (String) -> Unit = {},
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
            PinInput(submitting, code)
            FillHeight()
        }
        NumberPad(
            onPress = onKeyPress,
            enabled = !submitting,
        )
        VerticalSpacer(16.dp)
    }
}

@Composable
private fun PinInput(
    submitting: Boolean,
    code: String,
) {
    val submitProgress by animateFloatAsState(
        targetValue = if (submitting) 1f else 0f,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "pairCodeSubmit",
    )
    val cellStepPx = with(LocalDensity.current) {
        (PAIRING_CELL_WIDTH + PAIRING_CELL_SPACING).toPx()
    }
    Box(contentAlignment = Alignment.Center) {
        // Fixed-width cells so digits replace dots without the row shifting.
        Row(
            horizontalArrangement = Arrangement.spacedBy(PAIRING_CELL_SPACING),
        ) {
            repeat(PAIRING_CODE_LENGTH) { index ->
                val digit = code.getOrNull(index)?.toString()
                val centerOffset = (PAIRING_CODE_LENGTH - 1) / 2f - index
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .width(PAIRING_CELL_WIDTH)
                        .graphicsLayer {
                            alpha = 1f - submitProgress
                            translationX = centerOffset * cellStepPx * submitProgress
                            scaleX = 1f - (1f - PAIRING_CHAR_COLLAPSED_SCALE_X) * submitProgress
                            scaleY = 1f - (1f - PAIRING_CHAR_COLLAPSED_SCALE_Y) * submitProgress
                        }
                ) {
                    Display(
                        text = digit ?: "•",
                        color = if (digit != null) Colors.White else Colors.White32,
                    )
                }
            }
        }
        if (submitting) {
            GradientCircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier
                    .size(32.dp)
                    .graphicsLayer {
                        alpha = submitProgress
                        val spinnerScale = 0.8f + 0.2f * submitProgress
                        scaleX = spinnerScale
                        scaleY = spinnerScale
                    }
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                code = "123",
                modifier = Modifier.sheetHeight()
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewSubmitting() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                code = "123",
                submitting = true,
                modifier = Modifier.sheetHeight()
            )
        }
    }
}

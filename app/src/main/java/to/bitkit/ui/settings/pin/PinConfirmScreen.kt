package to.bitkit.ui.settings.pin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import to.bitkit.R
import to.bitkit.domain.models.secretOf
import to.bitkit.env.Env
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.KEY_DELETE
import to.bitkit.ui.components.NumberPad
import to.bitkit.ui.components.NumberPadType
import to.bitkit.ui.components.PinDots
import to.bitkit.ui.components.mutableSecretOf
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun PinConfirmScreen(
    onPinConfirmed: () -> Unit,
    onBack: () -> Unit,
) {
    val app = appViewModel ?: return
    var pin by remember { mutableSecretOf() }
    var showError by remember { mutableStateOf(false) }

    LaunchedEffect(pin) {
        if (pin.size == Env.PIN_LENGTH) {
            val matches = app.consumePendingPin()?.let { pending ->
                val result = pending.peek { it.contentEquals(pin) }
                if (result) {
                    app.addPin(secretOf(pin.copyOf()))
                    pending.wipe()
                }
                result
            } ?: false
            if (matches) {
                onPinConfirmed()
            } else {
                showError = true
                delay(500)
                pin = charArrayOf()
            }
        }
    }

    ConfirmPinContent(
        pinLength = pin.size,
        showError = showError,
        onKeyPress = { key ->
            if (key == KEY_DELETE) {
                if (pin.isNotEmpty()) {
                    pin = pin.sliceArray(0 until pin.size - 1)
                }
            } else if (pin.size < Env.PIN_LENGTH) {
                pin = pin + key[0]
            }
        },
        onBack = onBack,
    )
}

@Composable
private fun ConfirmPinContent(
    pinLength: Int,
    showError: Boolean,
    onKeyPress: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .gradientBackground()
            .navigationBarsPadding()
    ) {
        SheetTopBar(
            stringResource(R.string.security__pin_retype_header),
            onBack = onBack,
        )

        Spacer(modifier = Modifier.height(16.dp))

        BodyM(
            text = stringResource(R.string.security__pin_retype_text),
            color = Colors.White64,
            modifier = Modifier.padding(horizontal = 32.dp),
        )

        Spacer(modifier = Modifier.height(32.dp))
        Spacer(modifier = Modifier.weight(1f))

        AnimatedVisibility(visible = showError) {
            BodyS(
                text = stringResource(R.string.security__pin_not_match),
                textAlign = TextAlign.Center,
                color = Colors.Brand,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .testTag("WrongPIN")
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        PinDots(pinLength = pinLength)

        Spacer(modifier = Modifier.height(32.dp))

        NumberPad(
            onPress = onKeyPress,
            type = NumberPadType.SIMPLE,
            modifier = Modifier
                .height(350.dp)
                .background(Colors.Black)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        ConfirmPinContent(
            pinLength = 0,
            showError = false,
            onKeyPress = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewRetry() {
    AppThemeSurface {
        ConfirmPinContent(
            pinLength = 3,
            showError = true,
            onKeyPress = {},
            onBack = {},
        )
    }
}

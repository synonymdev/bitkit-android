package to.bitkit.ui.screens.wallets.send

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.KEY_DELETE
import to.bitkit.ui.components.PinDots
import to.bitkit.ui.components.PinNumberPad
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

const val PIN_CHECK_RESULT_KEY = "PIN_CHECK_RESULT_KEY"

@Composable
fun PinCheckScreen(
    onBack: () -> Unit,
    onSuccess: () -> Unit,
) {
    val app = appViewModel ?: return
    val attemptsRemaining by app.pinAttemptsRemaining.collectAsStateWithLifecycle()
    var pin by remember { mutableStateOf("") }

    LaunchedEffect(pin) {
        if (pin.length == Env.PIN_LENGTH) {
            if (app.validatePin(pin)) {
                onSuccess()
            }
            pin = ""
        }
    }

    PinCheckContent(
        pin = pin,
        attemptsRemaining = attemptsRemaining,
        onPinChange = { pin = it },
        onBack = onBack,
        onForgotPin = { app.setShowForgotPin(true) },
    )
}

@Composable
private fun PinCheckContent(
    pin: String,
    attemptsRemaining: Int,
    onPinChange: (String) -> Unit,
    onBack: () -> Unit,
    onForgotPin: () -> Unit,
) {
    val isLastAttempt = attemptsRemaining == 1

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .gradientBackground()
            .navigationBarsPadding()
    ) {
        SheetTopBar(
            titleText = stringResource(R.string.security__pin_send_title),
            onBack = onBack,
        )
        Spacer(Modifier.height(32.dp))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BodyM(
                text = stringResource(R.string.security__pin_send),
                color = Colors.White64,
                modifier = Modifier.padding(horizontal = 32.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            if (attemptsRemaining < Env.PIN_ATTEMPTS) {
                if (isLastAttempt) {
                    BodyS(
                        text = stringResource(R.string.security__pin_last_attempt),
                        color = Colors.Brand,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                } else {
                    BodyS(
                        text = stringResource(R.string.security__pin_attempts)
                            .replace("{attemptsRemaining}", "$attemptsRemaining"),
                        color = Colors.Brand,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(horizontal = 32.dp)
                            .clickableAlpha { onForgotPin() }
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            PinDots(
                pin = pin,
                modifier = Modifier.padding(vertical = 16.dp),
            )

            Spacer(modifier = Modifier.weight(1f))

            PinNumberPad(
                onPress = { key ->
                    val newPin = when {
                        key == KEY_DELETE && pin.isNotEmpty() -> pin.dropLast(1)
                        key != KEY_DELETE && pin.length < Env.PIN_LENGTH -> pin + key
                        else -> pin
                    }
                    onPinChange(newPin)
                },
                modifier = Modifier
                    .height(350.dp)
                    .background(Colors.Black)
            )
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        PinCheckContent(
            pin = "123",
            attemptsRemaining = 8,
            onPinChange = {},
            onBack = {},
            onForgotPin = {},
        )
    }
}

@Preview
@Composable
private fun PreviewAttempts() {
    AppThemeSurface {
        PinCheckContent(
            pin = "123",
            attemptsRemaining = 3,
            onPinChange = {},
            onBack = {},
            onForgotPin = {},
        )
    }
}

@Preview
@Composable
private fun PreviewAttemptsLast() {
    AppThemeSurface {
        PinCheckContent(
            pin = "123",
            attemptsRemaining = 1,
            onPinChange = {},
            onBack = {},
            onForgotPin = {},
        )
    }
}

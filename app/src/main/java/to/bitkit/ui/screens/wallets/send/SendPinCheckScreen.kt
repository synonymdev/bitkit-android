package to.bitkit.ui.screens.wallets.send

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.platform.testTag
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
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.KEY_DELETE
import to.bitkit.ui.components.NumberPad
import to.bitkit.ui.components.NumberPadType
import to.bitkit.ui.components.PinDots
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

const val PIN_CHECK_RESULT_KEY = "PIN_CHECK_RESULT_KEY"

@Composable
fun SendPinCheckScreen(
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
        onKeyPress = { key ->
            if (key == KEY_DELETE) {
                if (pin.isNotEmpty()) {
                    pin = pin.dropLast(1)
                }
            } else if (pin.length < Env.PIN_LENGTH) {
                pin += key
            }
        },
        onBack = onBack,
        onClickForgotPin = { app.setShowForgotPin(true) },
    )
}

@Composable
private fun PinCheckContent(
    pin: String,
    attemptsRemaining: Int,
    onKeyPress: (String) -> Unit,
    onBack: () -> Unit,
    onClickForgotPin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isLastAttempt = attemptsRemaining == 1

    Column(
        modifier = modifier
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

            AnimatedVisibility(visible = attemptsRemaining < Env.PIN_ATTEMPTS) {
                if (isLastAttempt) {
                    BodyS(
                        text = stringResource(R.string.security__pin_last_attempt),
                        color = Colors.Brand,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(horizontal = 32.dp)
                            .testTag("LastAttempt")
                    )
                } else {
                    BodyS(
                        text = stringResource(R.string.security__pin_attempts)
                            .replace("{attemptsRemaining}", "$attemptsRemaining"),
                        color = Colors.Brand,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(horizontal = 32.dp)
                            .clickableAlpha { onClickForgotPin() }
                            .testTag("AttemptsRemaining")
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            PinDots(
                pin = pin,
                modifier = Modifier.padding(vertical = 16.dp),
            )

            Spacer(modifier = Modifier.weight(1f))

            NumberPad(
                onPress = onKeyPress,
                type = NumberPadType.SIMPLE,
                modifier = Modifier
                    .height(350.dp)
                    .background(Colors.Black)
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            PinCheckContent(
                pin = "123",
                attemptsRemaining = 8,
                onKeyPress = {},
                onBack = {},
                onClickForgotPin = {},
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewAttemptsLeft() {
    AppThemeSurface {
        BottomSheetPreview {
            PinCheckContent(
                pin = "123",
                attemptsRemaining = 3,
                onKeyPress = {},
                onBack = {},
                onClickForgotPin = {},
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewAttemptsLast() {
    AppThemeSurface {
        BottomSheetPreview {
            PinCheckContent(
                pin = "123",
                attemptsRemaining = 1,
                onKeyPress = {},
                onBack = {},
                onClickForgotPin = {},
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}

package to.bitkit.ui.sheets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.NumberPad
import to.bitkit.ui.components.NumberPadType
import to.bitkit.ui.components.PinDots
import to.bitkit.ui.components.SheetSize
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.NumberPadHeight
import to.bitkit.ui.utils.handlePinKeyPress
import to.bitkit.ui.utils.withAccentBoldBright
import to.bitkit.viewmodels.AppViewModel

@Composable
fun DisablePinSheet(app: AppViewModel) {
    val attemptsRemaining by app.pinAttemptsRemaining.collectAsStateWithLifecycle()
    var pin by remember { mutableStateOf("") }
    val onDismiss = app::hideSheet

    LaunchedEffect(pin) {
        if (pin.length == Env.PIN_LENGTH) {
            if (app.validatePin(pin)) {
                app.removePin()
                onDismiss()
            } else {
                pin = ""
            }
        }
    }

    Content(
        pin = pin,
        attemptsRemaining = attemptsRemaining,
        onKeyPress = { pin = handlePinKeyPress(pin, it) },
        onBackClick = onDismiss,
        onClickForgotPin = { app.setShowForgotPin(true) },
    )
}

@Composable
private fun Content(
    pin: String,
    attemptsRemaining: Int,
    onKeyPress: (String) -> Unit,
    onBackClick: () -> Unit,
    onClickForgotPin: () -> Unit,
) {
    val isLastAttempt = attemptsRemaining == 1

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .sheetHeight(SheetSize.MEDIUM)
            .gradientBackground()
            .navigationBarsPadding()
            .testTag("DisablePIN"),
    ) {
        SheetTopBar(
            titleText = stringResource(R.string.security__pin_disable_button),
            onBack = onBackClick,
        )

        VerticalSpacer(16.dp)

        BodyM(
            text = stringResource(R.string.security__pin_disable_text).withAccentBoldBright(),
            color = Colors.White64,
            modifier = Modifier.padding(horizontal = 32.dp),
        )

        VerticalSpacer(32.dp)

        AnimatedVisibility(visible = attemptsRemaining < Env.PIN_ATTEMPTS) {
            if (isLastAttempt) {
                BodyS(
                    text = stringResource(R.string.security__pin_last_attempt),
                    color = Colors.Brand,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("LastAttempt"),
                )
            } else {
                BodyS(
                    text = stringResource(R.string.security__pin_attempts)
                        .replace("{attemptsRemaining}", "$attemptsRemaining"),
                    color = Colors.Brand,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickableAlpha { onClickForgotPin() }
                        .testTag("AttemptsRemaining"),
                )
            }
            VerticalSpacer(16.dp)
        }

        FillHeight()

        PinDots(pin = pin)

        VerticalSpacer(32.dp)

        NumberPad(
            onPress = onKeyPress,
            type = NumberPadType.SIMPLE,
            modifier = Modifier
                .height(NumberPadHeight)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            pin = "12",
            attemptsRemaining = 8,
            onKeyPress = {},
            onBackClick = {},
            onClickForgotPin = {},
        )
    }
}

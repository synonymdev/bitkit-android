package to.bitkit.ui.settings.pin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.KEY_DELETE
import to.bitkit.ui.components.PinDots
import to.bitkit.ui.components.PinNumberPad
import to.bitkit.ui.navigateToChangePinNew
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun ChangePinScreen(
    navController: NavController,
) {
    val app = appViewModel ?: return
    val attemptsRemaining by app.pinAttemptsRemaining.collectAsStateWithLifecycle()
    var pin by remember { mutableStateOf("") }

    LaunchedEffect(pin) {
        if (pin.length == Env.PIN_LENGTH) {
            if (app.validatePin(pin)) {
                navController.navigateToChangePinNew()
            } else {
                pin = ""
            }
        }
    }

    ChangePinContent(
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
        onBackClick = { navController.popBackStack() },
        onCloseClick = { navController.navigateToHome() },
        onClickForgotPin = { app.setShowForgotPin(true) },
    )
}

@Composable
private fun ChangePinContent(
    pin: String,
    attemptsRemaining: Int,
    onKeyPress: (String) -> Unit,
    onBackClick: () -> Unit,
    onCloseClick: () -> Unit,
    onClickForgotPin: () -> Unit,
) {
    val isLastAttempt = attemptsRemaining == 1

    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.security__cp_title),
            onBackClick = onBackClick,
            actions = { CloseNavIcon(onClick = onCloseClick) },
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            BodyM(
                text = stringResource(R.string.security__cp_text),
                color = Colors.White64,
            )

            Spacer(modifier = Modifier.height(32.dp))

            AnimatedVisibility(visible = attemptsRemaining < Env.PIN_ATTEMPTS) {
                if (isLastAttempt) {
                    BodyS(
                        text = stringResource(R.string.security__pin_last_attempt),
                        color = Colors.Brand,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    BodyS(
                        text = stringResource(R.string.security__pin_attempts).replace(
                            "{attemptsRemaining}",
                            "$attemptsRemaining"
                        ),
                        color = Colors.Brand,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.clickableAlpha { onClickForgotPin() }
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
                modifier = Modifier.height(350.dp),
                onPress = onKeyPress,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        ChangePinContent(
            pin = "12",
            attemptsRemaining = 8,
            onKeyPress = {},
            onBackClick = {},
            onCloseClick = {},
            onClickForgotPin = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewAttemptsRemaining() {
    AppThemeSurface {
        ChangePinContent(
            pin = "1234",
            attemptsRemaining = 5,
            onKeyPress = {},
            onBackClick = {},
            onCloseClick = {},
            onClickForgotPin = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewAttemptsLast() {
    AppThemeSurface {
        ChangePinContent(
            pin = "",
            attemptsRemaining = 1,
            onKeyPress = {},
            onBackClick = {},
            onCloseClick = {},
            onClickForgotPin = {},
        )
    }
}

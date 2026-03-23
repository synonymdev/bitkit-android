package to.bitkit.ui.sheets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.KEY_DELETE
import to.bitkit.ui.components.NumberPad
import to.bitkit.ui.components.NumberPadType
import to.bitkit.ui.components.PinDots
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SheetSize
import to.bitkit.ui.navigateTo
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.composableWithDefaultTransitions
import to.bitkit.viewmodels.AppViewModel

@Suppress("CyclomaticComplexMethod")
@Composable
fun ChangePinSheet(app: AppViewModel) {
    val navController = rememberNavController()
    val onDismiss = app::hideSheet

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .sheetHeight(SheetSize.MEDIUM)
            .gradientBackground()
    ) {
        NavHost(
            navController = navController,
            startDestination = ChangePinRoute.Validate,
        ) {
            composableWithDefaultTransitions<ChangePinRoute.Validate> {
                val attemptsRemaining by app.pinAttemptsRemaining.collectAsStateWithLifecycle()
                var pin by remember { mutableStateOf("") }

                LaunchedEffect(pin) {
                    if (pin.length == Env.PIN_LENGTH) {
                        if (app.validatePin(pin)) {
                            navController.navigateTo(ChangePinRoute.New)
                        } else {
                            pin = ""
                        }
                    }
                }

                ValidateContent(
                    pin = pin,
                    attemptsRemaining = attemptsRemaining,
                    onKeyPress = { key ->
                        if (key == KEY_DELETE) {
                            if (pin.isNotEmpty()) pin = pin.dropLast(1)
                        } else if (pin.length < Env.PIN_LENGTH) {
                            pin += key
                        }
                    },
                    onBackClick = onDismiss,
                    onClickForgotPin = { app.setShowForgotPin(true) },
                )
            }
            composableWithDefaultTransitions<ChangePinRoute.New> {
                var pin by remember { mutableStateOf("") }

                LaunchedEffect(pin) {
                    if (pin.length == Env.PIN_LENGTH) {
                        navController.navigateTo(ChangePinRoute.Confirm(pin))
                    }
                }

                NewPinContent(
                    pin = pin,
                    onKeyPress = { key ->
                        if (key == KEY_DELETE) {
                            if (pin.isNotEmpty()) pin = pin.dropLast(1)
                        } else if (pin.length < Env.PIN_LENGTH) {
                            pin += key
                        }
                    },
                    onBackClick = { navController.popBackStack() },
                )
            }
            composableWithDefaultTransitions<ChangePinRoute.Confirm> {
                val newPin = it.toRoute<ChangePinRoute.Confirm>().pin
                var pin by remember { mutableStateOf("") }
                var showError by remember { mutableStateOf(false) }

                LaunchedEffect(pin) {
                    if (pin.length == Env.PIN_LENGTH) {
                        if (pin == newPin) {
                            app.editPin(newPin)
                            navController.navigateTo(ChangePinRoute.Result)
                        } else {
                            showError = true
                            delay(500)
                            pin = ""
                        }
                    }
                }

                ConfirmContent(
                    pin = pin,
                    showError = showError,
                    onKeyPress = { key ->
                        if (key == KEY_DELETE) {
                            if (pin.isNotEmpty()) pin = pin.dropLast(1)
                        } else if (pin.length < Env.PIN_LENGTH) {
                            pin += key
                        }
                    },
                    onBackClick = { navController.popBackStack() },
                )
            }
            composableWithDefaultTransitions<ChangePinRoute.Result> {
                ResultContent(
                    onOkClick = onDismiss,
                    onBackClick = onDismiss,
                )
            }
        }
    }
}

sealed interface ChangePinRoute {
    @Serializable
    data object Validate : ChangePinRoute

    @Serializable
    data object New : ChangePinRoute

    @Serializable
    data class Confirm(val pin: String) : ChangePinRoute

    @Serializable
    data object Result : ChangePinRoute
}

@Composable
private fun ValidateContent(
    pin: String,
    attemptsRemaining: Int,
    onKeyPress: (String) -> Unit,
    onBackClick: () -> Unit,
    onClickForgotPin: () -> Unit,
) {
    val isLastAttempt = attemptsRemaining == 1

    ScreenColumn(
        noBackground = true,
        modifier = Modifier.testTag("ChangePIN"),
    ) {
        AppTopBar(
            titleText = stringResource(R.string.security__cp_title),
            onBackClick = onBackClick,
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
                        modifier = Modifier.testTag("LastAttempt")
                    )
                } else {
                    BodyS(
                        text = stringResource(R.string.security__pin_attempts)
                            .replace("{attemptsRemaining}", "$attemptsRemaining"),
                        color = Colors.Brand,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
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
                modifier = Modifier.height(350.dp),
            )
        }
    }
}

@Composable
private fun NewPinContent(
    pin: String,
    onKeyPress: (String) -> Unit,
    onBackClick: () -> Unit,
) {
    ScreenColumn(
        noBackground = true,
        modifier = Modifier.testTag("ChangePIN2"),
    ) {
        AppTopBar(
            titleText = stringResource(R.string.security__cp_setnew_title),
            onBackClick = onBackClick,
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            BodyM(
                text = stringResource(R.string.security__cp_setnew_text),
                color = Colors.White64,
            )

            Spacer(modifier = Modifier.height(32.dp))

            PinDots(
                pin = pin,
                modifier = Modifier.padding(vertical = 16.dp),
            )

            Spacer(modifier = Modifier.weight(1f))

            NumberPad(
                onPress = onKeyPress,
                type = NumberPadType.SIMPLE,
                modifier = Modifier.height(350.dp),
            )
        }
    }
}

@Composable
private fun ConfirmContent(
    pin: String,
    showError: Boolean,
    onKeyPress: (String) -> Unit,
    onBackClick: () -> Unit,
) {
    ScreenColumn(
        noBackground = true,
        modifier = Modifier.testTag("ChangePIN2"),
    ) {
        AppTopBar(
            titleText = stringResource(R.string.security__cp_retype_title),
            onBackClick = onBackClick,
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            BodyM(
                text = stringResource(R.string.security__cp_retype_text),
                color = Colors.White64,
            )

            Spacer(modifier = Modifier.height(32.dp))

            AnimatedVisibility(visible = showError) {
                BodyS(
                    text = stringResource(R.string.security__cp_try_again),
                    textAlign = TextAlign.Center,
                    color = Colors.Brand,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("WrongPIN")
                )
            }

            PinDots(
                pin = pin,
                modifier = Modifier.padding(vertical = 16.dp),
            )

            Spacer(modifier = Modifier.weight(1f))

            NumberPad(
                onPress = onKeyPress,
                type = NumberPadType.SIMPLE,
                modifier = Modifier.height(350.dp),
            )
        }
    }
}

@Composable
private fun ResultContent(
    onOkClick: () -> Unit,
    onBackClick: () -> Unit,
) {
    ScreenColumn(noBackground = true) {
        AppTopBar(stringResource(R.string.security__cp_changed_title), onBackClick = onBackClick)
        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            BodyM(
                text = stringResource(R.string.security__cp_changed_text),
                color = Colors.White64,
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Image(
                    painter = painterResource(R.drawable.check),
                    contentDescription = null,
                    modifier = Modifier.size(256.dp)
                )
            }

            PrimaryButton(
                text = stringResource(R.string.common__ok),
                onClick = onOkClick,
                modifier = Modifier.testTag("OK")
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewValidate() {
    AppThemeSurface {
        ValidateContent(
            pin = "12",
            attemptsRemaining = 8,
            onKeyPress = {},
            onBackClick = {},
            onClickForgotPin = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewNew() {
    AppThemeSurface {
        NewPinContent(
            pin = "12",
            onKeyPress = {},
            onBackClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewConfirm() {
    AppThemeSurface {
        ConfirmContent(
            pin = "12",
            showError = false,
            onKeyPress = {},
            onBackClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewResult() {
    AppThemeSurface {
        ResultContent(
            onOkClick = {},
            onBackClick = {},
        )
    }
}

package to.bitkit.ui.sheets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.NumberPad
import to.bitkit.ui.components.NumberPadType
import to.bitkit.ui.components.PinDots
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SheetSize
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.navigateTo
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.NumberPadHeight
import to.bitkit.ui.utils.composableWithDefaultTransitions
import to.bitkit.ui.utils.handlePinKeyPress
import to.bitkit.ui.utils.withAccentBoldBright
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
                    onKeyPress = { pin = handlePinKeyPress(pin, it) },
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
                    onKeyPress = { pin = handlePinKeyPress(pin, it) },
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
                    onKeyPress = { pin = handlePinKeyPress(pin, it) },
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .gradientBackground()
            .navigationBarsPadding()
            .testTag("ChangePIN"),
    ) {
        SheetTopBar(
            titleText = stringResource(R.string.security__cp_title),
            onBack = onBackClick,
        )

        VerticalSpacer(16.dp)

        BodyM(
            text = stringResource(R.string.security__cp_text).withAccentBoldBright(),
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
                .height(NumberPadHeight),
        )
    }
}

@Composable
private fun NewPinContent(
    pin: String,
    onKeyPress: (String) -> Unit,
    onBackClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .gradientBackground()
            .navigationBarsPadding()
            .testTag("ChangePIN2"),
    ) {
        SheetTopBar(
            titleText = stringResource(R.string.security__cp_setnew_title),
            onBack = onBackClick,
        )

        VerticalSpacer(16.dp)

        BodyM(
            text = stringResource(R.string.security__cp_setnew_text),
            color = Colors.White64,
            modifier = Modifier.padding(horizontal = 32.dp),
        )

        VerticalSpacer(32.dp)
        FillHeight()

        PinDots(pin = pin)

        VerticalSpacer(32.dp)

        NumberPad(
            onPress = onKeyPress,
            type = NumberPadType.SIMPLE,
            modifier = Modifier
                .height(NumberPadHeight)
                .background(Colors.Black),
        )
    }
}

@Composable
private fun ConfirmContent(
    pin: String,
    showError: Boolean,
    onKeyPress: (String) -> Unit,
    onBackClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .gradientBackground()
            .navigationBarsPadding()
            .testTag("ChangePIN2"),
    ) {
        SheetTopBar(
            titleText = stringResource(R.string.security__cp_retype_title),
            onBack = onBackClick,
        )

        VerticalSpacer(16.dp)

        BodyM(
            text = stringResource(R.string.security__cp_retype_text),
            color = Colors.White64,
            modifier = Modifier.padding(horizontal = 32.dp),
        )

        VerticalSpacer(32.dp)

        AnimatedVisibility(visible = showError) {
            BodyS(
                text = stringResource(R.string.security__cp_try_again),
                textAlign = TextAlign.Center,
                color = Colors.Brand,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("WrongPIN"),
            )
        }

        FillHeight()

        PinDots(pin = pin)

        VerticalSpacer(32.dp)

        NumberPad(
            onPress = onKeyPress,
            type = NumberPadType.SIMPLE,
            modifier = Modifier
                .height(NumberPadHeight)
                .background(Colors.Black),
        )
    }
}

@Composable
private fun ResultContent(
    onOkClick: () -> Unit,
    onBackClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .gradientBackground()
            .navigationBarsPadding(),
    ) {
        SheetTopBar(
            titleText = stringResource(R.string.security__cp_changed_title)
        )

        VerticalSpacer(16.dp)

        BodyM(
            text = stringResource(R.string.security__cp_changed_text),
            color = Colors.White64,
            modifier = Modifier.padding(horizontal = 32.dp),
        )

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Image(
                painter = painterResource(R.drawable.check),
                contentDescription = null,
                modifier = Modifier.size(256.dp),
            )
        }

        PrimaryButton(
            text = stringResource(R.string.common__ok),
            onClick = onOkClick,
            modifier = Modifier
                .padding(horizontal = 32.dp)
                .testTag("OK"),
        )

        VerticalSpacer(16.dp)
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

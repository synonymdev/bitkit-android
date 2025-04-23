package to.bitkit.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.rememberBiometricAuthSupported
import to.bitkit.viewmodels.AppViewModel

@Composable
fun AuthCheckView(
    showLogoOnPin: Boolean = false,
    appViewModel: AppViewModel,
    isBiometrySupported: Boolean = rememberBiometricAuthSupported(),
    requireBiometrics: Boolean = false,
    requirePin: Boolean = false,
    onSuccess: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    val isBiometricsEnabled by appViewModel.isBiometricEnabled.collectAsStateWithLifecycle()
    val attemptsRemaining by appViewModel.pinAttemptsRemaining.collectAsStateWithLifecycle()

    AuthCheckViewContent(
        isBiometricsEnabled = isBiometricsEnabled,
        isBiometrySupported = isBiometrySupported,
        showLogoOnPin = showLogoOnPin,
        attemptsRemaining = attemptsRemaining,
        requireBiometrics = requireBiometrics,
        requirePin = requirePin,
        validatePin = appViewModel::validatePin,
        onSuccess = onSuccess,
        onBack = onBack,
        onClickForgotPin = { appViewModel.toast(Exception("TODO: Forgot PIN")) },
    )
}

@Composable
private fun AuthCheckViewContent(
    isBiometricsEnabled: Boolean,
    isBiometrySupported: Boolean,
    showLogoOnPin: Boolean,
    attemptsRemaining: Int,
    requireBiometrics: Boolean = false,
    requirePin: Boolean = false,
    validatePin: (String) -> Boolean,
    onSuccess: (() -> Unit)? = null,
    onBack: (() -> Unit)?,
    onClickForgotPin: () -> Unit,
) {
    var showBio by rememberSaveable { mutableStateOf(isBiometricsEnabled) }

    LaunchedEffect(isBiometricsEnabled) {
        showBio = isBiometricsEnabled
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) {
        if ((showBio && isBiometrySupported && !requirePin) || requireBiometrics) {
            BiometricsView(
                onSuccess = { onSuccess?.invoke() },
                onFailure = { showBio = false },
            )
        } else {
            PinPad(
                showLogo = showLogoOnPin,
                validatePin = validatePin,
                attemptsRemaining = attemptsRemaining,
                allowBiometrics = isBiometricsEnabled && isBiometrySupported && !requirePin,
                onShowBiometrics = { showBio = true },
                onSuccess = onSuccess,
                onBack = onBack,
                onClickForgotPin = onClickForgotPin,
            )
        }
    }
}

@Composable
private fun PinPad(
    showLogo: Boolean = false,
    validatePin: (String) -> Boolean,
    attemptsRemaining: Int,
    allowBiometrics: Boolean,
    onShowBiometrics: () -> Unit,
    onSuccess: (() -> Unit)?,
    onBack: (() -> Unit)? = null,
    onClickForgotPin: () -> Unit = {},
) {
    var pin by remember { mutableStateOf("") }
    val isLastAttempt = attemptsRemaining == 1

    LaunchedEffect(pin) {
        if (pin.length == Env.PIN_LENGTH) {
            if (validatePin(pin)) {
                onSuccess?.invoke()
            }
            pin = ""
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppTopBar(titleText = " ", onBackClick = onBack)
        Box(
            contentAlignment = Alignment.BottomCenter,
            modifier = Modifier.weight(1f)
        ) {
            if (showLogo) {
                Image(
                    painter = painterResource(R.drawable.bitkit_logo),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(280.dp)
                )
            }
        }
        Subtitle(text = stringResource(R.string.security__pin_enter), modifier = Modifier.padding(bottom = 32.dp))

        if (attemptsRemaining < Env.PIN_ATTEMPTS) {
            if (isLastAttempt) {
                BodyS(
                    text = stringResource(R.string.security__pin_last_attempt),
                    color = Colors.Brand,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            } else {
                BodyS(
                    text = stringResource(R.string.security__pin_attempts).replace("{attemptsRemaining}", "$attemptsRemaining"),
                    color = Colors.Brand,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.clickableAlpha { onClickForgotPin() }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (allowBiometrics) {
            val biometricsName = stringResource(R.string.security__bio)
            PrimaryButton(
                text = stringResource(R.string.security__pin_use_biometrics).replace("{biometricsName}", biometricsName),
                onClick = onShowBiometrics,
                fullWidth = false,
                size = ButtonSize.Small,
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_fingerprint),
                        contentDescription = null,
                        tint = Colors.Brand,
                        modifier = Modifier.size(16.dp)
                    )
                },
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        PinDots(
            pin = pin,
            modifier = Modifier.padding(vertical = 16.dp),
        )
        PinNumberPad(
            modifier = Modifier.height(310.dp),
            onPress = { key ->
                if (key == KEY_DELETE) {
                    if (pin.isNotEmpty()) {
                        pin = pin.dropLast(1)
                    }
                } else if (pin.length < Env.PIN_LENGTH) {
                    pin += key
                }
            },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewBio() {
    AppThemeSurface {
        AuthCheckViewContent(
            onSuccess = {},
            onBack = {},
            isBiometricsEnabled = true,
            isBiometrySupported = true,
            showLogoOnPin = true,
            validatePin = { true },
            attemptsRemaining = 8,
            onClickForgotPin = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewPin() {
    AppThemeSurface {
        AuthCheckViewContent(
            onSuccess = {},
            onBack = {},
            isBiometricsEnabled = false,
            isBiometrySupported = true,
            showLogoOnPin = true,
            validatePin = { true },
            attemptsRemaining = 8,
            onClickForgotPin = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewPinAttempts() {
    AppThemeSurface {
        AuthCheckViewContent(
            onSuccess = {},
            onBack = null,
            isBiometricsEnabled = false,
            isBiometrySupported = true,
            showLogoOnPin = false,
            validatePin = { true },
            attemptsRemaining = 6,
            onClickForgotPin = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewPinAttemptLast() {
    AppThemeSurface {
        AuthCheckViewContent(
            onSuccess = {},
            onBack = {},
            isBiometricsEnabled = false,
            isBiometrySupported = true,
            showLogoOnPin = true,
            validatePin = { true },
            attemptsRemaining = 1,
            onClickForgotPin = {},
        )
    }
}

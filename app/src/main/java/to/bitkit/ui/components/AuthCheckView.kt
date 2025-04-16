package to.bitkit.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.rememberBiometricAuthSupported
import to.bitkit.viewmodels.AppViewModel

private const val PIN_LENGTH = 4

@Composable
fun AuthCheckView(
    onSuccess: (() -> Unit)? = null,
    isBiometrySupported: Boolean = rememberBiometricAuthSupported(),
    showLogoOnPin: Boolean,
    appViewModel: AppViewModel,
) {
    val isBiometricsEnabled by appViewModel.isBiometricEnabled.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        appViewModel.initTestPin()
    }

    AuthCheckViewContent(
        onSuccess = onSuccess,
        isBiometricsEnabled = isBiometricsEnabled,
        isBiometrySupported = isBiometrySupported,
        showLogoOnPin = showLogoOnPin,
        validatePin = appViewModel::validatePin,
    )
}

@Composable
private fun AuthCheckViewContent(
    onSuccess: (() -> Unit)? = null,
    isBiometricsEnabled: Boolean,
    isBiometrySupported: Boolean,
    showLogoOnPin: Boolean,
    validatePin: (String) -> Boolean,
) {
    var showBio by rememberSaveable { mutableStateOf(isBiometricsEnabled) }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) {
        Column {
            if (showBio && isBiometrySupported) {
                BiometricsView(
                    onSuccess = { onSuccess?.invoke() },
                    onFailure = { showBio = false },
                )
            } else {
                PinPad(
                    showLogo = showLogoOnPin,
                    validatePin = validatePin,
                    onSuccess = onSuccess,
                )
            }
        }
    }
}

@Composable
private fun PinPad(
    showLogo: Boolean = false,
    validatePin: (String) -> Boolean,
    onSuccess: (() -> Unit)?,
) {
    var pin by remember { mutableStateOf("") }

    LaunchedEffect(pin) {
        if (pin.length == PIN_LENGTH) {
            if (validatePin(pin)) {
                onSuccess?.invoke()
            }
            pin = ""
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            contentAlignment = Alignment.BottomCenter,
            modifier = Modifier.weight(1f)
        ) {
            if (showLogo) {
                Image(
                    painter = painterResource(R.drawable.bitkit_logo),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(256.dp)
                )
            }
        }
        Subtitle(text = stringResource(R.string.security__pin_enter))
        PinDots(
            pin = pin,
            modifier = Modifier.padding(vertical = 32.dp),
        )
        PinNumberPad(
            modifier = Modifier.height(310.dp),
            onPress = { key ->
                if (key == KEY_DELETE) {
                    if (pin.isNotEmpty()) {
                        pin = pin.dropLast(1)
                    }
                } else if (pin.length < PIN_LENGTH) {
                    pin += key
                }
            },
        )
    }
}


@Composable
private fun PinDots(
    pin: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
    ) {
        repeat(PIN_LENGTH) { index ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .border(1.dp, Colors.Brand, CircleShape)
                    .background(if (index < pin.length) Colors.Brand else Colors.Brand08)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewBio() {
    AppThemeSurface {
        AuthCheckViewContent(
            onSuccess = {},
            isBiometricsEnabled = true,
            isBiometrySupported = true,
            showLogoOnPin = true,
            validatePin = { true },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewPin() {
    AppThemeSurface {
        AuthCheckViewContent(
            onSuccess = {},
            isBiometricsEnabled = false,
            isBiometrySupported = true,
            showLogoOnPin = true,
            validatePin = { true },
        )
    }
}

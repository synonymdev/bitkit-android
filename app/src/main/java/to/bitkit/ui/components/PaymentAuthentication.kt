package to.bitkit.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import to.bitkit.R
import to.bitkit.ui.screens.wallets.send.SendPinCheckScreen
import to.bitkit.ui.utils.rememberBiometricAuthSupported

@Composable
internal fun PaymentAuthentication(
    useBiometrics: Boolean,
    onCancel: () -> Unit,
    onVerify: () -> Unit,
    modifier: Modifier = Modifier,
    @StringRes pinDescriptionRes: Int = R.string.security__pin_send,
) {
    val supported = rememberBiometricAuthSupported()
    var fallbackToPin by remember { mutableStateOf(false) }
    Box(propagateMinConstraints = true, modifier = modifier) {
        if (useBiometrics && supported && !fallbackToPin) {
            BiometricsView(onSuccess = onVerify, onFailure = { fallbackToPin = true })
        } else {
            SendPinCheckScreen(onBack = onCancel, onSuccess = onVerify, descriptionRes = pinDescriptionRes)
        }
    }
}

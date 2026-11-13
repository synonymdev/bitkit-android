package to.bitkit.ui.components

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import to.bitkit.ui.shared.util.blockPointerInputPassthrough
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.Colors

/**
 * Covers the parent box with the same PIN or biometric check as Send. Back cancels the check: on the PIN screen its
 * own handler takes it first, so this one only answers while the biometric prompt is shown.
 */
@Composable
fun BoxScope.PaymentAuthorizationOverlay(
    useBiometrics: Boolean,
    @StringRes pinDescriptionRes: Int,
    onCancel: () -> Unit,
    onVerify: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onCancel)
    Box(
        modifier = modifier
            .matchParentSize()
            .gradientBackground(startColor = Colors.Gray6, endColor = Colors.Black)
            .blockPointerInputPassthrough()
    ) {
        PaymentAuthentication(
            useBiometrics = useBiometrics,
            onCancel = onCancel,
            onVerify = onVerify,
            pinDescriptionRes = pinDescriptionRes,
        )
    }
}

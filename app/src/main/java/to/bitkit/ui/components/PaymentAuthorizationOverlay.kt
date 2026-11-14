package to.bitkit.ui.components

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import to.bitkit.ui.shared.util.blockPointerInputPassthrough
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.Colors

/**
 * Draws [content] with the payment check over it while one is open, [useBiometrics] being null when none is. The
 * check hides [content] from accessibility services while open, and one left open is cancelled when this host leaves.
 */
@Composable
fun PaymentAuthorizationHost(
    useBiometrics: Boolean?,
    @StringRes pinDescriptionRes: Int,
    onCancel: () -> Unit,
    onVerify: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val currentOnCancel by rememberUpdatedState(onCancel)
    DisposableEffect(Unit) {
        onDispose { currentOnCancel() }
    }
    Box(modifier = modifier) {
        Box(modifier = if (useBiometrics == null) Modifier else Modifier.clearAndSetSemantics {}) {
            content()
        }
        useBiometrics?.let {
            PaymentAuthorizationOverlay(
                useBiometrics = it,
                pinDescriptionRes = pinDescriptionRes,
                onCancel = onCancel,
                onVerify = onVerify,
            )
        }
    }
}

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

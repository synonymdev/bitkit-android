package to.bitkit.ui.utils

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import to.bitkit.R
import to.bitkit.utils.Logger

@Composable
fun BiometricPrompt(
    onSuccess: () -> Unit,
    onFailed: (() -> Unit)? = null,
    onError: (() -> Unit)? = null,
    onUnsupported: (() -> Unit)? = null,
) {
    val context = LocalContext.current

    val title = run {
        val name = stringResource(R.string.security__bio)
        stringResource(R.string.security__bio_confirm).replace("{biometricsName}", name)
    }

    LaunchedEffect(Unit) {
        verifyBiometric(
            activity = context,
            title = title,
            onAuthSucceeded = {
                Logger.debug("Biometric auth succeeded")
                onSuccess()
            },
            onAuthFailed = {
                Logger.debug(" Biometric auth failed")
                onFailed?.invoke()
            },
            onAuthError = { errorCode, errorMessage ->
                Logger.debug("Biometric auth error: code = '$errorCode', message = '$errorMessage'")
                onError?.invoke()
            },
            onUnsupported = {
                Logger.debug("Biometric auth unsupported")
                onUnsupported?.invoke()
            }
        )
    }
}

fun verifyBiometric(
    activity: Context,
    title: String,
    onAuthSucceeded: () -> Unit,
    onAuthFailed: (() -> Unit),
    onAuthError: ((errorCode: Int, errString: CharSequence) -> Unit),
    onUnsupported: () -> Unit,
) {
    if (isBiometricAuthSupported(activity)) {
        launchBiometricPrompt(
            activity = activity,
            title = title,
            onAuthSucceed = onAuthSucceeded,
            onAuthFailed = onAuthFailed,
            onAuthError = onAuthError,
        )
    } else {
        onUnsupported()
    }
}

fun isBiometricAuthSupported(context: Context): Boolean {
    val biometricManager = BiometricManager.from(context)
    return when (
        biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        )
    ) {
        BiometricManager.BIOMETRIC_SUCCESS -> true
        else -> false
    }
}

@Composable
fun rememberBiometricAuthSupported(context: Context = LocalContext.current): Boolean {
    return remember(context) { isBiometricAuthSupported(context) }
}

private fun launchBiometricPrompt(
    activity: Context,
    title: String,
    onAuthSucceed: () -> Unit,
    onAuthFailed: (() -> Unit),
    onAuthError: ((errorCode: Int, errString: CharSequence) -> Unit),
) {
    val executor = ContextCompat.getMainExecutor(activity)

    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setDescription(null)
        .setConfirmationRequired(true)
        .setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        )
        .build()

    val biometricPrompt = BiometricPrompt(
        activity as FragmentActivity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                // TODO check result.cryptoObject
                onAuthSucceed()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onAuthError(errorCode, errString)
            }

            override fun onAuthenticationFailed() {
                onAuthFailed()
            }
        },
    )

    // TODO Pass cryptoObject
    biometricPrompt.authenticate(promptInfo)
}

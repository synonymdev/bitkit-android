package to.bitkit.ui.utils

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import to.bitkit.R
import to.bitkit.utils.Logger

@Composable
fun BiometricButton(
    onSuccess: (() -> Unit)? = null,
    onFailure: (() -> Unit)? = null,
) {
    var isAuthenticated by rememberSaveable { mutableStateOf(false) }

    var showBiometricPrompt by rememberSaveable { mutableStateOf(false) }
    if (showBiometricPrompt) {
        BiometricPrompt(
            onSuccess = {
                showBiometricPrompt = false
                isAuthenticated = true
                onSuccess?.invoke()
            },
            onDismiss = {
                showBiometricPrompt = false
            },
            onError = {
                onFailure?.invoke()
                showBiometricPrompt = false
            },
            onUnsupported = {
                showBiometricPrompt = false
            },
        )
    }

    if (!isAuthenticated) {
        Button(onClick = { showBiometricPrompt = true }) {
            Text("Show Biometric Prompt")
        }
    } else {
        Text("Biometric authenticated")
    }
}

@Composable
fun BiometricPrompt(
    onSuccess: () -> Unit,
    onDismiss: () -> Unit,
    onUnsupported: () -> Unit,
    onError: () -> Unit,
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
                Logger.debug("Biometric Auth Succeeded")
                onDismiss()
                onSuccess()
            },
            onAuthError = { errorCode, errorMessage ->
                Logger.debug("Biometric Auth Error: code = $errorCode, message = $errorMessage")
                onDismiss()
                onError()
            },
            onAuthFailed = {
                Logger.debug(" Biometric Auth Failed")
            },
            onUnsupported = {
                Logger.debug("Biometric Auth Unsupported")
                onUnsupported()
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

package to.bitkit.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.utils.BiometricPrompt
import to.bitkit.ui.utils.rememberBiometricAuthSupported

@Composable
fun AuthCheckView(
    onSuccess: (() -> Unit)? = null,
    isBiometrySupported: Boolean = rememberBiometricAuthSupported(),
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            var showBio by rememberSaveable { mutableStateOf(true) }

            if (showBio && isBiometrySupported) {
                BiometricPrompt(
                    onSuccess = { onSuccess?.invoke() },
                    onError = { showBio = false },
                )

                val subtitleText = let {
                    val biometricsName = stringResource(R.string.security__bio)
                    stringResource(R.string.security__bio_auth_with).replace("{biometricsName}", biometricsName)
                }
                Icon(
                    painter = painterResource(R.drawable.ic_fingerprint),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Subtitle(text = subtitleText)
            } else {
                Subtitle(text = "TODO: Pin code auth")
                PrimaryButton(
                    text = stringResource(R.string.common__skip),
                    onClick = { onSuccess?.invoke() },
                    fullWidth = false,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        }
    }
}

@Preview
@Composable
fun AuthCheckPreview() {
    AppThemeSurface {
        AuthCheckView(
            onSuccess = {},
            isBiometrySupported = true,
        )
    }
}

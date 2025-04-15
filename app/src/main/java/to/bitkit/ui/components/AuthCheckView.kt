package to.bitkit.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.ui.appViewModel
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.utils.rememberBiometricAuthSupported

@Composable
fun AuthCheckView(
    onSuccess: (() -> Unit)? = null,
    isBiometrySupported: Boolean = rememberBiometricAuthSupported(),
) {
    val app = appViewModel ?: return
    val isBiometricsEnabled by app.isBiometricEnabled.collectAsStateWithLifecycle()

    AuthCheckViewContent(
        onSuccess = onSuccess,
        isBiometricsEnabled = isBiometricsEnabled,
        isBiometrySupported = isBiometrySupported,
    )
}

@Composable
private fun AuthCheckViewContent(
    onSuccess: (() -> Unit)? = null,
    isBiometricsEnabled: Boolean,
    isBiometrySupported: Boolean,
) {
    var showBio by rememberSaveable { mutableStateOf(isBiometricsEnabled) }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {

            if (showBio && isBiometrySupported) {
                BiometricsView(
                    onSuccess = { onSuccess?.invoke() },
                    onFailure = { showBio = false },
                )
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

@Preview(showBackground = true)
@Composable
private fun PreviewBio() {
    AppThemeSurface {
        AuthCheckViewContent(
            onSuccess = {},
            isBiometricsEnabled = true,
            isBiometrySupported = true,
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
        )
    }
}

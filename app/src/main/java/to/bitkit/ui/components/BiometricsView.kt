package to.bitkit.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.utils.BiometricPrompt

@Composable
fun BiometricsView(
    onSuccess: (() -> Unit)? = null,
    onFailure: (() -> Unit)? = null,
) {
    var shouldShowPrompt by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .clickableAlpha {
                // trick to show biometric prompt again on UI click
                scope.launch {
                    shouldShowPrompt = false
                    delay(5)
                    shouldShowPrompt = true
                }
            }
    ) {
        if (shouldShowPrompt) {
            BiometricPrompt(
                onSuccess = { onSuccess?.invoke() },
                onError = { onFailure?.invoke() },
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_fingerprint),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Subtitle(
            text = run {
                val biometricsName = stringResource(R.string.security__bio)
                stringResource(R.string.security__bio_auth_with).replace("{biometricsName}", biometricsName)
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BiometricsView()
    }
}

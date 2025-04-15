package to.bitkit.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.utils.BiometricPrompt

@Composable
fun BiometricsView(
    onSuccess: (() -> Unit)? = null,
    onFailure: (() -> Unit)? = null,
) {
    BiometricPrompt(
        onSuccess = { onSuccess?.invoke() },
        onError = { onFailure?.invoke() },
    )
    Icon(
        painter = painterResource(R.drawable.ic_fingerprint),
        contentDescription = null,
        modifier = Modifier.Companion.size(64.dp),
    )
    Spacer(modifier = Modifier.Companion.height(16.dp))
    Subtitle(
        text = run {
            val biometricsName = stringResource(R.string.security__bio)
            stringResource(R.string.security__bio_auth_with).replace("{biometricsName}", biometricsName)
        }
    )
}

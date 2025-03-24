 package to.bitkit.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.utils.isBiometricAuthSupported
import kotlin.let

@Composable
fun AuthCheckView(
    isBiometricAvailable: Boolean = isBiometricAuthSupported(LocalContext.current),
) {
    // TODO only if biometrics enabled?
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        if (isBiometricAvailable) {
            val subtitleText = let {
                val biometricsName = stringResource(R.string.security__bio)
                stringResource(R.string.security__bio_auth_with).replace("{biometricsName}", biometricsName)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_fingerprint),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Subtitle(text = subtitleText)
            }
        }
    }
}

@Preview
@Composable
fun AuthCheckPreview() {
    AppThemeSurface {
        AuthCheckView(
            isBiometricAvailable = true,
        )
    }
}

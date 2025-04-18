package to.bitkit.ui.settings.pin

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppSwitchDefaults
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.BiometricPrompt
import to.bitkit.ui.utils.rememberBiometricAuthSupported

@Composable
fun AskForBiometricsScreen(
    onContinue: () -> Unit,
    onSkip: () -> Unit,
) {
    val app = appViewModel ?: return
    val isBiometrySupported = rememberBiometricAuthSupported()
    var showBiometricPrompt by remember { mutableStateOf(false) }

    AskForBiometricsContent(
        isBiometrySupported = isBiometrySupported,
        onSkip = onSkip,
        onContinue = { shouldEnableBiometrics ->
            if (shouldEnableBiometrics) {
                showBiometricPrompt = true
            } else {
                onContinue()
            }
        },
    )

    if (showBiometricPrompt) {
        BiometricPrompt(
            onSuccess = {
                app.setIsBiometricEnabled(true)
                onContinue()
            },
            onError = {
                showBiometricPrompt = false
            },
            cancelButtonText = stringResource(R.string.common__cancel),
        )
    }
}

@Composable
private fun AskForBiometricsContent(
    isBiometrySupported: Boolean,
    onContinue: (Boolean) -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
            .padding(horizontal = 16.dp)
            .navigationBarsPadding()
    ) {
        SheetTopBar(stringResource(R.string.security__bio))

        Spacer(modifier = Modifier.height(16.dp))

        if (!isBiometrySupported) {
            BioNotAvailableView(onSkip = onSkip)
        } else {
            var shouldEnableBiometrics by remember { mutableStateOf(false) }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .weight(1f)
            ) {
                BodyM(
                    text = run {
                        val biometricsName = stringResource(R.string.security__bio)
                        stringResource(R.string.security__bio_ask).replace("{biometricsName}", biometricsName)
                    },
                    color = Colors.White64,
                )

                Spacer(modifier = Modifier.weight(1f))

                Icon(
                    painter = painterResource(R.drawable.ic_touch_id),
                    contentDescription = null,
                    tint = Colors.Brand,
                    modifier = Modifier.size(134.dp),
                )

                Spacer(modifier = Modifier.weight(1f))

                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickableAlpha { shouldEnableBiometrics = !shouldEnableBiometrics }
                ) {
                    BodyMSB(
                        text = run {
                            val biometricsName = stringResource(R.string.security__bio)
                            stringResource(R.string.security__bio_use).replace("{biometricsName}", biometricsName)
                        },
                    )

                    Switch(
                        checked = shouldEnableBiometrics,
                        onCheckedChange = null, // handled by parent
                        colors = AppSwitchDefaults.colors,
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                PrimaryButton(
                    text = stringResource(R.string.common__continue),
                    onClick = {
                        onContinue(shouldEnableBiometrics)
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun ColumnScope.BioNotAvailableView(
    onSkip: () -> Unit,
) {
    val context = LocalContext.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .weight(1f)
    ) {
        BodyM(
            text = stringResource(R.string.security__bio_not_available),
            color = Colors.White64,
        )

        Spacer(modifier = Modifier.weight(1f))

        Image(
            painter = painterResource(R.drawable.cog),
            contentDescription = null,
            modifier = Modifier
                .size(256.dp)
                .aspectRatio(1f),
        )

        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SecondaryButton(
                text = stringResource(R.string.common__skip),
                onClick = onSkip,
                modifier = Modifier.weight(1f),
            )

            PrimaryButton(
                text = stringResource(R.string.security__bio_phone_settings),
                onClick = {
                    val intent = Intent(Settings.ACTION_SETTINGS)
                    context.startActivity(intent)
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        AskForBiometricsContent(
            isBiometrySupported = true,
            onContinue = {},
            onSkip = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewNoBio() {
    AppThemeSurface {
        AskForBiometricsContent(
            isBiometrySupported = false,
            onContinue = {},
            onSkip = {},
        )
    }
}

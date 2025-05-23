package to.bitkit.ui.settings.pin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.settingsViewModel
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppSwitchDefaults
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun PinResultScreen(
    isBioOn: Boolean,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
) {
    val settings = settingsViewModel ?: return
    val pinForPayments by settings.isPinForPaymentsEnabled.collectAsStateWithLifecycle()

    BackHandler { onBack() }

    PinResultContent(
        bio = isBioOn,
        pinForPayments = pinForPayments,
        onTogglePinForPayments = { settings.setIsPinForPaymentsEnabled(!pinForPayments) },
        onContinueClick = onDismiss,
    )
}

@Composable
private fun PinResultContent(
    bio: Boolean,
    pinForPayments: Boolean,
    onTogglePinForPayments: () -> Unit,
    onContinueClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
            .padding(horizontal = 16.dp)
            .navigationBarsPadding()
    ) {
        SheetTopBar(stringResource(R.string.security__success_title))

        Spacer(modifier = Modifier.height(16.dp))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .weight(1f)
        ) {
            BodyM(
                text = if (bio) {
                    stringResource(R.string.security__success_bio)
                        .replace("{biometricsName}", stringResource(R.string.security__bio))
                } else {
                    stringResource(R.string.security__success_no_bio)
                },
                color = Colors.White64,
            )

            Spacer(modifier = Modifier.weight(1f))

            Image(
                painter = painterResource(R.drawable.check),
                contentDescription = null,
                modifier = Modifier
                    .size(256.dp)
            )

            Spacer(modifier = Modifier.weight(1f))

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickableAlpha { onTogglePinForPayments() }
            ) {
                BodyMSB(text = stringResource(R.string.security__success_payments))
                Switch(
                    checked = pinForPayments,
                    onCheckedChange = null, // handled by parent
                    colors = AppSwitchDefaults.colors,
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            PrimaryButton(
                text = stringResource(R.string.common__ok),
                onClick = onContinueClick,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        PinResultContent(
            bio = true,
            pinForPayments = true,
            onTogglePinForPayments = {},
            onContinueClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewNoBio() {
    AppThemeSurface {
        PinResultContent(
            bio = false,
            pinForPayments = false,
            onTogglePinForPayments = {},
            onContinueClick = {},
        )
    }
}

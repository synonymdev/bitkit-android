package to.bitkit.ui.screens.wallets.receive

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMB
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.ChannelStatusUi
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.LightningChannel
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.settings.SettingsSwitchRow
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppSwitchDefaults
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.RequestNotificationPermissions
import to.bitkit.viewmodels.SettingsViewModel
import kotlin.math.round

@Composable
fun ReceiveLiquidityScreen(
    entry: CjitEntryDetails,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    onSwitchClick: () -> Unit,
    hasNotificationPermission: Boolean,
    modifier: Modifier = Modifier,
    isAdditional: Boolean = false,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    RequestNotificationPermissions(
        onPermissionChange = { granted ->
            settingsViewModel.setNotificationPreference(granted)
        },
        showPermissionDialog = false
    )

    Content(
        entry = entry,
        onContinue = onContinue,
        onBack = onBack,
        onSwitchClick = onSwitchClick,
        hasNotificationPermission = hasNotificationPermission,
        modifier = modifier,
        isAdditional = isAdditional
    )
}

@Composable
private fun Content(
    entry: CjitEntryDetails,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    onSwitchClick: () -> Unit,
    hasNotificationPermission: Boolean,
    modifier: Modifier = Modifier,
    isAdditional: Boolean = false,
) {
    val channelSize = entry.channelSizeSat
    val localBalance = entry.receiveAmountSats - entry.feeSat

    val remoteBalance = remember(entry) {
        val remoteReserve = channelSize / 100.0
        round(channelSize - localBalance - remoteReserve).toLong()
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .gradientBackground()
            .navigationBarsPadding()
    ) {
        SheetTopBar(
            stringResource(
                if (isAdditional) R.string.wallet__receive_liquidity__nav_title_additional else R.string.wallet__receive_liquidity__nav_title
            ),
            onBack = onBack
        )
        Spacer(Modifier.height(24.dp))

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .weight(1f)
        ) {
            BodyM(
                text = stringResource(
                    if (isAdditional) R.string.wallet__receive_liquidity__text_additional else R.string.wallet__receive_liquidity__text
                ),
                color = Colors.White64
            )

            VerticalSpacer(32.dp)

            BodyMB(
                text = stringResource(
                    if (isAdditional) R.string.wallet__receive_liquidity__label_additional else R.string.wallet__receive_liquidity__label
                )
            )
            Spacer(modifier = Modifier.height(16.dp))

            LightningChannel(
                capacity = channelSize,
                localBalance = localBalance,
                remoteBalance = remoteBalance,
                status = ChannelStatusUi.OPEN,
                showLabels = true,
            )

            Spacer(modifier = Modifier.height(32.dp))

            FillHeight()

            BodyM(
                text = "Enable background setup to safely exit Bitkit while your balance is being configured.",
                color = Colors.White64
            )

            VerticalSpacer(15.dp)

            SettingsSwitchRow(
                title = "Set up in background",
                isChecked = hasNotificationPermission,
                colors = AppSwitchDefaults.colorsPurple,
                onClick = onSwitchClick,
                modifier = Modifier.fillMaxWidth()
            )

            VerticalSpacer(22.dp)

            PrimaryButton(
                text = stringResource(R.string.common__understood),
                onClick = onContinue,
                modifier = Modifier.testTag("LiquidityContinue")
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Preview(showSystemUi = true, name = "Initial flow")
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                entry = CjitEntryDetails(
                    channelSizeSat = 200_000L,
                    receiveAmountSats = 50_000L,
                    feeSat = 10_000L,
                    networkFeeSat = 5_000L,
                    serviceFeeSat = 150_000L,
                    invoice = "",
                ),
                isAdditional = false,
                onContinue = {},
                onBack = {},
                onSwitchClick = {},
                hasNotificationPermission = true,
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}

@Preview(showSystemUi = true, name = "Additional flow")
@Composable
private fun Preview2() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                entry = CjitEntryDetails(
                    channelSizeSat = 200_000L,
                    receiveAmountSats = 50_000L,
                    feeSat = 10_000L,
                    networkFeeSat = 5_000L,
                    serviceFeeSat = 150_000L,
                    invoice = "",
                ),
                isAdditional = true,
                onContinue = {},
                onBack = {},
                onSwitchClick = {},
                hasNotificationPermission = false,
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}

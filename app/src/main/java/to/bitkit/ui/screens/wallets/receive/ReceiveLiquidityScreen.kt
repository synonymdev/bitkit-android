package to.bitkit.ui.screens.wallets.receive

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMB
import to.bitkit.ui.components.ChannelStatusUi
import to.bitkit.ui.components.LightningChannel
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import kotlin.math.round

@Composable
fun ReceiveLiquidityScreen(
    entry: CjitEntryDetails,
    isAdditional: Boolean = false,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    val channelSize = entry.channelSizeSat
    val localBalance = entry.receiveAmountSats - entry.feeSat

    val remoteBalance = remember(entry) {
        val remoteReserve = channelSize / 100.0
        round(channelSize - localBalance - remoteReserve).toLong()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .gradientBackground()
            .navigationBarsPadding()
    ) {
        SheetTopBar(
            stringResource(if (isAdditional) R.string.wallet__receive_liquidity__nav_title_additional else R.string.wallet__receive_liquidity__nav_title),
            onBack = onBack
        )
        Spacer(Modifier.height(24.dp))

        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            BodyM(
                text = stringResource(if (isAdditional) R.string.wallet__receive_liquidity__text_additional else R.string.wallet__receive_liquidity__text),
                color = Colors.White64
            )

            Spacer(modifier = Modifier.weight(1f))

            BodyMB(text = stringResource(if (isAdditional) R.string.wallet__receive_liquidity__label_additional else R.string.wallet__receive_liquidity__label))
            Spacer(modifier = Modifier.height(16.dp))

            LightningChannel(
                capacity = channelSize,
                localBalance = localBalance,
                remoteBalance = remoteBalance,
                status = ChannelStatusUi.OPEN,
                showLabels = true,
            )

            Spacer(modifier = Modifier.height(32.dp))
            PrimaryButton(
                text = stringResource(R.string.common__understood),
                onClick = onContinue,
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Preview(showSystemUi = true, name = "Initial flow")
@Composable
private fun Preview() {
    AppThemeSurface {
        ReceiveLiquidityScreen(
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
        )
    }
}

@Preview(showSystemUi = true, name = "Additional flow")
@Composable
private fun Preview2() {
    AppThemeSurface {
        ReceiveLiquidityScreen(
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
        )
    }
}

package to.bitkit.ui.screens.transfer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMB
import to.bitkit.ui.components.ChannelStatusUi
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.LightningChannel
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.transferViewModel
import to.bitkit.ui.utils.withAccent

@Composable
fun LiquidityScreen(
    onContinueClick: () -> Unit = {},
    onBackClick: () -> Unit = {},
    onCloseClick: () -> Unit = {},
) {
    val transfer = transferViewModel ?: return
    val state by transfer.spendingUiState.collectAsStateWithLifecycle()
    val order = state.order ?: return

    val channelSize = (order.clientBalanceSat + order.lspBalanceSat).toLong()
    val localBalance = order.clientBalanceSat.toLong()
    val remoteBalance = channelSize - localBalance

    LiquidityScreen(
        channelSize = channelSize,
        localBalance = localBalance,
        remoteBalance = remoteBalance,
        onBackClick = onBackClick,
        onCloseClick = onCloseClick,
        onContinueClick = onContinueClick,
    )
}

@Composable
private fun LiquidityScreen(
    channelSize: Long,
    localBalance: Long,
    remoteBalance: Long,
    onBackClick: () -> Unit = {},
    onCloseClick: () -> Unit = {},
    onContinueClick: () -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__transfer__nav_title),
            onBackClick = onBackClick,
            actions = {
                IconButton(onClick = onCloseClick) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.common__close),
                    )
                }
            },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(32.dp))
            Display(text = stringResource(R.string.lightning__liquidity__title).withAccent(accentColor = Colors.Purple))
            Spacer(modifier = Modifier.height(8.dp))
            BodyM(text = stringResource(R.string.lightning__liquidity__text), color = Colors.White64)

            Spacer(modifier = Modifier.weight(1f))

            BodyMB(text = stringResource(R.string.lightning__liquidity__label))
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
                onClick = onContinueClick,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun LiquidityScreenPreview() {
    AppThemeSurface {
        val channelSize = 200_000L
        val localBalance = 50_000L
        val remoteBalance = channelSize - localBalance

        LiquidityScreen(
            channelSize = channelSize,
            localBalance = localBalance,
            remoteBalance = remoteBalance,
        )
    }
}

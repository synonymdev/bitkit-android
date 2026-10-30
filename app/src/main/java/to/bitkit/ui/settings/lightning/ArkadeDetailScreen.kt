package to.bitkit.ui.settings.lightning

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.persistentListOf
import to.bitkit.R
import to.bitkit.ext.setClipboardText
import to.bitkit.ext.toLocalizedTimestamp
import to.bitkit.repositories.ArkadeDeposit
import to.bitkit.repositories.ArkadeWithdrawal
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.CaptionB
import to.bitkit.ui.components.ChannelStatusUi
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.LightningChannel
import to.bitkit.ui.components.MoneyCaptionB
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.getBlockExplorerUrl

/** The Arkade wallet renews outputs this long before their batch expires. */
private const val RENEWAL_LEAD_SECONDS = 3L * 24 * 60 * 60

private const val MILLIS_PER_SECOND = 1_000L

@Composable
fun ArkadeDetailScreen(
    onBack: () -> Unit,
    onTransferToSavings: () -> Unit,
    viewModel: ArkadeDetailViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Content(
        uiState = uiState,
        onBack = onBack,
        onRefresh = viewModel::onPullToRefresh,
        onOpenTx = { txId -> context.startActivity(Intent(Intent.ACTION_VIEW, getBlockExplorerUrl(txId).toUri())) },
        onCopyText = { context.setClipboardText(it) },
        onTransferToSavings = onTransferToSavings,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Content(
    uiState: ArkadeDetailUiState,
    onBack: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onOpenTx: (String) -> Unit = {},
    onCopyText: (String) -> Unit = {},
    onTransferToSavings: () -> Unit = {},
) {
    val arkade = uiState.arkade ?: ArkadeUi(balanceSats = 0uL)
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__arkade),
            onBackClick = onBack,
            actions = { DrawerNavIcon() },
        )
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .testTag("ArkadeScrollView")
            ) {
                VerticalSpacer(16.dp)
                LightningChannel(
                    capacity = arkade.balanceSats.toLong(),
                    localBalance = arkade.balanceSats.toLong(),
                    remoteBalance = 0L,
                    status = ChannelStatusUi.OPEN,
                    showReceiving = false,
                )
                VerticalSpacer(32.dp)
                HorizontalDivider()

                SectionTitle(stringResource(R.string.lightning__arkade_provider))
                SectionRow(
                    name = stringResource(R.string.lightning__arkade_provider),
                    valueContent = { CaptionB(text = stringResource(R.string.lightning__arkade_provider_value)) },
                )
                VerticalSpacer(12.dp)
                BodyS(text = stringResource(R.string.lightning__arkade_trust), color = Colors.White64)

                uiState.depositAddress?.let { address ->
                    SectionRow(
                        name = stringResource(R.string.wallet__activity_address),
                        valueContent = {
                            CaptionB(
                                text = address,
                                maxLines = 1,
                                overflow = TextOverflow.MiddleEllipsis,
                                textAlign = TextAlign.End,
                            )
                        },
                        onClick = { onCopyText(address) },
                        modifier = Modifier.testTag("ArkadeDepositAddress"),
                    )
                }

                SectionTitle(stringResource(R.string.lightning__balance))
                SectionRow(
                    name = stringResource(R.string.lightning__spending_label),
                    valueContent = { MoneyCaptionB(sats = arkade.balanceSats.toLong(), symbol = true) },
                )

                arkade.lightningAddress?.let { address ->
                    SectionRow(
                        name = stringResource(R.string.lightning__arkade_lightning_address),
                        valueContent = {
                            CaptionB(text = address, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        },
                        onClick = { onCopyText(address) },
                        modifier = Modifier.testTag("ArkadeLightningAddress"),
                    )
                }
                arkade.nextExpiryAt?.let { expiresAt ->
                    // The wallet renews outputs 3 days before they expire.
                    val renewAt = remember(expiresAt) { (expiresAt - RENEWAL_LEAD_SECONDS) * MILLIS_PER_SECOND }
                    SectionRow(
                        name = stringResource(R.string.lightning__arkade_expiry),
                        valueContent = { CaptionB(text = renewAt.toLocalizedTimestamp()) },
                        modifier = Modifier.testTag("ArkadeNextRenewal"),
                    )
                }

                if (arkade.pendingDeposits.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.lightning__arkade_deposits_pending))
                    arkade.pendingDeposits.forEach { ArkadeDepositRow(it, onOpenTx) }
                }

                if (arkade.claimedDeposits.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.lightning__arkade_deposits_claimed))
                    arkade.claimedDeposits.forEach { ArkadeDepositRow(it, onOpenTx) }
                }

                if (arkade.withdrawals.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.lightning__savings_confirm__label))
                    arkade.withdrawals.forEach { withdrawal ->
                        ArkadeTransactionRow(
                            title = stringResource(R.string.wallet__activity_transfer_to_savings),
                            txid = withdrawal.txid,
                            amountSats = withdrawal.amountSats,
                            onOpenTx = onOpenTx,
                            modifier = Modifier.testTag("ArkadeWithdrawal")
                        )
                        SectionRow(
                            name = stringResource(R.string.wallet__activity_fee),
                            valueContent = { MoneyCaptionB(sats = withdrawal.feeSats.toLong(), symbol = true) },
                        )
                    }
                }

                FillHeight()
                VerticalSpacer(32.dp)
                PrimaryButton(
                    text = stringResource(R.string.wallet__transfer_to_savings),
                    onClick = onTransferToSavings,
                    enabled = arkade.balanceSats > 0uL,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ArkadeTransferToSavings")
                )
                VerticalSpacer(16.dp)
            }
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            uiState = ArkadeDetailUiState(
                arkade = ArkadeUi(
                    balanceSats = 35_000uL,
                    deposits = persistentListOf(
                        ArkadeDepositUi(ArkadeDeposit("6c8706b8a9cbd18f", 15_000uL, isClaimed = false), 1u),
                        ArkadeDepositUi(ArkadeDeposit("d0067b2497c7b905", 35_000uL, isClaimed = true, feeSats = 312uL)),
                    ),
                    withdrawals = persistentListOf(ArkadeWithdrawal("4f1d0c55e2a9b713", 4_917uL, feeSats = 1_470uL)),
                ),
            ),
        )
    }
}

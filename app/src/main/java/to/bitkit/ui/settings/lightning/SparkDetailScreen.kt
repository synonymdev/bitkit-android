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
import to.bitkit.repositories.SparkDeposit
import to.bitkit.repositories.SparkWithdrawal
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

@Composable
fun SparkDetailScreen(
    onBack: () -> Unit,
    onTransferToSavings: () -> Unit,
    viewModel: SparkDetailViewModel = hiltViewModel(),
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
    uiState: SparkDetailUiState,
    onBack: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onOpenTx: (String) -> Unit = {},
    onCopyText: (String) -> Unit = {},
    onTransferToSavings: () -> Unit = {},
) {
    val spark = uiState.spark ?: SparkUi(balanceSats = 0uL)
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__spark),
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
                    .testTag("SparkScrollView")
            ) {
                VerticalSpacer(16.dp)
                LightningChannel(
                    capacity = spark.balanceSats.toLong(),
                    localBalance = spark.balanceSats.toLong(),
                    remoteBalance = 0L,
                    status = ChannelStatusUi.OPEN,
                    showReceiving = false,
                )
                VerticalSpacer(32.dp)
                HorizontalDivider()

                SectionTitle(stringResource(R.string.lightning__spark_provider))
                SectionRow(
                    name = stringResource(R.string.lightning__spark_provider),
                    valueContent = { CaptionB(text = stringResource(R.string.lightning__spark_provider_value)) },
                )
                VerticalSpacer(12.dp)
                BodyS(text = stringResource(R.string.lightning__spark_trust), color = Colors.White64)

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
                        modifier = Modifier.testTag("SparkDepositAddress"),
                    )
                }

                SectionTitle(stringResource(R.string.lightning__balance))
                SectionRow(
                    name = stringResource(R.string.lightning__spending_label),
                    valueContent = { MoneyCaptionB(sats = spark.balanceSats.toLong(), symbol = true) },
                )

                if (spark.pendingDeposits.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.lightning__spark_deposits_pending))
                    spark.pendingDeposits.forEach { SparkDepositRow(it, onOpenTx) }
                }

                if (spark.claimedDeposits.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.lightning__spark_deposits_claimed))
                    spark.claimedDeposits.forEach { depositUi ->
                        SparkDepositRow(depositUi, onOpenTx)
                        SectionRow(
                            name = stringResource(R.string.lightning__spark_claim_fee),
                            valueContent = {
                                MoneyCaptionB(sats = depositUi.deposit.feeSats.toLong(), symbol = true)
                            },
                        )
                    }
                }

                if (spark.withdrawals.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.lightning__savings_confirm__label))
                    spark.withdrawals.forEach { withdrawal ->
                        SparkTransactionRow(
                            title = stringResource(R.string.wallet__activity_transfer_to_savings),
                            txid = withdrawal.txid,
                            amountSats = withdrawal.amountSats,
                            onOpenTx = onOpenTx,
                            modifier = Modifier.testTag("SparkWithdrawal")
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
                    enabled = spark.balanceSats > 0uL,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("SparkTransferToSavings")
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
            uiState = SparkDetailUiState(
                spark = SparkUi(
                    balanceSats = 35_000uL,
                    deposits = persistentListOf(
                        SparkDepositUi(SparkDeposit("6c8706b8a9cbd18f", 15_000uL, isClaimed = false), 1u),
                        SparkDepositUi(SparkDeposit("d0067b2497c7b905", 35_000uL, isClaimed = true, feeSats = 312uL)),
                    ),
                    withdrawals = persistentListOf(SparkWithdrawal("4f1d0c55e2a9b713", 4_917uL, feeSats = 1_470uL)),
                ),
            ),
        )
    }
}

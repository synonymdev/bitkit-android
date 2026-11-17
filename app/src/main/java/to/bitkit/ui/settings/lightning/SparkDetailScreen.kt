package to.bitkit.ui.settings.lightning

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
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
import to.bitkit.ext.ellipsisMiddle
import to.bitkit.ext.setClipboardText
import to.bitkit.models.HealthState
import to.bitkit.repositories.SparkDeposit
import to.bitkit.repositories.SparkExitEarlierAddress
import to.bitkit.repositories.SparkWithdrawal
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.CaptionB
import to.bitkit.ui.components.ChannelStatusUi
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.LightningChannel
import to.bitkit.ui.components.MoneyCaptionB
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.getBlockExplorerUrl

@Composable
fun SparkDetailScreen(
    onBack: () -> Unit,
    onTransferToSavings: () -> Unit,
    onEmergencyExit: (showsResult: Boolean) -> Unit,
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
        onEmergencyExit = { onEmergencyExit(uiState.spark?.showsExitResult == true) },
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
    onEmergencyExit: () -> Unit = {},
) {
    val spark = uiState.spark ?: SparkUi(balanceSats = 0uL)
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__spark_provider_value),
            onBackClick = onBack,
            actions = { DrawerNavIcon() },
        )
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f)
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .testTag("SparkScrollView")
            ) {
                VerticalSpacer(16.dp)
                LightningChannel(
                    capacity = spark.balanceSats.toLong(),
                    localBalance = spark.balanceSats.toLong(),
                    remoteBalance = 0L,
                    status = ChannelStatusUi.OPEN,
                    showReceiving = spark.isReceiving,
                    receivingUnlimited = spark.isReceiving,
                )
                VerticalSpacer(16.dp)
                HorizontalDivider()

                SectionTitle(stringResource(R.string.lightning__status))
                SparkStatusRow(uiState.status)
                HorizontalDivider()

                ProviderSection(depositAddress = uiState.depositAddress, onCopyText = onCopyText)

                SectionTitle(stringResource(R.string.lightning__balance))
                if (spark.isReceiving) {
                    SectionRow(
                        name = stringResource(R.string.lightning__receiving_label),
                        valueContent = {
                            CaptionB(text = stringResource(R.string.lightning__spark_receiving_unlimited))
                        },
                    )
                }
                SectionRow(
                    name = stringResource(R.string.lightning__spending_label),
                    valueContent = { MoneyCaptionB(sats = spark.balanceSats.toLong(), symbol = true) },
                )

                if (spark.deposits.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.lightning__spark_deposits))
                    HorizontalDivider()
                    spark.deposits.forEach { SparkDepositRows(it, onOpenTx) }
                }

                if (spark.earlierAddresses.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.lightning__spark_exit__earlier_addresses))
                    BodyS(
                        text = stringResource(R.string.lightning__spark_exit__earlier_addresses_text),
                        color = Colors.White64,
                    )
                    spark.earlierAddresses.forEach { EarlierExitAddressRows(it, onCopyText) }
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

                VerticalSpacer(32.dp)
            }
        }
        Buttons(
            spark = spark,
            onTransferToSavings = onTransferToSavings,
            onEmergencyExit = onEmergencyExit,
        )
    }
}

@Composable
private fun SparkStatusRow(status: HealthState) {
    val (text, color, background) = when (status) {
        HealthState.READY -> Triple(R.string.lightning__order_state__open, Colors.Green, Colors.Green16)
        HealthState.PENDING -> Triple(R.string.settings__status__spark__pending, Colors.Yellow, Colors.Yellow16)
        HealthState.ERROR -> Triple(R.string.settings__status__spark__error, Colors.Red, Colors.Red16)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(top = 8.dp, bottom = 16.dp)
            .testTag("SparkStatus")
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(32.dp)
                .background(background, CircleShape)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_lightning_alt),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp)
            )
        }
        HorizontalSpacer(16.dp)
        BodyMSB(text = stringResource(text), color = color)
    }
}

@Composable
private fun ProviderSection(depositAddress: String?, onCopyText: (String) -> Unit) {
    SectionTitle(stringResource(R.string.lightning__spark_provider))
    SectionRow(
        name = stringResource(R.string.lightning__spark),
        valueContent = {
            CaptionB(text = stringResource(R.string.lightning__spark_operators), textAlign = TextAlign.End)
        },
    )
    depositAddress?.let { address ->
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
    BodyM(
        text = stringResource(R.string.lightning__spark_trust),
        color = Colors.White50,
        modifier = Modifier.padding(vertical = 16.dp)
    )
    HorizontalDivider()
}

@Composable
internal fun SparkDepositRows(depositUi: SparkDepositUi, onOpenTx: (String) -> Unit) {
    val deposit = depositUi.deposit
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        CompactRow(
            name = stringResource(R.string.lightning__spark_deposit_tx),
            onClick = { onOpenTx(deposit.txid) },
            modifier = Modifier.testTag("SparkDeposit"),
        ) {
            CaptionB(
                text = deposit.txid.ellipsisMiddle(DEPOSIT_TXID_LENGTH),
                maxLines = 1,
                textAlign = TextAlign.End,
            )
        }
        CompactRow(name = stringResource(R.string.lightning__spark_deposit)) {
            MoneyCaptionB(sats = depositUi.grossSats.toLong(), symbol = true)
        }
        when {
            deposit.isClaimed -> CompactRow(name = stringResource(R.string.lightning__spark_claim_fee)) {
                MoneyCaptionB(sats = deposit.feeSats.toLong(), symbol = true)
            }

            else -> CompactRow(name = stringResource(R.string.lightning__status)) {
                CaptionB(
                    text = depositUi.confirmations?.takeIf { it > 0u }?.let {
                        stringResource(
                            R.string.lightning__spark_confirmations,
                            minOf(it.toInt(), SPARK_CLAIM_CONFIRMATIONS),
                        )
                    } ?: stringResource(R.string.wallet__activity_pending),
                    color = Colors.Purple,
                )
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun CompactRow(
    name: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    valueContent: @Composable () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            .clickableAlpha(onClick = onClick)
    ) {
        CaptionB(text = name, modifier = Modifier.weight(1f))
        Box(
            contentAlignment = Alignment.CenterEnd,
            modifier = Modifier.weight(1.5f),
        ) {
            valueContent()
        }
    }
}

@Composable
private fun Buttons(
    spark: SparkUi,
    onTransferToSavings: () -> Unit,
    onEmergencyExit: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp, bottom = 16.dp)
            .navigationBarsPadding()
    ) {
        PrimaryButton(
            text = stringResource(R.string.wallet__transfer_to_savings),
            onClick = onTransferToSavings,
            enabled = spark.balanceSats > 0uL && !spark.isExiting,
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_transfer),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("SparkTransferToSavings")
        )
        if (spark.showsExit) {
            SecondaryButton(
                text = stringResource(R.string.lightning__spark_exit__button),
                onClick = onEmergencyExit,
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_warning),
                        contentDescription = null,
                        tint = Colors.Yellow,
                        modifier = Modifier.size(16.dp)
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("SparkEmergencyExit")
            )
        }
    }
}

/** Confirmations after which Spark claims a deposit, shown as its "n/3 confirmations". */
private const val SPARK_CLAIM_CONFIRMATIONS = 3

/** A deposit's txid shown as its first and last 8 characters, as the design shows it. */
private const val DEPOSIT_TXID_LENGTH = 17

@Composable
private fun EarlierExitAddressRows(entry: SparkExitEarlierAddress, onCopyText: (String) -> Unit) {
    SectionRow(
        name = stringResource(R.string.wallet__activity_address),
        valueContent = {
            CaptionB(
                text = entry.address,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                textAlign = TextAlign.End,
            )
        },
        onClick = { onCopyText(entry.address) },
        modifier = Modifier.testTag("SparkEarlierExitAddress"),
    )
    SectionRow(
        name = stringResource(R.string.lightning__spark_exit__earlier_path),
        valueContent = { CaptionB(text = entry.path, textAlign = TextAlign.End) },
        onClick = { onCopyText(entry.path) },
    )
    entry.sats?.takeIf { it > 0uL }?.let { sats ->
        SectionRow(
            name = stringResource(R.string.lightning__balance),
            valueContent = { MoneyCaptionB(sats = sats.toLong(), symbol = true) },
        )
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
                    isReceiving = true,
                    deposits = persistentListOf(
                        SparkDepositUi(SparkDeposit("6c8706b8a9cbd18f", 15_000uL, isClaimed = false), 1u),
                        SparkDepositUi(SparkDeposit("d0067b2497c7b905", 35_000uL, isClaimed = true, feeSats = 312uL)),
                    ),
                    withdrawals = persistentListOf(SparkWithdrawal("4f1d0c55e2a9b713", 4_917uL, feeSats = 1_470uL)),
                ),
                status = HealthState.READY,
            ),
        )
    }
}

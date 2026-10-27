package to.bitkit.ui.screens.wallets.usdt

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import breez_sdk_spark.PaymentStatus
import com.synonym.bitkitcore.usdtFormatAmount
import to.bitkit.R
import to.bitkit.ext.uiDateStyleFor
import to.bitkit.models.formatToModernDisplay
import to.bitkit.repositories.SparkUsdTransfer
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.CaptionB
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.UsdtAmountHeader
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.usdtOverviewAmount
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.screens.wallets.activity.components.ActivityRowSurface
import to.bitkit.ui.screens.wallets.activity.components.AmountViewContent
import to.bitkit.ui.screens.wallets.activity.components.CircularIcon
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.uiDateText

@Composable
fun SparkUsdActivityRow(transfer: SparkUsdTransfer, hideBalance: Boolean, onClick: () -> Unit) {
    ActivityRowSurface(onClick = onClick, modifier = Modifier.testTag("SparkUsdActivity-" + transfer.id)) {
        SparkUsdActivityIcon(transfer)
        HorizontalSpacer(16.dp)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            BodyMSB(stringResource(transfer.titleResource), maxLines = 1)
            CaptionB(
                uiDateText(transfer.timestamp, uiDateStyleFor(transfer.timestamp)),
                color = Colors.White64,
                maxLines = 1
            )
        }
        HorizontalSpacer(16.dp)
        AmountViewContent(
            title = usdtOverviewAmount(transfer.amount),
            titlePrefix = if (transfer.isIncoming) "+" else "−",
            subtitle = "$ " + usdtOverviewAmount(transfer.amount),
            hideBalance = hideBalance
        )
    }
}

/** Opens one Spark dollar activity by id, from the Spark dollar balance. */
@Composable
fun SparkUsdActivityScreen(transferId: String, onBack: () -> Unit, viewModel: SparkUsdViewModel = hiltViewModel()) {
    val wallet by viewModel.wallet.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val transfer = wallet?.transfers?.firstOrNull { it.id == transferId }
    if (transfer == null) {
        Column(modifier = Modifier.fillMaxSize().background(Colors.Black)) {
            AppTopBar(titleText = null, onBackClick = onBack, actions = { DrawerNavIcon() })
        }
        return
    }
    SparkUsdActivityDetail(transfer, settings.hideBalance, onBack)
}

@Composable
private fun SparkUsdActivityDetail(transfer: SparkUsdTransfer, hideBalance: Boolean, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(Colors.Black)) {
        AppTopBar(
            titleText = stringResource(transfer.titleResource),
            onBackClick = onBack,
            actions = { DrawerNavIcon() }
        )
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            VerticalSpacer(16.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                UsdtAmountHeader(
                    amount = usdtFormatAmount(transfer.amount),
                    displayAmount = usdtOverviewAmount(transfer.amount),
                    hideBalance = hideBalance,
                    prefix = if (transfer.isIncoming) "+" else "−",
                    modifier = Modifier.weight(1f).testTag("SparkUsdActivityAmount")
                )
                SparkUsdActivityIcon(transfer)
            }
            VerticalSpacer(24.dp)
            DetailRow(R.string.wallet__activity_status, stringResource(transfer.statusResource), transfer.color)
            DetailRow(
                R.string.wallet__activity_date,
                uiDateText(transfer.timestamp, uiDateStyleFor(transfer.timestamp))
            )
            transfer.sats?.let {
                val label = when {
                    transfer.isIncoming -> R.string.wallet__activity_sent
                    else -> R.string.wallet__activity_received
                }
                DetailRow(label, "₿ " + it.formatToModernDisplay())
            }
            DetailRow(R.string.wallet__activity_fee, "$ " + usdtOverviewAmount(transfer.fee))
            transfer.counterparty?.let { DetailRow(R.string.wallet__activity_invoice, it) }
            transfer.txHash?.let { DetailRow(R.string.wallet__activity_tx_id, it) }
            VerticalSpacer(32.dp)
        }
    }
}

@Composable
private fun DetailRow(@StringRes label: Int, value: String, valueColor: Color = Colors.White) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Caption13Up(stringResource(label), color = Colors.White64)
        VerticalSpacer(8.dp)
        BodySSB(value, color = valueColor, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
    HorizontalDivider(color = Colors.White10)
}

@Composable
private fun SparkUsdActivityIcon(transfer: SparkUsdTransfer) {
    CircularIcon(
        icon = painterResource(transfer.iconResource),
        iconColor = transfer.color,
        backgroundColor = transfer.color.copy(alpha = 0.16f),
        size = 40.dp
    )
}

private val SparkUsdTransfer.color: Color get() = when (status) {
    PaymentStatus.FAILED -> Colors.Red
    PaymentStatus.PENDING -> Colors.Yellow
    PaymentStatus.COMPLETED -> Colors.Usdt
}

private val SparkUsdTransfer.iconResource: Int get() = when (status) {
    PaymentStatus.FAILED -> R.drawable.ic_x_circle
    PaymentStatus.PENDING -> R.drawable.ic_hourglass_simple
    PaymentStatus.COMPLETED -> when (kind) {
        SparkUsdTransfer.Kind.TO_USD,
        SparkUsdTransfer.Kind.TO_BTC,
        SparkUsdTransfer.Kind.TO_ARBITRUM,
        -> R.drawable.ic_transfer
        else -> if (isIncoming) R.drawable.ic_received else R.drawable.ic_sent
    }
}

private val SparkUsdTransfer.statusResource: Int get() = when (status) {
    PaymentStatus.FAILED -> R.string.usdt__failed
    PaymentStatus.PENDING -> R.string.usdt__pending
    PaymentStatus.COMPLETED -> R.string.usdt__confirmed
}

private val SparkUsdTransfer.titleResource: Int get() = when {
    status == PaymentStatus.FAILED -> R.string.usdt__failed
    status == PaymentStatus.PENDING -> R.string.usdt__pending
    kind == SparkUsdTransfer.Kind.TO_USD -> R.string.usdt__activity_to_usd_spark
    kind == SparkUsdTransfer.Kind.TO_BTC -> R.string.usdt__activity_to_btc_spark
    kind == SparkUsdTransfer.Kind.TO_ARBITRUM -> R.string.usdt__activity_bridge_spark
    isIncoming -> R.string.usdt__received
    else -> R.string.usdt__sent
}

package to.bitkit.ui.screens.wallets.usdt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synonym.bitkitcore.UsdtDestination
import com.synonym.bitkitcore.UsdtOrchestraTransfer
import com.synonym.bitkitcore.UsdtTransfer
import com.synonym.bitkitcore.UsdtTransferStatus
import com.synonym.bitkitcore.usdtFormatAmount
import to.bitkit.R
import to.bitkit.ext.UiDateStyle
import to.bitkit.ext.uiDateStyleFor
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BalanceHeader
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.CaptionB
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.SendCell
import to.bitkit.ui.components.usdtOverviewAmount
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.screens.wallets.activity.components.ActivityRowSurface
import to.bitkit.ui.screens.wallets.activity.components.AmountViewContent
import to.bitkit.ui.screens.wallets.activity.components.CircularIcon
import to.bitkit.ui.shared.UiConstants
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.uiDateText

@Composable
internal fun UsdtActivityRow(transfer: UsdtTransfer, hideBalance: Boolean, onClick: () -> Unit) {
    val contactName = paykitContactName(transfer.id)
    ActivityRowSurface(onClick = onClick, modifier = Modifier.testTag("UsdtActivity-" + transfer.id)) {
        UsdtActivityIcon(transfer)
        HorizontalSpacer(16.dp)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            BodyMSB(contactName ?: stringResource(transfer.titleResource), maxLines = 1)
            CaptionB(
                if (transfer.status == UsdtTransferStatus.BRIDGING) {
                    stringResource(R.string.usdt__to_network).replace("{network}", transfer.destination.label)
                } else {
                    uiDateText(transfer.timestamp, uiDateStyleFor(transfer.timestamp))
                },
                color = Colors.White64,
                maxLines = 1
            )
        }
        HorizontalSpacer(16.dp)
        AmountViewContent(
            title = usdtOverviewAmount(transfer.activityAmount),
            titlePrefix = if (transfer.isIncoming) "+" else "−",
            subtitle = "$ " + usdtOverviewAmount(transfer.activityAmount),
            hideBalance = hideBalance
        )
    }
}

@Composable
private fun UsdtActivityIcon(transfer: UsdtTransfer, size: Dp = 40.dp) {
    CircularIcon(
        icon = painterResource(transfer.iconResource),
        iconColor = Colors.Usdt,
        backgroundColor = Colors.Usdt.copy(alpha = 0.16f),
        size = size
    )
}

@Composable
internal fun UsdtActivityDetail(
    transfer: UsdtTransfer,
    hideBalance: Boolean,
    walletAddress: String,
    onBack: () -> Unit,
) {
    val contactName = paykitContactName(transfer.id)
    var showDetails by rememberSaveable(transfer.id) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize()) {
        AppTopBar(
            titleText = stringResource(
                if (transfer.isIncoming) R.string.usdt__payment_received else R.string.usdt__sent_title
            ),
            onBackClick = onBack,
            actions = { DrawerNavIcon() }
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.padding(top = 24.dp, bottom = 16.dp)
            ) {
                BalanceHeader(
                    isBitcoinPrimary = false,
                    smallRowText = usdtOverviewAmount(transfer.activityAmount),
                    smallRowSymbol = "$",
                    smallRowModifier = Modifier.padding(bottom = 4.dp),
                    largeRowText = usdtOverviewAmount(transfer.activityAmount),
                    largeRowSymbol = "",
                    largeRowPrefix = if (transfer.isIncoming) "+" else "−",
                    showSymbol = false,
                    hideBalance = hideBalance,
                    onClick = {},
                    modifier = Modifier.weight(1f)
                )
                UsdtActivityIcon(transfer, size = 48.dp)
            }
            if (showDetails) {
                UsdtTechnicalDetails(transfer, hideBalance, walletAddress)
            } else {
                UsdtActivitySummary(transfer, hideBalance)
                if (contactName != null) {
                    SendCell(
                        caption = stringResource(R.string.wallet__payment_request_contact)
                    ) { BodySSB(contactName) }
                }
            }
            PrimaryButton(
                text = stringResource(
                    if (showDetails) R.string.common__hide_details else R.string.wallet__send_details
                ),
                onClick = { showDetails = !showDetails },
                size = ButtonSize.Small,
                icon = {
                    Icon(
                        painterResource(if (showDetails) R.drawable.ic_eye_slash else R.drawable.ic_git_branch),
                        null,
                        tint = Colors.Usdt,
                        modifier = Modifier.size(16.dp)
                    )
                },
                modifier = Modifier.testTag("UsdtActivityDetails")
            )
            if (transfer.destination != UsdtDestination.ARBITRUM) UsdtSupportActions(transfer.supportDetails)
        }
        if (showDetails && transfer.txHash != null) {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(16.dp)
            ) {
                UsdtTransactionDetails(transfer)
            }
        }
    }
}

@Composable
private fun UsdtTechnicalDetails(transfer: UsdtTransfer, hideBalance: Boolean, walletAddress: String) {
    transfer.txHash?.let { hash ->
        SendCell(caption = stringResource(R.string.wallet__activity_tx_id)) {
            SelectionContainer { BodySSB(hash) }
        }
    }
    val sender = transfer.sender ?: walletAddress.takeIf { !transfer.isIncoming && it.isNotEmpty() }
    if (sender != null) {
        SendCell(caption = stringResource(R.string.usdt__from_address)) {
            SelectionContainer { BodySSB(sender) }
        }
    }
    SendCell(caption = stringResource(R.string.usdt__to_address)) {
        SelectionContainer { BodySSB(transfer.recipient) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        UsdtDetailCell(
            stringResource(R.string.usdt__sender_network),
            UsdtDestination.ARBITRUM.label,
            R.drawable.usdt_network,
            modifier = Modifier.weight(1f)
        )
        UsdtDetailCell(
            stringResource(R.string.usdt__destination),
            transfer.destination.label,
            R.drawable.usdt_network,
            modifier = Modifier.weight(1f)
        )
    }
    transfer.orchestra?.let { UsdtOrchestraDetails(it, hideBalance) }
}

@Composable
private fun UsdtActivitySummary(transfer: UsdtTransfer, hideBalance: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        UsdtDetailCell(
            stringResource(R.string.wallet__activity_status),
            stringResource(transfer.status.labelResource),
            if (transfer.status == UsdtTransferStatus.CONFIRMED) R.drawable.ic_check_circle else transfer.iconResource,
            valueColor = transfer.statusColor,
            modifier = Modifier.weight(1f).testTag("UsdtActivityStatus")
        )
        UsdtDetailCell(
            stringResource(R.string.usdt__network),
            UsdtDestination.ARBITRUM.label,
            R.drawable.usdt_network,
            modifier = Modifier.weight(1f)
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        UsdtDetailCell(
            stringResource(R.string.wallet__activity_date),
            uiDateText(transfer.timestamp, UiDateStyle.DATE),
            R.drawable.ic_calendar,
            modifier = Modifier.weight(1f)
        )
        UsdtDetailCell(
            stringResource(R.string.wallet__activity_time),
            uiDateText(transfer.timestamp, UiDateStyle.TIME),
            R.drawable.ic_clock,
            modifier = Modifier.weight(1f)
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        UsdtDetailCell(
            stringResource(R.string.wallet__activity_payment),
            if (hideBalance) UiConstants.HIDE_BALANCE_SHORT else usdtOverviewAmount(transfer.amount),
            R.drawable.ic_user,
            modifier = Modifier.weight(1f)
        )
        UsdtDetailCell(
            stringResource(R.string.wallet__activity_fee),
            if (hideBalance) UiConstants.HIDE_BALANCE_SHORT else transfer.fee?.let { usdtOverviewAmount(it) } ?: "—",
            R.drawable.ic_speed_normal,
            modifier = Modifier.weight(1f)
        )
    }
    if (transfer.destination != UsdtDestination.ARBITRUM) {
        SendCell(
            caption = stringResource(
                if (transfer.status == UsdtTransferStatus.CONFIRMED) {
                    R.string.usdt__recipient_gets
                } else {
                    R.string.usdt__expected_amount
                }
            )
        ) {
            BodySSB(
                if (hideBalance) {
                    UiConstants.HIDE_BALANCE_SHORT
                } else {
                    usdtOverviewAmount(transfer.receivedAmount) + " USDT"
                }
            )
        }
    }
}

@Composable
internal fun UsdtDetailCell(
    title: String,
    value: String,
    icon: Int,
    modifier: Modifier = Modifier,
    valueColor: Color = Colors.White,
) {
    SendCell(caption = title, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(painterResource(icon), null, tint = Colors.Usdt, modifier = Modifier.size(16.dp))
            BodySSB(value, color = valueColor)
        }
    }
}

private val UsdtTransfer.titleResource: Int get() = when (status) {
    UsdtTransferStatus.FAILED, UsdtTransferStatus.REPLACED, UsdtTransferStatus.PENDING,
    UsdtTransferStatus.BRIDGE_REFUNDED, UsdtTransferStatus.BRIDGING,
    UsdtTransferStatus.BRIDGE_NEEDS_ATTENTION, UsdtTransferStatus.BRIDGE_FAILED ->
        status.labelResource
    else -> if (isIncoming) R.string.usdt__received else R.string.usdt__sent
}

private val UsdtTransfer.iconResource: Int get() = when (status) {
    UsdtTransferStatus.FAILED, UsdtTransferStatus.REPLACED, UsdtTransferStatus.BRIDGE_FAILED -> R.drawable.ic_x_circle
    UsdtTransferStatus.PENDING, UsdtTransferStatus.BRIDGE_NEEDS_ATTENTION -> R.drawable.ic_hourglass_simple
    UsdtTransferStatus.BRIDGING -> R.drawable.ic_transfer
    UsdtTransferStatus.BRIDGE_REFUNDED -> R.drawable.ic_received
    UsdtTransferStatus.CONFIRMED -> if (isIncoming) R.drawable.ic_received else R.drawable.ic_sent
}

private val UsdtTransfer.statusColor get() = when (status) {
    UsdtTransferStatus.FAILED, UsdtTransferStatus.REPLACED -> Colors.Red
    UsdtTransferStatus.BRIDGE_NEEDS_ATTENTION, UsdtTransferStatus.BRIDGE_FAILED -> Colors.Yellow
    else -> Colors.Usdt
}

private val UsdtTransferStatus.labelResource: Int get() = when (this) {
    UsdtTransferStatus.PENDING -> R.string.usdt__pending
    UsdtTransferStatus.CONFIRMED -> R.string.usdt__confirmed
    UsdtTransferStatus.FAILED -> R.string.usdt__failed
    UsdtTransferStatus.BRIDGING -> R.string.wallet__activity_transferring
    UsdtTransferStatus.BRIDGE_NEEDS_ATTENTION, UsdtTransferStatus.BRIDGE_FAILED -> R.string.usdt__bridge_attention
    UsdtTransferStatus.BRIDGE_REFUNDED -> R.string.usdt__deposit_refunded
    UsdtTransferStatus.REPLACED -> R.string.usdt__replaced
}

internal val UsdtTransfer.activityAmount: ULong get() {
    if (isIncoming) return amount
    if (status == UsdtTransferStatus.FAILED || status == UsdtTransferStatus.REPLACED) return fee ?: 0u
    val fee = fee ?: return amount
    return if (ULong.MAX_VALUE - amount < fee) ULong.MAX_VALUE else amount + fee
}

@Composable
private fun paykitContactName(transferId: String): String? {
    val app = appViewModel ?: return null
    val attempts by app.paykitUsdtPayments.attempts.collectAsStateWithLifecycle()
    val receipts by app.paykitUsdtPayments.receipts.collectAsStateWithLifecycle()
    val contacts by app.pubkyContacts.collectAsStateWithLifecycle()
    val key = attempts.firstOrNull { it.quoteId == transferId }?.contact
        ?: receipts.firstOrNull { it.transferId == transferId }?.requestId?.counterparty ?: return null
    return contacts.firstOrNull { PubkyPublicKeyFormat.matches(it.publicKey, key) }?.name
        ?: PubkyPublicKeyFormat.display(key)
}

@Composable
private fun UsdtOrchestraDetails(bridge: UsdtOrchestraTransfer, hideBalance: Boolean) {
    SendCell(caption = stringResource(R.string.usdt__bridge_provider)) { BodySSB("Orchestra") }
    SendCell(caption = stringResource(R.string.usdt__bridge_reference)) { SelectionContainer { BodyS(bridge.quoteId) } }
    bridge.destinationTx?.let { hash ->
        SendCell(
            caption = stringResource(R.string.usdt__destination_tx)
        ) { SelectionContainer { BodyS(hash) } }
    }
    bridge.refundAmount?.let { amount ->
        SendCell(caption = stringResource(R.string.usdt__deposit_refunded)) {
            BodySSB(if (hideBalance) UiConstants.HIDE_BALANCE_SHORT else usdtFormatAmount(amount) + " USDT")
        }
    }
    bridge.refundTx?.let { hash ->
        SendCell(caption = stringResource(R.string.usdt__refund_tx)) { SelectionContainer { BodyS(hash) } }
    }
}

@Composable
private fun UsdtTransactionDetails(transfer: UsdtTransfer) {
    val uri = LocalUriHandler.current
    transfer.txHash?.let { txHash ->
        if (transfer.destination != UsdtDestination.ARBITRUM && transfer.orchestra == null) {
            SecondaryButton(text = stringResource(R.string.usdt__track_bridge), onClick = {
                uri.openUri("https://layerzeroscan.com/tx/" + txHash)
            })
        }
        PrimaryButton(text = stringResource(R.string.wallet__activity_explorer), onClick = {
            uri.openUri("https://arbiscan.io/tx/" + txHash)
        })
    }
}

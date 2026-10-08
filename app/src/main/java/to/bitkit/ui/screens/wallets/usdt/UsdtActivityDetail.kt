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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synonym.bitkitcore.UsdtDestination
import com.synonym.bitkitcore.UsdtTransfer
import com.synonym.bitkitcore.UsdtTransferStatus
import com.synonym.bitkitcore.usdtFormatAmount
import to.bitkit.R
import to.bitkit.ext.UiDateStyle
import to.bitkit.ext.uiDateStyleFor
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.CaptionB
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.SendCell
import to.bitkit.ui.components.UsdtAmountHeader
import to.bitkit.ui.components.VerticalSpacer
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
                uiDateText(transfer.timestamp, uiDateStyleFor(transfer.timestamp)),
                color = Colors.White64,
                maxLines = 1
            )
        }
        HorizontalSpacer(16.dp)
        AmountViewContent(
            title = usdtFormatAmount(transfer.activityAmount),
            titlePrefix = if (transfer.isIncoming) "+" else "−",
            subtitle = "$ " + usdtOverviewAmount(transfer.activityAmount),
            hideBalance = hideBalance
        )
    }
}

@Composable
private fun UsdtActivityIcon(transfer: UsdtTransfer) {
    CircularIcon(
        icon = painterResource(transfer.iconResource),
        iconColor = transfer.statusColor,
        backgroundColor = transfer.statusColor.copy(alpha = 0.16f),
        size = 40.dp
    )
}

@Composable
internal fun UsdtActivityDetail(transfer: UsdtTransfer, hideBalance: Boolean, onBack: () -> Unit) {
    val contactName = paykitContactName(transfer.id)
    Column(modifier = Modifier.fillMaxSize()) {
        AppTopBar(
            titleText = stringResource(transfer.titleResource),
            onBackClick = onBack,
            actions = { DrawerNavIcon() }
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                UsdtAmountHeader(
                    usdtFormatAmount(transfer.activityAmount),
                    hideBalance = hideBalance,
                    prefix = if (transfer.isIncoming) "+" else "−",
                    modifier = Modifier.weight(1f)
                )
                UsdtActivityIcon(transfer)
            }
            VerticalSpacer(16.dp)
            if (contactName != null) {
                SendCell(
                    caption = stringResource(
                        if (transfer.isIncoming) R.string.wallet__send_from else R.string.wallet__send_to
                    )
                ) {
                    BodySSB(contactName)
                }
            }
            SendCell(caption = stringResource(R.string.wallet__activity_status)) {
                BodySSB(
                    stringResource(transfer.status.labelResource),
                    color = transfer.statusColor,
                    modifier = Modifier.testTag("UsdtActivityStatus")
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SendCell(caption = stringResource(R.string.wallet__activity_date), modifier = Modifier.weight(1f)) {
                    BodySSB(uiDateText(transfer.timestamp, UiDateStyle.DATE))
                }
                SendCell(caption = stringResource(R.string.wallet__activity_time), modifier = Modifier.weight(1f)) {
                    BodySSB(uiDateText(transfer.timestamp, UiDateStyle.TIME))
                }
            }
            SendCell(caption = stringResource(R.string.usdt__destination)) { BodySSB(transfer.destination.label) }
            if (!transfer.isIncoming) {
                SendCell(
                    caption = stringResource(R.string.usdt__address)
                ) { SelectionContainer { BodySSB(transfer.recipient) } }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (
                        transfer.status != UsdtTransferStatus.FAILED && transfer.status != UsdtTransferStatus.REPLACED
                    ) {
                        SendCell(
                            caption = stringResource(
                                if (transfer.destination != UsdtDestination.ARBITRUM &&
                                    transfer.status != UsdtTransferStatus.CONFIRMED
                                ) {
                                    R.string.usdt__expected_amount
                                } else {
                                    R.string.usdt__recipient_gets
                                }
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            BodySSB(
                                (
                                    if (hideBalance) {
                                        UiConstants.HIDE_BALANCE_SHORT
                                    } else {
                                        usdtFormatAmount(transfer.receivedAmount)
                                    }
                                    ) + " USDT"
                            )
                        }
                    }
                    SendCell(caption = stringResource(R.string.wallet__activity_fee), modifier = Modifier.weight(1f)) {
                        BodySSB(
                            if (hideBalance) {
                                UiConstants.HIDE_BALANCE_SHORT
                            } else {
                                transfer.fee?.let { usdtFormatAmount(it) + " USDT" }
                                    ?: "—"
                            }
                        )
                    }
                }
            }
            transfer.orchestra?.let { UsdtOrchestraDetails(it, hideBalance) }
            UsdtTransactionDetails(transfer)
            VerticalSpacer(120.dp)
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
    UsdtTransferStatus.FAILED, UsdtTransferStatus.REPLACED, UsdtTransferStatus.BRIDGE_FAILED -> R.drawable.ic_x
    UsdtTransferStatus.PENDING, UsdtTransferStatus.BRIDGING,
    UsdtTransferStatus.BRIDGE_NEEDS_ATTENTION -> R.drawable.ic_timer_alt
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
    UsdtTransferStatus.BRIDGING -> R.string.usdt__bridging
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
    val app = to.bitkit.ui.appViewModel ?: return null
    val attempts by app.paykitUsdtPayments.attempts.collectAsStateWithLifecycle()
    val receipts by app.paykitUsdtPayments.receipts.collectAsStateWithLifecycle()
    val contacts by app.pubkyContacts.collectAsStateWithLifecycle()
    val key = attempts.firstOrNull { it.quoteId == transferId }?.contact
        ?: receipts.firstOrNull { it.transferId == transferId }?.requestId?.counterparty ?: return null
    return contacts.firstOrNull { to.bitkit.models.PubkyPublicKeyFormat.matches(it.publicKey, key) }?.name
        ?: to.bitkit.models.PubkyPublicKeyFormat.display(key)
}

@Composable
private fun UsdtOrchestraDetails(bridge: com.synonym.bitkitcore.UsdtOrchestraTransfer, hideBalance: Boolean) {
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
        SendCell(
            caption = stringResource(R.string.wallet__activity_tx_id)
        ) { SelectionContainer { BodyS(txHash) } }
        if (transfer.destination != UsdtDestination.ARBITRUM && transfer.orchestra == null) {
            SecondaryButton(text = stringResource(R.string.usdt__track_bridge), onClick = {
                uri.openUri("https://layerzeroscan.com/tx/" + txHash)
            })
        }
        SecondaryButton(text = stringResource(R.string.wallet__activity_explore), onClick = {
            uri.openUri("https://arbiscan.io/tx/" + txHash)
        })
    }
    if (transfer.destination != UsdtDestination.ARBITRUM) UsdtSupportActions(transfer.supportDetails)
}

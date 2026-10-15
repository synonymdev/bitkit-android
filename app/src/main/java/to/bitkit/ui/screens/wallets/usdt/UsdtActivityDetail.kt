package to.bitkit.ui.screens.wallets.usdt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synonym.bitkitcore.UsdtDestination
import com.synonym.bitkitcore.UsdtOrchestraTransfer
import com.synonym.bitkitcore.UsdtTransfer
import com.synonym.bitkitcore.UsdtTransferStatus
import com.synonym.bitkitcore.usdtFormatAmount
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.ext.UiDateStyle
import to.bitkit.ext.uiDateStyleFor
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.CaptionB
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.LargeRow
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.usdtOverviewAmount
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.screens.wallets.activity.components.ActivityRowSurface
import to.bitkit.ui.screens.wallets.activity.components.AmountViewContent
import to.bitkit.ui.screens.wallets.activity.components.CircularIcon
import to.bitkit.ui.settingsViewModel
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
        iconColor = transfer.activityIconColor,
        backgroundColor = transfer.activityIconColor.copy(alpha = 0.16f),
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
    val app = appViewModel
    val canOpenDeposits = transfer.isIncoming && Env.usdtDepositsUrl != null
    var showDetails by rememberSaveable(transfer.id) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize()) {
        AppTopBar(
            titleText = stringResource(
                transfer.detailTitleResource
            ),
            onBackClick = onBack,
            titleStyle = UsdtDetailStyles.Title,
            actions = { DrawerNavIcon() }
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
        ) {
            UsdtActivityAmountHeader(
                amount = transfer.activityAmount,
                isIncoming = transfer.isIncoming,
                hideBalance = hideBalance,
                modifier = Modifier.padding(top = 16.dp, bottom = 16.dp)
            )
            if (showDetails) {
                UsdtTechnicalDetails(transfer, hideBalance, walletAddress)
                if (canOpenDeposits) UsdtTransactionDetails(transfer)
            } else {
                UsdtActivitySummary(transfer, hideBalance)
                if (contactName != null) {
                    UsdtDetailField(
                        caption = stringResource(R.string.wallet__payment_request_contact)
                    ) { BodySSB(contactName, style = UsdtDetailStyles.Body) }
                }
            }
            PrimaryButton(
                text = stringResource(
                    if (showDetails) R.string.common__hide_details else R.string.wallet__send_details
                ),
                onClick = { showDetails = !showDetails },
                size = ButtonSize.Small,
                textStyle = UsdtDetailStyles.SmallButton,
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
            if (transfer.destination != UsdtDestination.ARBITRUM && (
                    showDetails || transfer.status in setOf(
                        UsdtTransferStatus.BRIDGE_NEEDS_ATTENTION, UsdtTransferStatus.BRIDGE_FAILED,
                        UsdtTransferStatus.FAILED, UsdtTransferStatus.BRIDGE_REFUNDED,
                    )
                    )
            ) {
                UsdtSupportActions(transfer.supportDetails)
            }
        }
        if (showDetails && (transfer.txHash != null || canOpenDeposits)) {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(16.dp)
            ) {
                if (canOpenDeposits) {
                    PrimaryButton(
                        text = stringResource(R.string.usdt__deposit_history),
                        textStyle = UsdtDetailStyles.Button,
                        onClick = { app?.navigateToUsdtDeposits() },
                        modifier = Modifier.testTag("UsdtDepositHistory")
                    )
                } else {
                    UsdtTransactionDetails(transfer)
                }
            }
        }
    }
}

@Composable
internal fun UsdtActivityAmountHeader(
    amount: ULong?,
    isIncoming: Boolean,
    hideBalance: Boolean,
    modifier: Modifier = Modifier,
) {
    val amountText = amount?.let { usdtOverviewAmount(it) } ?: "—"
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = modifier) {
        Caption13Up(
            text = "$ " + if (hideBalance) UiConstants.HIDE_BALANCE_SHORT else amountText,
            color = Colors.White64,
            style = UsdtDetailStyles.Caption,
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(36.dp)) {
            LargeRow(
                prefix = if (isIncoming) "+" else "-",
                text = amountText,
                symbol = "",
                showSymbol = false,
                hideBalance = hideBalance,
                prefixSpacing = 6.dp,
                modifier = Modifier.weight(1f).wrapContentHeight(unbounded = true)
            )
            CircularIcon(
                icon = painterResource(if (isIncoming) R.drawable.ic_received else R.drawable.ic_sent),
                iconColor = Colors.Usdt,
                backgroundColor = Colors.Usdt.copy(alpha = 0.16f),
                size = 48.dp,
                modifier = Modifier.requiredSize(48.dp)
            )
        }
    }
}

internal val UsdtTransfer.detailTitleResource: Int get() = when {
    isIncoming && status == UsdtTransferStatus.PENDING -> R.string.usdt__receiving_title
    isIncoming -> R.string.usdt__payment_received
    status == UsdtTransferStatus.BRIDGING -> R.string.usdt__delivering_title
    else -> R.string.usdt__sent_title
}

@Composable
private fun UsdtTechnicalDetails(transfer: UsdtTransfer, hideBalance: Boolean, walletAddress: String) {
    transfer.txHash?.let { hash ->
        UsdtDetailField(caption = stringResource(R.string.wallet__activity_tx_id)) {
            SelectionContainer { BodySSB(hash, style = UsdtDetailStyles.Body) }
        }
    }
    val sender = transfer.sender ?: walletAddress.takeIf { !transfer.isIncoming && it.isNotEmpty() }
    if (sender != null) {
        UsdtDetailField(caption = stringResource(R.string.usdt__from_address)) {
            SelectionContainer { BodySSB(sender, style = UsdtDetailStyles.Body) }
        }
    }
    UsdtDetailField(caption = stringResource(R.string.usdt__to_address)) {
        SelectionContainer { BodySSB(transfer.recipient, style = UsdtDetailStyles.Body) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!transfer.isIncoming) {
            UsdtDetailCell(
                stringResource(R.string.usdt__sender_network),
                UsdtDestination.ARBITRUM.label,
                R.drawable.usdt_network,
                modifier = Modifier.weight(1f)
            )
        }
        UsdtDetailCell(
            stringResource(R.string.usdt__destination),
            transfer.destination.label,
            R.drawable.usdt_network,
            modifier = Modifier.weight(1f)
        )
    }
    transfer.orchestra?.let { UsdtOrchestraDetails(it, hideBalance) }
    if (transfer.destination != UsdtDestination.ARBITRUM && transfer.status !in setOf(
            UsdtTransferStatus.BRIDGE_REFUNDED, UsdtTransferStatus.FAILED, UsdtTransferStatus.REPLACED,
        )
    ) {
        UsdtDetailField(
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
private fun UsdtActivitySummary(transfer: UsdtTransfer, hideBalance: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        UsdtDetailCell(
            stringResource(R.string.wallet__activity_status),
            stringResource(transfer.status.labelResource),
            when (transfer.status) {
                UsdtTransferStatus.CONFIRMED -> R.drawable.ic_check_circle
                UsdtTransferStatus.BRIDGING -> R.drawable.ic_hourglass_simple
                else -> transfer.iconResource
            },
            textStyle = UsdtDetailStyles.Body,
            valueColor = transfer.statusColor,
            iconColor = transfer.statusColor,
            modifier = Modifier.weight(1f).testTag("UsdtActivityStatus")
        )
        UsdtDetailCell(
            stringResource(R.string.usdt__receiving_network),
            transfer.destination.label,
            R.drawable.usdt_network,
            modifier = Modifier.weight(1f)
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        UsdtDetailCell(
            stringResource(R.string.wallet__activity_date),
            uiDateText(transfer.timestamp, UiDateStyle.DATE),
            R.drawable.ic_calendar,
            textStyle = UsdtDetailStyles.Body,
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
}

@Composable
internal fun UsdtDetailCell(
    title: String,
    value: String,
    icon: Int,
    modifier: Modifier = Modifier,
    valueColor: Color = Colors.White,
    iconColor: Color = Colors.Usdt,
    minValueHeight: Dp = 0.dp,
    textStyle: TextStyle = UsdtDetailStyles.Value,
) {
    UsdtDetailField(caption = title, modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.heightIn(min = minValueHeight).wrapContentHeight(Alignment.Top),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(16.dp)) {
                Icon(
                    painterResource(icon),
                    null,
                    tint = iconColor,
                    modifier = if (icon == R.drawable.ic_clock) Modifier.size(13.dp, 14.dp) else Modifier.size(16.dp)
                )
            }
            BodySSB(value, color = valueColor, style = textStyle)
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

private val UsdtTransfer.activityIconColor get() = when (status) {
    UsdtTransferStatus.FAILED, UsdtTransferStatus.REPLACED, UsdtTransferStatus.BRIDGE_FAILED -> Colors.Red
    UsdtTransferStatus.PENDING, UsdtTransferStatus.BRIDGE_NEEDS_ATTENTION -> Colors.Yellow
    else -> Colors.Usdt
}

private val UsdtTransfer.statusColor get() = when (status) {
    UsdtTransferStatus.FAILED, UsdtTransferStatus.REPLACED -> Colors.Red
    UsdtTransferStatus.PENDING, UsdtTransferStatus.BRIDGING,
    UsdtTransferStatus.BRIDGE_NEEDS_ATTENTION, UsdtTransferStatus.BRIDGE_FAILED -> Colors.Yellow
    else -> Colors.Usdt
}

private val UsdtTransferStatus.labelResource: Int get() = when (this) {
    UsdtTransferStatus.PENDING -> R.string.usdt__pending
    UsdtTransferStatus.CONFIRMED -> R.string.usdt__confirmed
    UsdtTransferStatus.FAILED -> R.string.usdt__failed
    UsdtTransferStatus.BRIDGING -> R.string.usdt__delivering
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
    val settings = settingsViewModel ?: return null
    val isPaykitEnabled by settings.isPaykitEnabled.collectAsStateWithLifecycle()
    if (!isPaykitEnabled) return null
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
    UsdtDetailCell(stringResource(R.string.usdt__bridge_provider), "Orchestra", R.drawable.ic_transfer)
    SelectionContainer {
        UsdtDetailCell(stringResource(R.string.usdt__bridge_reference), bridge.quoteId, R.drawable.ic_note)
    }
    bridge.destinationTx?.let { hash ->
        UsdtDetailField(
            caption = stringResource(R.string.usdt__destination_tx)
        ) { SelectionContainer { BodyS(hash) } }
    }
    bridge.refundAmount?.let { amount ->
        UsdtDetailField(caption = stringResource(R.string.usdt__deposit_refunded)) {
            BodySSB(if (hideBalance) UiConstants.HIDE_BALANCE_SHORT else usdtFormatAmount(amount) + " USDT")
        }
    }
    bridge.refundTx?.let { hash ->
        UsdtDetailField(caption = stringResource(R.string.usdt__refund_tx)) { SelectionContainer { BodyS(hash) } }
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
        PrimaryButton(
            text = stringResource(R.string.wallet__activity_explorer),
            textStyle = UsdtDetailStyles.Button,
            onClick = { uri.openUri("https://arbiscan.io/tx/" + txHash) },
            modifier = Modifier.testTag("UsdtActivityExplorer")
        )
    }
}

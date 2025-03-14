package to.bitkit.ui.screens.wallets.send

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ext.DatePattern
import to.bitkit.ext.ellipsisMiddle
import to.bitkit.ext.formatted
import to.bitkit.ext.truncate
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.SwipeToConfirm
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.SendEvent
import to.bitkit.viewmodels.SendMethod
import to.bitkit.viewmodels.SendUiState
import uniffi.bitkitcore.LightningInvoice
import uniffi.bitkitcore.NetworkType
import java.time.Instant

@Composable
fun SendAndReviewScreen(
    uiState: SendUiState,
    onBack: () -> Unit,
    onEvent: (SendEvent) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        val scope = rememberCoroutineScope()
        // TODO handle loading via uiState?
        var isLoading by remember { mutableStateOf(false) }

        SheetTopBar(stringResource(R.string.title_send_review)) {
            onBack()
        }
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
        ) {
            BalanceHeaderView(sats = uiState.amount.toLong(), modifier = Modifier.fillMaxWidth())

            Spacer(modifier = Modifier.height(16.dp))

            when (uiState.payMethod) {
                SendMethod.ONCHAIN -> OnChainDescription(uiState = uiState, onEvent = onEvent)
                SendMethod.LIGHTNING -> LightningDescription(uiState = uiState, onEvent = onEvent)
            }

            uiState.decodedInvoice?.description?.let { description ->
                Column {
                    Caption13Up(text = stringResource(R.string.label_note), color = Colors.White64)
                    Spacer(modifier = Modifier.height(8.dp))
                    BodySSB(text = description)
                    HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
                }
            }

            Column {
                Spacer(modifier = Modifier.height(16.dp))
                Caption13Up(text = stringResource(R.string.label_tags), color = Colors.White64)
                Spacer(modifier = Modifier.height(8.dp))
                BodySSB(text = "Todo")
            }

            Spacer(modifier = Modifier.weight(1f))
            SwipeToConfirm(
                text = stringResource(R.string.wallet__send_swipe),
                loading = isLoading,
                onConfirm = {
                    scope.launch {
                        isLoading = true
                        delay(300)
                        onEvent(SendEvent.SwipeToPay)
                    }
                }
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun OnChainDescription(
    uiState: SendUiState,
    onEvent: (SendEvent) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Caption13Up(
            text = stringResource(
                if (uiState.payMethod == SendMethod.ONCHAIN) R.string.wallet__send_to else R.string.wallet__send_invoice
            ),
            color = Colors.White64,
        )
        val destination = when (uiState.payMethod) {
            SendMethod.ONCHAIN -> uiState.address.ellipsisMiddle(25)
            SendMethod.LIGHTNING -> uiState.bolt11?.truncate(100) ?: ""
        }
        Spacer(modifier = Modifier.height(8.dp))
        BodySSB(text = destination)
        HorizontalDivider(modifier = Modifier.padding(top = 16.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.height(IntrinsicSize.Min)
        ) {
            if (uiState.payMethod == SendMethod.ONCHAIN) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .clickable { onEvent(SendEvent.SpeedAndFee) }
                        .padding(top = 16.dp)
                ) {
                    Caption13Up(text = stringResource(R.string.label_speed), color = Colors.White64)
                    Spacer(modifier = Modifier.height(8.dp))
                    BodySSB(text = "Todo Normal (₿ 210)")
                    Spacer(modifier = Modifier.weight(1f))
                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                }
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .clickable { onEvent(SendEvent.SpeedAndFee) }
                        .padding(top = 16.dp)
                ) {
                    Caption13Up(text = stringResource(R.string.label_confirms_in), color = Colors.White64)
                    Spacer(modifier = Modifier.height(8.dp))
                    BodySSB(text = "Todo ± 20-60 minutes")
                    Spacer(modifier = Modifier.weight(1f))
                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .padding(top = 16.dp)
                ) {
                    Caption13Up(text = stringResource(R.string.label_speed), color = Colors.White64)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_speed_normal),
                            contentDescription = null,
                            tint = Colors.Brand,
                            modifier = Modifier.size(16.dp)
                        ) //TODO GET FROM STATE
                        BodySSB(text = "Instant (±$0.01)") //TODO GET FROM STATE
                        Icon(
                            painterResource(R.drawable.ic_pencil_simple),
                            contentDescription = null,
                            tint = Colors.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                }
                uiState.decodedInvoice?.expirySeconds?.let { expirySeconds ->
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(1f)
                            .padding(top = 16.dp)
                    ) {
                        val invoiceExpiryTimestamp = expirySeconds.let {
                            Instant.now().plusSeconds(it.toLong()).formatted(DatePattern.INVOICE_EXPIRY)
                        }
                        Caption13Up(
                            text = stringResource(R.string.label_invoice_expiration),
                            color = Colors.White64
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_clock),
                                contentDescription = null,
                                tint = Colors.Brand,
                                modifier = Modifier.size(16.dp)
                            )
                            BodySSB(text = invoiceExpiryTimestamp)
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun LightningDescription(
    uiState: SendUiState,
    onEvent: (SendEvent) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Caption13Up(
            text = stringResource(R.string.wallet__send_invoice),
            color = Colors.White64,
        )
        val destination = uiState.bolt11?.truncate(100).orEmpty()
        Spacer(modifier = Modifier.height(8.dp))
        BodySSB(text = destination)
        HorizontalDivider(modifier = Modifier.padding(top = 16.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.height(IntrinsicSize.Min)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    .padding(top = 16.dp)
            ) {
                Caption13Up(text = stringResource(R.string.label_speed), color = Colors.White64)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_lightning),
                        contentDescription = null,
                        tint = Colors.Brand,
                        modifier = Modifier.size(16.dp)
                    )
                    BodySSB(text = "Instant (±$0.01)") //TODO GET FROM STATE
                    Icon(
                        painterResource(R.drawable.ic_timer),
                        contentDescription = null,
                        tint = Colors.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            }
            uiState.decodedInvoice?.expirySeconds?.let { expirySeconds ->
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .padding(top = 16.dp)
                ) {
                    val invoiceExpiryTimestamp = expirySeconds.let {
                        Instant.now().plusSeconds(it.toLong()).formatted(DatePattern.INVOICE_EXPIRY)
                    }
                    Caption13Up(
                        text = stringResource(R.string.label_invoice_expiration),
                        color = Colors.White64
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_clock),
                            contentDescription = null,
                            tint = Colors.Brand,
                            modifier = Modifier.size(16.dp)
                        )
                        BodySSB(text = invoiceExpiryTimestamp)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                }
            }
        }
    }
}

@Suppress("SpellCheckingInspection")
@Preview(name = "Lightning")
@Composable
private fun SendAndReviewPreview() {
    AppThemeSurface {
        SendAndReviewScreen(
            uiState = SendUiState(
                amount = 1234uL,
                address = "bcrt1qkgfgyxyqhvkdqh04sklnzxphmcds6vft6y7h0r",
                bolt11 = "lnbcrt1…",
                payMethod = SendMethod.LIGHTNING,
                decodedInvoice = LightningInvoice(
                    bolt11 = "bcrt123",
                    paymentHash = ByteArray(0),
                    amountSatoshis = 100000uL,
                    timestampSeconds = 0uL,
                    expirySeconds = 3600uL,
                    isExpired = false,
                    networkType = NetworkType.REGTEST,
                    payeeNodeId = null,
                    description = "Some invoice description",
                )
            ),
            onBack = {},
            onEvent = {},
        )
    }
}

@Suppress("SpellCheckingInspection")
@Preview(name = "OnChain")
@Composable
private fun SendAndReviewPreview2() {
    AppThemeSurface {
        SendAndReviewScreen(
            uiState = SendUiState(
                amount = 1234uL,
                address = "bcrt1qkgfgyxyqhvkdqh04sklnzxphmcds6vft6y7h0r",
                bolt11 = "lnbcrt1…",
                payMethod = SendMethod.ONCHAIN,
                decodedInvoice = LightningInvoice(
                    bolt11 = "bcrt123",
                    paymentHash = ByteArray(0),
                    amountSatoshis = 10000uL,
                    timestampSeconds = 0uL,
                    expirySeconds = 3600uL,
                    isExpired = false,
                    networkType = NetworkType.REGTEST,
                    payeeNodeId = null,
                    description = "Some invoice description",
                )
            ),
            onBack = {},
            onEvent = {},
        )
    }
}

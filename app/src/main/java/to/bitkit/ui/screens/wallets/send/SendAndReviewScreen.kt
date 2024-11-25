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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import to.bitkit.R
import to.bitkit.ext.DatePattern
import to.bitkit.ext.ellipsisMiddle
import to.bitkit.ext.formatted
import to.bitkit.ext.truncate
import to.bitkit.ui.components.LabelText
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.screens.wallets.send.components.SwipeButton
import to.bitkit.ui.shared.moneyString
import to.bitkit.ui.shared.util.DarkModePreview
import to.bitkit.ui.shared.util.LightModePreview
import to.bitkit.ui.theme.AppThemeSurface
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
        SheetTopBar(stringResource(R.string.title_send_review)) {
            onBack()
        }
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
        ) {
            LabelText(text = stringResource(R.string.label_amount))
            Text(
                text = moneyString(uiState.amount.toLong()),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
            )

            Spacer(modifier = Modifier.height(16.dp))

            LabelText(
                text = stringResource(
                    if (uiState.payMethod == SendMethod.ONCHAIN) R.string.label_to else R.string.label_invoice
                )
            )
            val destination = when (uiState.payMethod) {
                SendMethod.ONCHAIN -> uiState.address.ellipsisMiddle(25)
                SendMethod.LIGHTNING -> uiState.bolt11?.truncate(100) ?: ""
            }
            Text(text = destination)

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
                        LabelText(text = stringResource(R.string.label_speed))
                        Text(text = "Todo Normal (₿ 210)")
                        Spacer(modifier = Modifier.weight(1f))
                        HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(1f)
                            .clickable { onEvent(SendEvent.SpeedAndFee) }
                            .padding(top = 16.dp)
                    ) {
                        LabelText(text = stringResource(R.string.label_confirms_in))
                        Text(text = "Todo ± 20-60 minutes")
                        Spacer(modifier = Modifier.weight(1f))
                        HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(1f)
                            .padding(top = 16.dp)
                    ) {
                        LabelText(text = stringResource(R.string.label_speed))
                        Text(text = "Instant (±$0.01)")
                        Spacer(modifier = Modifier.weight(1f))
                        HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
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
                            LabelText(text = stringResource(R.string.label_invoice_expiration))
                            Text(text = invoiceExpiryTimestamp)
                            Spacer(modifier = Modifier.weight(1f))
                            HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
                        }
                    }
                }
            }

            uiState.decodedInvoice?.description?.let { description ->
                Column {
                    Spacer(modifier = Modifier.height(16.dp))
                    LabelText(text = stringResource(R.string.label_note))
                    Text(text = description)
                    HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
                }
            }

            Column {
                Spacer(modifier = Modifier.height(16.dp))
                LabelText(text = stringResource(R.string.label_tags))
                Text(text = "Todo")
            }

            Spacer(modifier = Modifier.weight(1f))
            SwipeButton(
                onComplete = {
                    delay(300)
                    onEvent(SendEvent.SwipeToPay)
                }
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Suppress("SpellCheckingInspection")
@LightModePreview
@DarkModePreview
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
                    ByteArray(0),
                    amountSatoshis = 0uL,
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

package to.bitkit.ui.screens.trezor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.synonym.bitkitcore.AccountType
import com.synonym.bitkitcore.HistoryTransaction
import com.synonym.bitkitcore.TransactionHistoryResult
import com.synonym.bitkitcore.TxDirection
import to.bitkit.R
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.copyToClipboard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun TransactionHistorySection(
    uiState: TrezorUiState,
    onInputChange: (String) -> Unit,
    onAccountTypeChange: (AccountType?) -> Unit,
    onLookup: () -> Unit,
) {
    Column {
        Caption13Up(
            text = "Transaction History",
            color = Colors.White64,
        )
        VerticalSpacer(8.dp)

        OutlinedTextField(
            value = uiState.txHistoryInput,
            onValueChange = onInputChange,
            label = { Caption("xpub / vpub / zpub", color = Colors.White50) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Colors.White,
                unfocusedTextColor = Colors.White,
                focusedBorderColor = Colors.Brand,
                unfocusedBorderColor = Colors.White32,
                cursorColor = Colors.Brand,
            ),
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        VerticalSpacer(8.dp)

        AccountTypeSelectorRow(
            selectedAccountType = uiState.txHistorySelectedAccountType,
            onAccountTypeChange = onAccountTypeChange,
        )

        VerticalSpacer(16.dp)

        PrimaryButton(
            text = if (uiState.isLoadingTxHistory) "Loading..." else "Lookup History",
            onClick = onLookup,
            enabled = !uiState.isLoadingTxHistory && uiState.txHistoryInput.isNotBlank(),
            size = ButtonSize.Small,
            modifier = Modifier.fillMaxWidth(),
        )

        AnimatedVisibility(visible = uiState.txHistoryResult != null) {
            uiState.txHistoryResult?.let { TransactionHistoryResultView(it) }
        }
    }
}

@Composable
private fun TransactionHistoryResultView(result: TransactionHistoryResult) {
    Column {
        VerticalSpacer(16.dp)

        Caption13Up(text = "Summary", color = Colors.White64)
        VerticalSpacer(4.dp)
        ResultCard {
            InfoRow("Account Type", result.accountType.name)
            InfoRow("Transactions", "${result.txCount}")
            InfoRow("Block Height", "${result.blockHeight}")
        }

        VerticalSpacer(12.dp)

        Caption13Up(text = "Balance", color = Colors.White64)
        VerticalSpacer(4.dp)
        ResultCard {
            InfoRow("Confirmed", "${result.balance.confirmed} sats")
            InfoRow("Trusted Pending", "${result.balance.trustedPending} sats")
            InfoRow("Untrusted Pending", "${result.balance.untrustedPending} sats")
            InfoRow("Spendable", "${result.balance.spendable} sats")
            InfoRow("Total", "${result.balance.total} sats")
        }

        if (result.transactions.isNotEmpty()) {
            VerticalSpacer(12.dp)
            Caption13Up(
                text = "Transactions (${result.transactions.size})",
                color = Colors.White64,
            )
            VerticalSpacer(4.dp)
            result.transactions.forEach { tx ->
                TransactionRow(tx)
                VerticalSpacer(4.dp)
            }
        }
    }
}

@Composable
private fun TransactionRow(tx: HistoryTransaction) {
    val onCopyTxid = copyToClipboard(text = tx.txid, label = "TXID")
    val directionLabel = when (tx.direction) {
        TxDirection.SENT -> "Sent"
        TxDirection.RECEIVED -> "Received"
        TxDirection.SELF_TRANSFER -> "Self Transfer"
    }
    ResultCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Caption(
                text = "${tx.txid.take(8)}...${tx.txid.takeLast(8)}",
                color = Colors.Brand,
                modifier = Modifier.weight(1f),
            )
            HorizontalSpacer(8.dp)
            Icon(
                painter = painterResource(R.drawable.ic_copy),
                contentDescription = "Copy txid",
                tint = Colors.Brand,
                modifier = Modifier
                    .size(16.dp)
                    .clickableAlpha(onClick = onCopyTxid),
            )
        }
        InfoRow("Direction", directionLabel)
        InfoRow("Net", "${tx.net} sats")
        tx.fee?.let { InfoRow("Fee", "$it sats") }
        InfoRow("Confirmations", "${tx.confirmations}")
        tx.blockHeight?.let { InfoRow("Block", "$it") }
        tx.timestamp?.let { InfoRow("Time", formatTimestamp(it)) }
    }
}

private fun formatTimestamp(epochSeconds: ULong, locale: Locale = Locale.getDefault()): String = runCatching {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", locale)
    sdf.format(Date(epochSeconds.toLong() * 1000))
}.getOrDefault("$epochSeconds")

@Preview
@Composable
private fun PreviewTransactionHistoryEmpty() {
    AppThemeSurface {
        TransactionHistorySection(
            uiState = TrezorUiState(),
            onInputChange = {},
            onAccountTypeChange = {},
            onLookup = {},
        )
    }
}

@Preview
@Composable
private fun PreviewTransactionHistoryLoading() {
    AppThemeSurface {
        TransactionHistorySection(
            uiState = TrezorUiState(
                txHistory = TrezorTxHistoryState(
                    input = "vpub5Y...",
                    isLoading = true,
                ),
            ),
            onInputChange = {},
            onAccountTypeChange = {},
            onLookup = {},
        )
    }
}

@Preview
@Composable
private fun PreviewTransactionHistoryWithResult() {
    AppThemeSurface {
        TransactionHistorySection(
            uiState = TrezorPreviewData.uiStateWithTxHistory,
            onInputChange = {},
            onAccountTypeChange = {},
            onLookup = {},
        )
    }
}

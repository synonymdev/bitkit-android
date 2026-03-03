package to.bitkit.ui.screens.trezor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.synonym.bitkitcore.AccountInfoResult
import com.synonym.bitkitcore.AccountUtxo
import com.synonym.bitkitcore.SingleAddressInfoResult
import to.bitkit.R
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.copyToClipboard
import to.bitkit.viewmodels.TrezorUiState

@Composable
internal fun BalanceLookupSection(
    uiState: TrezorUiState,
    onInputChange: (String) -> Unit,
    onLookup: () -> Unit,
) {
    Column {
        Text(
            text = "Balance Lookup",
            color = Colors.White64,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = uiState.lookupInput,
            onValueChange = onInputChange,
            label = { Text("Address or xpub", color = Colors.White50) },
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

        Spacer(modifier = Modifier.height(12.dp))

        PrimaryButton(
            text = if (uiState.isLookingUp) "Looking up..." else "Lookup",
            onClick = onLookup,
            enabled = !uiState.isLookingUp && uiState.lookupInput.isNotBlank(),
            size = ButtonSize.Small,
            modifier = Modifier.fillMaxWidth(),
        )

        AnimatedVisibility(visible = uiState.accountInfoResult != null) {
            uiState.accountInfoResult?.let { AccountInfoResultView(it) }
        }

        AnimatedVisibility(visible = uiState.addressInfoResult != null) {
            uiState.addressInfoResult?.let { AddressInfoResultView(it) }
        }
    }
}

@Composable
private fun AccountInfoResultView(result: AccountInfoResult) {
    Column {
        Spacer(modifier = Modifier.height(12.dp))
        ResultCard {
            InfoRow("Account Type", result.accountType.name)
            InfoRow("Balance", "${result.balance} sats")
            InfoRow("UTXO Count", "${result.utxoCount}")
            InfoRow("Block Height", "${result.blockHeight}")
        }

        if (result.account.utxo.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "UTXOs (${result.account.utxo.size})",
                color = Colors.White64,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            result.account.utxo.forEach { utxo ->
                UtxoRow(utxo)
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun AddressInfoResultView(result: SingleAddressInfoResult) {
    val onCopyAddress = copyToClipboard(text = result.address, label = "Address")
    Column {
        Spacer(modifier = Modifier.height(12.dp))
        ResultCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = result.address,
                    color = Colors.Brand,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_copy),
                    contentDescription = "Copy address",
                    tint = Colors.Brand,
                    modifier = Modifier
                        .size(20.dp)
                        .clickableAlpha(onClick = onCopyAddress),
                )
            }
            InfoRow("Balance", "${result.balance} sats")
            InfoRow("UTXOs", "${result.utxos.size}")
            InfoRow("Transfers", "${result.transfers}")
            InfoRow("Block Height", "${result.blockHeight}")
        }

        if (result.utxos.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "UTXOs (${result.utxos.size})",
                color = Colors.White64,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            result.utxos.forEach { utxo ->
                UtxoRow(utxo)
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun UtxoRow(utxo: AccountUtxo) {
    val onCopyTxid = copyToClipboard(text = utxo.txid, label = "TXID")
    ResultCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "${utxo.txid.take(8)}...${utxo.txid.takeLast(8)}:${utxo.vout}",
                color = Colors.Brand,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                painter = painterResource(R.drawable.ic_copy),
                contentDescription = "Copy txid",
                tint = Colors.Brand,
                modifier = Modifier
                    .size(16.dp)
                    .clickableAlpha(onClick = onCopyTxid),
            )
        }
        InfoRow("Amount", "${utxo.amount} sats")
        InfoRow("Confirmations", "${utxo.confirmations}")
        InfoRow("Address", utxo.address)
    }
}

@Composable
private fun ResultCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Colors.White06)
            .padding(12.dp),
    ) {
        content()
    }
}

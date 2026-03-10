package to.bitkit.ui.screens.trezor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.synonym.bitkitcore.AccountInfoResult
import com.synonym.bitkitcore.AccountUtxo
import com.synonym.bitkitcore.SingleAddressInfoResult
import com.synonym.bitkitcore.TrezorSortingStrategy
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

@Suppress("LongParameterList")
@Composable
internal fun BalanceLookupSection(
    uiState: TrezorUiState,
    isDeviceConnected: Boolean,
    onInputChange: (String) -> Unit,
    onLookup: () -> Unit,
    onSendAddressChange: (String) -> Unit,
    onSendAmountChange: (String) -> Unit,
    onSendFeeRateChange: (String) -> Unit,
    onToggleSendMax: () -> Unit,
    onSortingStrategyChange: (TrezorSortingStrategy) -> Unit,
    onCompose: () -> Unit,
    onSign: () -> Unit,
    onBroadcast: () -> Unit,
    onBackToForm: () -> Unit,
    onResetSend: () -> Unit,
) {
    Column {
        Caption13Up(
            text = "Balance Lookup",
            color = Colors.White64,
        )
        VerticalSpacer(8.dp)

        OutlinedTextField(
            value = uiState.lookupInput,
            onValueChange = onInputChange,
            label = { Caption("Address or xpub", color = Colors.White50) },
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

        VerticalSpacer(12.dp)

        PrimaryButton(
            text = if (uiState.isLookingUp) "Looking up..." else "Lookup",
            onClick = onLookup,
            enabled = !uiState.isLookingUp && uiState.lookupInput.isNotBlank(),
            size = ButtonSize.Small,
            modifier = Modifier.fillMaxWidth(),
        )

        AnimatedVisibility(visible = uiState.accountInfoResult != null) {
            uiState.accountInfoResult?.let {
                AccountInfoResultView(
                    result = it,
                    uiState = uiState,
                    isDeviceConnected = isDeviceConnected,
                    onSendAddressChange = onSendAddressChange,
                    onSendAmountChange = onSendAmountChange,
                    onSendFeeRateChange = onSendFeeRateChange,
                    onToggleSendMax = onToggleSendMax,
                    onSortingStrategyChange = onSortingStrategyChange,
                    onCompose = onCompose,
                    onSign = onSign,
                    onBroadcast = onBroadcast,
                    onBackToForm = onBackToForm,
                    onResetSend = onResetSend,
                )
            }
        }

        AnimatedVisibility(visible = uiState.addressInfoResult != null) {
            uiState.addressInfoResult?.let { AddressInfoResultView(it) }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun AccountInfoResultView(
    result: AccountInfoResult,
    uiState: TrezorUiState,
    isDeviceConnected: Boolean,
    onSendAddressChange: (String) -> Unit,
    onSendAmountChange: (String) -> Unit,
    onSendFeeRateChange: (String) -> Unit,
    onToggleSendMax: () -> Unit,
    onSortingStrategyChange: (TrezorSortingStrategy) -> Unit,
    onCompose: () -> Unit,
    onSign: () -> Unit,
    onBroadcast: () -> Unit,
    onBackToForm: () -> Unit,
    onResetSend: () -> Unit,
) {
    Column {
        VerticalSpacer(12.dp)
        ResultCard {
            InfoRow("Account Type", result.accountType.name)
            InfoRow("Balance", "${result.balance} sats")
            InfoRow("UTXO Count", "${result.utxoCount}")
            InfoRow("Block Height", "${result.blockHeight}")
        }

        if (result.account.utxo.isNotEmpty()) {
            VerticalSpacer(8.dp)
            Caption13Up(
                text = "UTXOs (${result.account.utxo.size})",
                color = Colors.White64,
            )
            VerticalSpacer(4.dp)
            result.account.utxo.forEach { utxo ->
                UtxoRow(utxo)
                VerticalSpacer(4.dp)
            }
        }

        if (result.balance > 0uL) {
            VerticalSpacer(16.dp)
            SendTransactionSection(
                uiState = uiState,
                isDeviceConnected = isDeviceConnected,
                onAddressChange = onSendAddressChange,
                onAmountChange = onSendAmountChange,
                onFeeRateChange = onSendFeeRateChange,
                onToggleSendMax = onToggleSendMax,
                onSortingStrategyChange = onSortingStrategyChange,
                onCompose = onCompose,
                onSign = onSign,
                onBroadcast = onBroadcast,
                onBack = onBackToForm,
                onReset = onResetSend,
            )
        }
    }
}

@Composable
private fun AddressInfoResultView(result: SingleAddressInfoResult) {
    val onCopyAddress = copyToClipboard(text = result.address, label = "Address")
    Column {
        VerticalSpacer(12.dp)
        ResultCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Caption(
                    text = result.address,
                    color = Colors.Brand,
                    modifier = Modifier.weight(1f),
                )
                HorizontalSpacer(8.dp)
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
            VerticalSpacer(8.dp)
            Caption13Up(
                text = "UTXOs (${result.utxos.size})",
                color = Colors.White64,
            )
            VerticalSpacer(4.dp)
            result.utxos.forEach { utxo ->
                UtxoRow(utxo)
                VerticalSpacer(4.dp)
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
            Caption(
                text = "${utxo.txid.take(8)}...${utxo.txid.takeLast(8)}:${utxo.vout}",
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
        InfoRow("Amount", "${utxo.amount} sats")
        InfoRow("Confirmations", "${utxo.confirmations}")
        InfoRow("Address", utxo.address)
    }
}

@Composable
internal fun ResultCard(content: @Composable () -> Unit) {
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

@Preview
@Composable
private fun PreviewBalanceLookupEmpty() {
    AppThemeSurface {
        BalanceLookupSection(
            uiState = TrezorUiState(),
            isDeviceConnected = false,
            onInputChange = {},
            onLookup = {},
            onSendAddressChange = {},
            onSendAmountChange = {},
            onSendFeeRateChange = {},
            onToggleSendMax = {},
            onSortingStrategyChange = {},
            onCompose = {},
            onSign = {},
            onBroadcast = {},
            onBackToForm = {},
            onResetSend = {},
        )
    }
}

@Preview
@Composable
private fun PreviewBalanceLookupWithAccountInfo() {
    AppThemeSurface {
        BalanceLookupSection(
            uiState = TrezorPreviewData.uiStateWithAccountInfo,
            isDeviceConnected = true,
            onInputChange = {},
            onLookup = {},
            onSendAddressChange = {},
            onSendAmountChange = {},
            onSendFeeRateChange = {},
            onToggleSendMax = {},
            onSortingStrategyChange = {},
            onCompose = {},
            onSign = {},
            onBroadcast = {},
            onBackToForm = {},
            onResetSend = {},
        )
    }
}

@Preview
@Composable
private fun PreviewBalanceLookupWithAddressInfo() {
    AppThemeSurface {
        BalanceLookupSection(
            uiState = TrezorPreviewData.uiStateWithAddressInfo,
            isDeviceConnected = false,
            onInputChange = {},
            onLookup = {},
            onSendAddressChange = {},
            onSendAmountChange = {},
            onSendFeeRateChange = {},
            onToggleSendMax = {},
            onSortingStrategyChange = {},
            onCompose = {},
            onSign = {},
            onBroadcast = {},
            onBackToForm = {},
            onResetSend = {},
        )
    }
}

@Preview
@Composable
private fun PreviewBalanceLookupLoading() {
    AppThemeSurface {
        BalanceLookupSection(
            uiState = TrezorUiState(lookupInput = "xpub6C...", isLookingUp = true),
            isDeviceConnected = false,
            onInputChange = {},
            onLookup = {},
            onSendAddressChange = {},
            onSendAmountChange = {},
            onSendFeeRateChange = {},
            onToggleSendMax = {},
            onSortingStrategyChange = {},
            onCompose = {},
            onSign = {},
            onBroadcast = {},
            onBackToForm = {},
            onResetSend = {},
        )
    }
}

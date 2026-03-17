package to.bitkit.ui.screens.trezor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.synonym.bitkitcore.CoinSelection
import com.synonym.bitkitcore.ComposeResult
import com.synonym.bitkitcore.TrezorSignedTx
import to.bitkit.R
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.TagButton
import to.bitkit.ui.components.TertiaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.copyToClipboard

private val textFieldColors
    @Composable get() = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Colors.White,
        unfocusedTextColor = Colors.White,
        focusedBorderColor = Colors.Brand,
        unfocusedBorderColor = Colors.White32,
        cursorColor = Colors.Brand,
        disabledTextColor = Colors.White50,
        disabledBorderColor = Colors.White16,
    )

@Composable
internal fun SendTransactionSection(
    uiState: TrezorUiState,
    isDeviceConnected: Boolean,
    onAddressChange: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onFeeRateChange: (String) -> Unit,
    onToggleSendMax: () -> Unit,
    onCoinSelectionChange: (CoinSelection) -> Unit,
    onCompose: () -> Unit,
    onSign: () -> Unit,
    onBroadcast: () -> Unit,
    onBack: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Caption13Up(
            text = "Send Transaction",
            color = Colors.White64,
        )
        VerticalSpacer(8.dp)

        when (uiState.sendStep) {
            SendStep.FORM -> ComposeForm(
                uiState = uiState,
                onAddressChange = onAddressChange,
                onAmountChange = onAmountChange,
                onFeeRateChange = onFeeRateChange,
                onToggleSendMax = onToggleSendMax,
                onCoinSelectionChange = onCoinSelectionChange,
                onCompose = onCompose,
            )
            SendStep.REVIEW -> uiState.composeResult?.let { result ->
                ReviewSection(
                    result = result,
                    isDeviceConnected = isDeviceConnected,
                    isSigning = uiState.isSigning,
                    onSign = onSign,
                    onBack = onBack,
                )
            }
            SendStep.SIGNED -> uiState.signedTxResult?.let { signedTx ->
                SignedResultSection(
                    signedTx = signedTx,
                    isBroadcasting = uiState.isBroadcasting,
                    broadcastTxid = uiState.broadcastTxid,
                    onBroadcast = onBroadcast,
                    onReset = onReset,
                )
            }
        }
    }
}

@Composable
private fun ComposeForm(
    uiState: TrezorUiState,
    onAddressChange: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onFeeRateChange: (String) -> Unit,
    onToggleSendMax: () -> Unit,
    onCoinSelectionChange: (CoinSelection) -> Unit,
    onCompose: () -> Unit,
) {
    Column {
        OutlinedTextField(
            value = uiState.sendAddress,
            onValueChange = onAddressChange,
            label = { Caption("Destination address", color = Colors.White50) },
            colors = textFieldColors,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        VerticalSpacer(8.dp)

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = if (uiState.isSendMax) "MAX" else uiState.sendAmountSats,
                onValueChange = onAmountChange,
                label = { Caption("Amount (sats)", color = Colors.White50) },
                colors = textFieldColors,
                enabled = !uiState.isSendMax,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            TertiaryButton(
                text = "MAX",
                onClick = onToggleSendMax,
                size = ButtonSize.Small,
                fullWidth = false,
            )
        }

        VerticalSpacer(8.dp)

        OutlinedTextField(
            value = uiState.sendFeeRate,
            onValueChange = onFeeRateChange,
            label = { Caption("Fee rate (sat/vB)", color = Colors.White50) },
            colors = textFieldColors,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        VerticalSpacer(16.dp)

        Caption13Up(
            text = "Coin Selection",
            color = Colors.White64,
        )
        VerticalSpacer(4.dp)
        CoinSelectionRow(
            selected = uiState.coinSelection,
            onChange = onCoinSelectionChange,
        )

        VerticalSpacer(16.dp)

        PrimaryButton(
            text = if (uiState.isComposing) "Composing..." else "Compose Transaction",
            onClick = onCompose,
            enabled = !uiState.isComposing &&
                uiState.sendAddress.isNotBlank() &&
                (uiState.isSendMax || uiState.sendAmountSats.isNotBlank()),
            size = ButtonSize.Small,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CoinSelectionRow(
    selected: CoinSelection,
    onChange: (CoinSelection) -> Unit,
) {
    val labels = mapOf(
        CoinSelection.BRANCH_AND_BOUND to "Branch & Bound",
        CoinSelection.LARGEST_FIRST to "Largest First",
        CoinSelection.OLDEST_FIRST to "Oldest First",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CoinSelection.entries.forEach { selection ->
            TagButton(
                text = labels[selection] ?: selection.name,
                onClick = { onChange(selection) },
                isSelected = selection == selected,
            )
        }
    }
}

@Composable
private fun ReviewSection(
    result: ComposeResult.Success,
    isDeviceConnected: Boolean,
    isSigning: Boolean,
    onSign: () -> Unit,
    onBack: () -> Unit,
) {
    val onCopyPsbt = copyToClipboard(text = result.psbt, label = "PSBT")

    Column {
        ResultCard {
            InfoRow("Total Spent", "${result.totalSpent} sats")
            InfoRow("Fee", "${result.fee} sats")
            InfoRow("Fee Rate", "${result.feeRate} sat/vB")
        }

        VerticalSpacer(8.dp)
        Caption13Up(
            text = "PSBT",
            color = Colors.White64,
        )
        VerticalSpacer(4.dp)

        ResultCard {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Caption(
                    text = result.psbt,
                    color = Colors.White,
                    modifier = Modifier.weight(1f),
                )
                HorizontalSpacer(8.dp)
                Icon(
                    painter = painterResource(R.drawable.ic_copy),
                    contentDescription = "Copy PSBT",
                    tint = Colors.Brand,
                    modifier = Modifier
                        .size(20.dp)
                        .clickableAlpha(onClick = onCopyPsbt),
                )
            }
        }

        VerticalSpacer(16.dp)

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            SecondaryButton(
                text = "Back",
                onClick = onBack,
                size = ButtonSize.Small,
                modifier = Modifier.weight(1f),
            )
            PrimaryButton(
                text = if (isSigning) "Signing..." else "Sign with Trezor",
                onClick = onSign,
                enabled = !isSigning && isDeviceConnected,
                size = ButtonSize.Small,
                modifier = Modifier.weight(1f),
            )
        }

        if (!isDeviceConnected) {
            VerticalSpacer(4.dp)
            Caption(text = "Connect a Trezor device to sign")
        }
    }
}

@Composable
private fun SignedResultSection(
    signedTx: TrezorSignedTx,
    isBroadcasting: Boolean,
    broadcastTxid: String?,
    onBroadcast: () -> Unit,
    onReset: () -> Unit,
) {
    val onCopyRawTx = copyToClipboard(text = signedTx.serializedTx, label = "Raw Transaction")

    Column {
        ResultCard {
            InfoRow("Signatures", "${signedTx.signatures.size}")
            signedTx.txid?.let { InfoRow("TXID", it) }
        }

        VerticalSpacer(8.dp)

        Caption13Up(
            text = "Raw Transaction Hex",
            color = Colors.White64,
        )
        VerticalSpacer(4.dp)

        ResultCard {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Caption(
                    text = signedTx.serializedTx,
                    color = Colors.White,
                    modifier = Modifier.weight(1f),
                )
                HorizontalSpacer(8.dp)
                Icon(
                    painter = painterResource(R.drawable.ic_copy),
                    contentDescription = "Copy raw tx",
                    tint = Colors.Brand,
                    modifier = Modifier
                        .size(20.dp)
                        .clickableAlpha(onClick = onCopyRawTx),
                )
            }
        }

        VerticalSpacer(16.dp)

        if (broadcastTxid != null) {
            BroadcastResultCard(txid = broadcastTxid)
            VerticalSpacer(16.dp)
        } else {
            PrimaryButton(
                text = if (isBroadcasting) "Broadcasting..." else "Broadcast",
                onClick = onBroadcast,
                enabled = !isBroadcasting,
                size = ButtonSize.Small,
                modifier = Modifier.fillMaxWidth(),
            )
            VerticalSpacer(8.dp)
        }

        SecondaryButton(
            text = "New Transaction",
            onClick = onReset,
            size = ButtonSize.Small,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BroadcastResultCard(txid: String) {
    val onCopyTxid = copyToClipboard(text = txid, label = "TXID")
    ResultCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Caption13Up(
                text = "Broadcast TXID",
                color = Colors.White64,
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
        Caption(
            text = txid,
            color = Colors.Brand,
        )
    }
}

@Preview
@Composable
private fun PreviewSendForm() {
    AppThemeSurface {
        SendTransactionSection(
            uiState = TrezorUiState(),
            isDeviceConnected = true,
            onAddressChange = {},
            onAmountChange = {},
            onFeeRateChange = {},
            onToggleSendMax = {},
            onCoinSelectionChange = {},
            onCompose = {},
            onSign = {},
            onBroadcast = {},
            onBack = {},
            onReset = {},
        )
    }
}

@Preview
@Composable
private fun PreviewSendFormFilled() {
    AppThemeSurface {
        SendTransactionSection(
            uiState = TrezorUiState(
                sendAddress = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",
                sendAmountSats = "45000",
                sendFeeRate = "5",
            ),
            isDeviceConnected = true,
            onAddressChange = {},
            onAmountChange = {},
            onFeeRateChange = {},
            onToggleSendMax = {},
            onCoinSelectionChange = {},
            onCompose = {},
            onSign = {},
            onBroadcast = {},
            onBack = {},
            onReset = {},
        )
    }
}

@Preview
@Composable
private fun PreviewSendReview() {
    AppThemeSurface {
        SendTransactionSection(
            uiState = TrezorPreviewData.uiStateReview,
            isDeviceConnected = true,
            onAddressChange = {},
            onAmountChange = {},
            onFeeRateChange = {},
            onToggleSendMax = {},
            onCoinSelectionChange = {},
            onCompose = {},
            onSign = {},
            onBroadcast = {},
            onBack = {},
            onReset = {},
        )
    }
}

@Preview
@Composable
private fun PreviewSendSigned() {
    AppThemeSurface {
        SendTransactionSection(
            uiState = TrezorPreviewData.uiStateSigned,
            isDeviceConnected = true,
            onAddressChange = {},
            onAmountChange = {},
            onFeeRateChange = {},
            onToggleSendMax = {},
            onCoinSelectionChange = {},
            onCompose = {},
            onSign = {},
            onBroadcast = {},
            onBack = {},
            onReset = {},
        )
    }
}

@Preview
@Composable
private fun PreviewSendBroadcast() {
    AppThemeSurface {
        SendTransactionSection(
            uiState = TrezorPreviewData.uiStateBroadcast,
            isDeviceConnected = true,
            onAddressChange = {},
            onAmountChange = {},
            onFeeRateChange = {},
            onToggleSendMax = {},
            onCoinSelectionChange = {},
            onCompose = {},
            onSign = {},
            onBroadcast = {},
            onBack = {},
            onReset = {},
        )
    }
}

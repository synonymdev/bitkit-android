package to.bitkit.ui.screens.trezor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.synonym.bitkitcore.TrezorPrecomposedOutput
import com.synonym.bitkitcore.TrezorPrecomposedResult
import com.synonym.bitkitcore.TrezorSignedTx
import com.synonym.bitkitcore.TrezorSortingStrategy
import to.bitkit.R
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Footnote
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.copyToClipboard
import to.bitkit.viewmodels.SendStep
import to.bitkit.viewmodels.TrezorUiState

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
    onSortingStrategyChange: (TrezorSortingStrategy) -> Unit,
    onCompose: () -> Unit,
    onSign: () -> Unit,
    onBroadcast: () -> Unit,
    onBack: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Footnote(
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
                onSortingStrategyChange = onSortingStrategyChange,
                onCompose = onCompose,
            )
            SendStep.REVIEW -> ReviewSection(
                result = uiState.precomposedResult!!,
                isDeviceConnected = isDeviceConnected,
                isSigning = uiState.isSigning,
                onSign = onSign,
                onBack = onBack,
            )
            SendStep.SIGNED -> SignedResultSection(
                signedTx = uiState.signedTxResult!!,
                isBroadcasting = uiState.isBroadcasting,
                broadcastTxid = uiState.broadcastTxid,
                onBroadcast = onBroadcast,
                onReset = onReset,
            )
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
    onSortingStrategyChange: (TrezorSortingStrategy) -> Unit,
    onCompose: () -> Unit,
) {
    Column {
        OutlinedTextField(
            value = uiState.sendAddress,
            onValueChange = onAddressChange,
            label = { Text("Destination address", color = Colors.White50) },
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
                label = { Text("Amount (sats)", color = Colors.White50) },
                colors = textFieldColors,
                enabled = !uiState.isSendMax,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            val maxColor = if (uiState.isSendMax) Colors.Brand else Colors.White32
            Footnote(
                text = "MAX",
                color = maxColor,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(maxColor.copy(alpha = 0.15f))
                    .clickableAlpha(onClick = onToggleSendMax)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }

        VerticalSpacer(8.dp)

        OutlinedTextField(
            value = uiState.sendFeeRate,
            onValueChange = onFeeRateChange,
            label = { Text("Fee rate (sat/vB)", color = Colors.White50) },
            colors = textFieldColors,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        VerticalSpacer(12.dp)

        Text(
            text = "Coin Selection",
            color = Colors.White64,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
        VerticalSpacer(4.dp)
        SortingStrategyRow(
            selected = uiState.sortingStrategy,
            onChange = onSortingStrategyChange,
        )

        VerticalSpacer(12.dp)

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
private fun SortingStrategyRow(
    selected: TrezorSortingStrategy,
    onChange: (TrezorSortingStrategy) -> Unit,
) {
    val labels = mapOf(
        TrezorSortingStrategy.BIP69 to "BIP69",
        TrezorSortingStrategy.RANDOM to "Random",
        TrezorSortingStrategy.NONE to "None",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TrezorSortingStrategy.entries.forEach { strategy ->
            val isSelected = strategy == selected
            val color = if (isSelected) Colors.Brand else Colors.White32
            Footnote(
                text = labels[strategy] ?: strategy.name,
                color = color,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(color.copy(alpha = 0.15f))
                    .clickableAlpha(onClick = { onChange(strategy) })
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ReviewSection(
    result: TrezorPrecomposedResult.Final,
    isDeviceConnected: Boolean,
    isSigning: Boolean,
    onSign: () -> Unit,
    onBack: () -> Unit,
) {
    Column {
        ResultCard {
            InfoRow("Total Spent", "${result.totalSpent} sats")
            InfoRow("Fee", "${result.fee} sats")
            InfoRow("Fee Rate", "${result.feePerByte} sat/vB")
            InfoRow("Size", "${result.bytes} bytes")
            InfoRow("Inputs", "${result.inputs.size}")
            InfoRow("Outputs", "${result.outputs.size}")
        }

        if (result.inputs.isNotEmpty()) {
            VerticalSpacer(8.dp)
            Text(
                text = "Inputs (${result.inputs.size})",
                color = Colors.White64,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            VerticalSpacer(4.dp)
            result.inputs.forEach { input ->
                ResultCard {
                    Text(
                        text = "${input.txid.take(8)}...${input.txid.takeLast(8)}:${input.vout}",
                        color = Colors.Brand,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    InfoRow("Amount", "${input.amount} sats")
                    InfoRow("Path", input.path)
                }
                VerticalSpacer(4.dp)
            }
        }

        if (result.outputs.isNotEmpty()) {
            VerticalSpacer(8.dp)
            Text(
                text = "Outputs (${result.outputs.size})",
                color = Colors.White64,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            VerticalSpacer(4.dp)
            result.outputs.forEach { output ->
                OutputCard(output)
                VerticalSpacer(4.dp)
            }
        }

        VerticalSpacer(12.dp)

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
            Text(
                text = "Connect a Trezor device to sign",
                color = Colors.White32,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun OutputCard(output: TrezorPrecomposedOutput) {
    ResultCard {
        when (output) {
            is TrezorPrecomposedOutput.Payment -> {
                InfoRow("Type", "Payment")
                Text(
                    text = output.address,
                    color = Colors.Brand,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
                InfoRow("Amount", "${output.amount} sats")
            }
            is TrezorPrecomposedOutput.Change -> {
                InfoRow("Type", "Change")
                Text(
                    text = output.address,
                    color = Colors.White64,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
                InfoRow("Amount", "${output.amount} sats")
                InfoRow("Path", output.path)
            }
            is TrezorPrecomposedOutput.OpReturn -> {
                InfoRow("Type", "OP_RETURN")
                InfoRow("Data", output.dataHex)
            }
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

        Text(
            text = "Raw Transaction Hex",
            color = Colors.White64,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
        VerticalSpacer(4.dp)

        ResultCard {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = signedTx.serializedTx,
                    color = Colors.White,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                HorizontalSpacer(8.dp)
                Icon(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.ic_copy),
                    contentDescription = "Copy raw tx",
                    tint = Colors.Brand,
                    modifier = Modifier
                        .size(20.dp)
                        .clickableAlpha(onClick = onCopyRawTx),
                )
            }
        }

        VerticalSpacer(12.dp)

        if (broadcastTxid != null) {
            BroadcastResultCard(txid = broadcastTxid)
            VerticalSpacer(12.dp)
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
            Text(
                text = "Broadcast TXID",
                color = Colors.White64,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            HorizontalSpacer(8.dp)
            Icon(
                painter = androidx.compose.ui.res.painterResource(R.drawable.ic_copy),
                contentDescription = "Copy txid",
                tint = Colors.Brand,
                modifier = Modifier
                    .size(16.dp)
                    .clickableAlpha(onClick = onCopyTxid),
            )
        }
        Text(
            text = txid,
            color = Colors.Brand,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 14.sp,
        )
    }
}

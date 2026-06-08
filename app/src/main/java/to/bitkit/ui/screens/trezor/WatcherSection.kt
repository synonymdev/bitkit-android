package to.bitkit.ui.screens.trezor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.synonym.bitkitcore.AccountType
import com.synonym.bitkitcore.TxDirection
import to.bitkit.models.safe
import to.bitkit.repositories.TrezorState
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.Footnote
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.TagButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Suppress("LongParameterList")
@Composable
internal fun WatcherSection(
    uiState: TrezorUiState,
    trezorState: TrezorState,
    onExtendedKeyChange: (String) -> Unit,
    onGapLimitChange: (String) -> Unit,
    onAccountTypeChange: (AccountType?) -> Unit,
    onStartWatcher: () -> Unit,
    onStopWatcher: () -> Unit,
    onPopulateFromXpub: () -> Unit,
) {
    Column {
        Caption13Up(
            text = "Event Watcher",
            color = Colors.White64,
        )
        VerticalSpacer(8.dp)

        OutlinedTextField(
            value = uiState.watcherExtendedKey,
            onValueChange = onExtendedKeyChange,
            label = { Caption("Extended key (xpub/tpub/...)", color = Colors.White50) },
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

        AnimatedVisibility(visible = trezorState.lastPublicKey != null) {
            Column {
                SecondaryButton(
                    text = "Use xpub from device",
                    onClick = onPopulateFromXpub,
                    size = ButtonSize.Small,
                )
                VerticalSpacer(8.dp)
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = uiState.watcherGapLimit,
                onValueChange = onGapLimitChange,
                label = { Caption("Gap limit", color = Colors.White50) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Colors.White,
                    unfocusedTextColor = Colors.White,
                    focusedBorderColor = Colors.Brand,
                    unfocusedBorderColor = Colors.White32,
                    cursorColor = Colors.Brand,
                ),
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }

        VerticalSpacer(8.dp)

        AccountTypeSelectorRow(
            selectedAccountType = uiState.watcherSelectedAccountType,
            onAccountTypeChange = onAccountTypeChange,
        )

        VerticalSpacer(16.dp)

        if (uiState.activeWatcherId != null) {
            SecondaryButton(
                text = "Stop Watching",
                onClick = onStopWatcher,
                enabled = !uiState.isStartingWatcher,
                size = ButtonSize.Small,
            )
        } else {
            PrimaryButton(
                text = if (uiState.isStartingWatcher) "Starting..." else "Start Watching",
                onClick = onStartWatcher,
                enabled = !uiState.isStartingWatcher && uiState.watcherExtendedKey.isNotBlank(),
                size = ButtonSize.Small,
            )
        }

        AnimatedVisibility(
            visible = uiState.isStartingWatcher || uiState.activeWatcherId != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column {
                VerticalSpacer(16.dp)
                WatcherStatusContent(uiState)
            }
        }
    }
}

private fun AccountType?.label(): String = when (this) {
    null -> "Auto"
    AccountType.LEGACY -> "Legacy"
    AccountType.WRAPPED_SEGWIT -> "Wrapped"
    AccountType.NATIVE_SEGWIT -> "Native"
    AccountType.TAPROOT -> "Taproot"
}

@Composable
private fun AccountTypeSelectorRow(
    selectedAccountType: AccountType?,
    onAccountTypeChange: (AccountType?) -> Unit,
) {
    Column {
        Caption("Account type (Auto = detect from key prefix)", color = Colors.White50)
        VerticalSpacer(8.dp)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            val options = listOf(null) + AccountType.entries
            options.forEach { type ->
                TagButton(
                    text = type.label(),
                    onClick = { onAccountTypeChange(type) },
                    isSelected = type == selectedAccountType,
                )
            }
        }
    }
}

private fun WatcherConnectionStatus.toColor(): Color = when (this) {
    WatcherConnectionStatus.IDLE -> Colors.White50
    WatcherConnectionStatus.STARTING -> Colors.Yellow
    WatcherConnectionStatus.CONNECTED -> Colors.Green
    WatcherConnectionStatus.DISCONNECTED -> Colors.Yellow
    WatcherConnectionStatus.ERROR -> Colors.Red
}

@Composable
private fun WatcherStatusContent(uiState: TrezorUiState) {
    StatusBadge(
        text = uiState.watcherConnectionStatus.name,
        color = uiState.watcherConnectionStatus.toColor(),
    )

    uiState.watcherBalance?.let { balance ->
        VerticalSpacer(12.dp)
        ResultCard {
            val pending = balance.trustedPending.safe() + balance.untrustedPending.safe()
            InfoRow("Confirmed", "${balance.confirmed} sats")
            InfoRow("Pending", "$pending sats")
            InfoRow("Total", "${balance.total} sats")
            InfoRow("Block Height", "${uiState.watcherBlockHeight}")
            InfoRow("Account Type", uiState.watcherAccountType?.name ?: "-")
            InfoRow("Transactions", "${uiState.watcherTransactionCount}")
        }
    }

    if (uiState.watcherTransactions.isNotEmpty()) {
        VerticalSpacer(12.dp)
        Caption13Up(
            text = "Transactions (${uiState.watcherTransactions.size})",
            color = Colors.White64,
        )
        VerticalSpacer(4.dp)
        LazyColumn(
            modifier = Modifier.heightIn(max = 200.dp),
        ) {
            items(uiState.watcherTransactions) { tx ->
                val directionLabel = when (tx.direction) {
                    TxDirection.SENT -> "Sent"
                    TxDirection.RECEIVED -> "Recv"
                    TxDirection.SELF_TRANSFER -> "Self"
                }
                val directionColor = when (tx.direction) {
                    TxDirection.SENT -> Colors.Red
                    TxDirection.RECEIVED -> Colors.Green
                    TxDirection.SELF_TRANSFER -> Colors.White64
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Caption(
                        text = "$directionLabel ${tx.amount} sats",
                        color = directionColor,
                    )
                    HorizontalSpacer(8.dp)
                    Caption(
                        text = "${tx.txid.take(8)}...${tx.txid.takeLast(8)}",
                        color = Colors.White50,
                    )
                    HorizontalSpacer(8.dp)
                    Caption(
                        text = "${tx.confirmations} conf",
                        color = Colors.White50,
                    )
                }
            }
        }
    }

    if (uiState.watcherEvents.isNotEmpty()) {
        VerticalSpacer(12.dp)
        Caption13Up(
            text = "Event Log",
            color = Colors.White64,
        )
        VerticalSpacer(4.dp)
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 150.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Colors.Black.copy(alpha = 0.5f))
                .padding(8.dp),
        ) {
            items(uiState.watcherEvents.asReversed()) { event ->
                Footnote(
                    text = event,
                    color = Colors.White80,
                )
            }
        }
    }
}

@Preview
@Composable
private fun PreviewWatcherEmpty() {
    AppThemeSurface {
        WatcherSection(
            uiState = TrezorUiState(),
            trezorState = TrezorState(),
            onExtendedKeyChange = {},
            onGapLimitChange = {},
            onAccountTypeChange = {},
            onStartWatcher = {},
            onStopWatcher = {},
            onPopulateFromXpub = {},
        )
    }
}

@Preview
@Composable
private fun PreviewWatcherActive() {
    AppThemeSurface {
        WatcherSection(
            uiState = TrezorPreviewData.uiStateWithActiveWatcher,
            trezorState = TrezorState(),
            onExtendedKeyChange = {},
            onGapLimitChange = {},
            onAccountTypeChange = {},
            onStartWatcher = {},
            onStopWatcher = {},
            onPopulateFromXpub = {},
        )
    }
}

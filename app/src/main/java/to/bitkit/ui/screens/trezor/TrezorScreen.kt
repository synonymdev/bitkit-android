package to.bitkit.ui.screens.trezor

import android.Manifest
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.synonym.bitkitcore.AccountType
import com.synonym.bitkitcore.CoinSelection
import kotlinx.collections.immutable.toImmutableList
import to.bitkit.R
import to.bitkit.models.KnownDevice
import to.bitkit.repositories.ConnectedTrezorDevice
import to.bitkit.repositories.TrezorState
import to.bitkit.services.TrezorDebugLog
import to.bitkit.services.TrezorWalletMode
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.CaptionB
import to.bitkit.ui.components.Footnote
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.TagButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.copyToClipboard
import com.synonym.bitkitcore.Network as BitkitCoreNetwork

private val bluetoothPermissions: List<String>
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    } else {
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

@Composable
fun TrezorScreen(navController: NavController) {
    TrezorScreenContent(onBack = { navController.popBackStack() })
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun TrezorScreenContent(
    viewModel: TrezorViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
) {
    val trezorState by viewModel.trezorState.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val needsPinEntry by viewModel.needsPinEntry.collectAsStateWithLifecycle()
    val walletMode by viewModel.walletMode.collectAsStateWithLifecycle()

    val permissionsState = rememberMultiplePermissionsState(bluetoothPermissions)

    val onScanWithPermissions: () -> Unit = {
        if (permissionsState.allPermissionsGranted) {
            viewModel.scan()
        } else {
            permissionsState.launchMultiplePermissionRequest()
        }
    }

    if (needsPinEntry) {
        PinEntryDialog(
            onSubmit = viewModel::submitPin,
            onCancel = viewModel::cancelPin,
        )
    }

    ScreenColumn {
        AppTopBar(
            titleText = "Trezor",
            onBackClick = onBack,
            actions = { DrawerNavIcon() },
        )
        Content(
            trezorState = trezorState,
            uiState = uiState,
            onScan = onScanWithPermissions,
            onConnectNearby = viewModel::connect,
            onConnectKnown = viewModel::connectKnownDevice,
            onForgetDevice = viewModel::forgetDevice,
            walletMode = walletMode,
            onSetWalletMode = viewModel::setWalletMode,
            onGetAddress = viewModel::getAddress,
            onGetPublicKey = viewModel::getPublicKey,
            onIncrementIndex = viewModel::incrementAddressIndex,
            onDisconnect = viewModel::disconnect,
            onSignMessage = viewModel::signMessage,
            onVerifyMessage = viewModel::verifyMessage,
            onMessageChange = viewModel::setMessageToSign,
            onClearError = viewModel::clearError,
            onLookupInputChange = viewModel::setLookupInput,
            onLookupAccountTypeChange = viewModel::setLookupAccountType,
            onLookup = viewModel::lookupBalanceInfo,
            onNetworkChange = viewModel::setSelectedNetwork,
            onSendAddressChange = viewModel::setSendAddress,
            onSendAmountChange = viewModel::setSendAmount,
            onSendFeeRateChange = viewModel::setSendFeeRate,
            onToggleSendMax = viewModel::toggleSendMax,
            onCoinSelectionChange = viewModel::setCoinSelection,
            onCompose = viewModel::composeTx,
            onSign = viewModel::signComposedTx,
            onBroadcast = viewModel::broadcastSignedTx,
            onBackToForm = viewModel::backToComposeForm,
            onResetSend = viewModel::resetSendFlow,
            onTxHistoryInputChange = viewModel::setTxHistoryInput,
            onTxHistoryAccountTypeChange = viewModel::setTxHistoryAccountType,
            onLookupTxHistory = viewModel::lookupTransactionHistory,
            onWatcherExtendedKeyChange = viewModel::setWatcherExtendedKey,
            onWatcherGapLimitChange = viewModel::setWatcherGapLimit,
            onWatcherAccountTypeChange = viewModel::setWatcherAccountType,
            onStartWatcher = viewModel::startWatcher,
            onStopWatcher = viewModel::stopWatcher,
            onPopulateWatcherFromXpub = viewModel::populateWatcherFromXpub,
            permissionsGranted = permissionsState.allPermissionsGranted,
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun Content(
    trezorState: TrezorState,
    uiState: TrezorUiState,
    onScan: () -> Unit = {},
    onConnectNearby: (String) -> Unit = {},
    onConnectKnown: (String) -> Unit = {},
    onForgetDevice: (KnownDevice) -> Unit = {},
    walletMode: TrezorWalletMode = TrezorWalletMode.STANDARD,
    onSetWalletMode: (TrezorWalletMode, String) -> Unit = { _, _ -> },
    onGetAddress: (Boolean) -> Unit = {},
    onGetPublicKey: (Boolean) -> Unit = {},
    onIncrementIndex: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    onSignMessage: () -> Unit = {},
    onVerifyMessage: () -> Unit = {},
    onMessageChange: (String) -> Unit = {},
    onClearError: () -> Unit = {},
    onLookupInputChange: (String) -> Unit = {},
    onLookupAccountTypeChange: (AccountType?) -> Unit = {},
    onLookup: () -> Unit = {},
    onNetworkChange: (BitkitCoreNetwork) -> Unit = {},
    onSendAddressChange: (String) -> Unit = {},
    onSendAmountChange: (String) -> Unit = {},
    onSendFeeRateChange: (String) -> Unit = {},
    onToggleSendMax: () -> Unit = {},
    onCoinSelectionChange: (CoinSelection) -> Unit = {},
    onCompose: () -> Unit = {},
    onSign: () -> Unit = {},
    onBroadcast: () -> Unit = {},
    onBackToForm: () -> Unit = {},
    onResetSend: () -> Unit = {},
    onTxHistoryInputChange: (String) -> Unit = {},
    onTxHistoryAccountTypeChange: (AccountType?) -> Unit = {},
    onLookupTxHistory: () -> Unit = {},
    onWatcherExtendedKeyChange: (String) -> Unit = {},
    onWatcherGapLimitChange: (String) -> Unit = {},
    onWatcherAccountTypeChange: (AccountType?) -> Unit = {},
    onStartWatcher: () -> Unit = {},
    onStopWatcher: () -> Unit = {},
    onPopulateWatcherFromXpub: () -> Unit = {},
    permissionsGranted: Boolean = true,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .imePadding()
            .verticalScroll(rememberScrollState())
    ) {
        Caption13Up("TREZOR TEST", color = Colors.White64)
        VerticalSpacer(8.dp)
        NetworkSelectorRow(
            selectedNetwork = uiState.selectedNetwork,
            onNetworkChange = onNetworkChange,
        )
        VerticalSpacer(16.dp)

        Card(
            colors = CardDefaults.cardColors(containerColor = Colors.White08),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                StatusRow(trezorState)

                VerticalSpacer(16.dp)

                ActionButtonsRow(
                    trezorState = trezorState,
                    onScan = onScan,
                    onDisconnect = onDisconnect,
                    permissionsGranted = permissionsGranted,
                )

                if (trezorState.connected != null) {
                    WalletModeRow(
                        walletMode = walletMode,
                        passphraseEntryCapable = trezorState.connectedDevice()?.passphraseEntryCapable == true,
                        onSetWalletMode = onSetWalletMode,
                    )
                }

                // Known Devices Section
                AnimatedVisibility(
                    visible = trezorState.knownDevices.isNotEmpty(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Column {
                        VerticalSpacer(32.dp)
                        Caption13Up(
                            text = "My Devices (${trezorState.knownDevices.size})",
                            color = Colors.White64,
                        )
                        VerticalSpacer(8.dp)
                        trezorState.knownDevices.forEach { device ->
                            val isConnected = trezorState.connectedDeviceId() == device.id
                            KnownDeviceCard(
                                device = device,
                                isConnected = isConnected,
                                onClick = {
                                    if (!isConnected && !trezorState.isConnecting) {
                                        onConnectKnown(device.id)
                                    }
                                },
                                onForget = { onForgetDevice(device) },
                            )
                            VerticalSpacer(8.dp)
                        }
                    }
                }

                // Nearby Devices Section
                AnimatedVisibility(
                    visible = trezorState.nearbyDevices.isNotEmpty(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Column {
                        VerticalSpacer(32.dp)
                        Caption13Up(
                            text = "New Devices (${trezorState.nearbyDevices.size})",
                            color = Colors.White64,
                        )
                        VerticalSpacer(8.dp)
                        trezorState.nearbyDevices.forEach { device ->
                            DeviceCard(
                                device = device,
                                onClick = {
                                    if (!trezorState.isConnecting) {
                                        onConnectNearby(device.id)
                                    }
                                },
                            )
                            VerticalSpacer(8.dp)
                        }
                    }
                }

                // Connected Device Info
                AnimatedVisibility(
                    visible = trezorState.connectedDevice() != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    trezorState.connectedDevice()?.let { features ->
                        Column {
                            VerticalSpacer(32.dp)
                            Caption13Up(
                                text = "Connected Device",
                                color = Colors.White64,
                            )
                            VerticalSpacer(8.dp)

                            ConnectedDeviceInfo(features)

                            VerticalSpacer(32.dp)

                            AddressSection(
                                trezorState = trezorState,
                                uiState = uiState,
                                onGetAddress = onGetAddress,
                                onIncrementIndex = onIncrementIndex,
                            )

                            VerticalSpacer(32.dp)

                            PublicKeySection(
                                trezorState = trezorState,
                                uiState = uiState,
                                onGetPublicKey = onGetPublicKey,
                            )

                            VerticalSpacer(32.dp)

                            SignMessageSection(
                                uiState = uiState,
                                onMessageChange = onMessageChange,
                                onSignMessage = onSignMessage,
                                onVerifyMessage = onVerifyMessage,
                            )
                        }
                    }
                }

                // Error Display
                AnimatedVisibility(visible = trezorState.error != null) {
                    trezorState.error?.let { error ->
                        val onCopyError = copyToClipboard(text = error, label = "Trezor Error")
                        Column {
                            VerticalSpacer(32.dp)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Colors.Red.copy(alpha = 0.1f))
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                Caption(
                                    text = error,
                                    color = Colors.Red,
                                    modifier = Modifier.weight(1f),
                                )
                                HorizontalSpacer(8.dp)
                                Icon(
                                    painter = painterResource(R.drawable.ic_copy),
                                    contentDescription = "Copy error",
                                    tint = Colors.Red,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clickableAlpha(onClick = onCopyError)
                                )
                                HorizontalSpacer(8.dp)
                                Icon(
                                    painter = painterResource(R.drawable.ic_x),
                                    contentDescription = "Dismiss error",
                                    tint = Colors.Red,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clickableAlpha(onClick = onClearError)
                                )
                            }
                        }
                    }
                }

                // Balance Lookup (always visible, no device needed)
                VerticalSpacer(32.dp)
                BalanceLookupSection(
                    uiState = uiState,
                    isDeviceConnected = trezorState.connectedDevice() != null,
                    onInputChange = onLookupInputChange,
                    onAccountTypeChange = onLookupAccountTypeChange,
                    onLookup = onLookup,
                    onSendAddressChange = onSendAddressChange,
                    onSendAmountChange = onSendAmountChange,
                    onSendFeeRateChange = onSendFeeRateChange,
                    onToggleSendMax = onToggleSendMax,
                    onCoinSelectionChange = onCoinSelectionChange,
                    onCompose = onCompose,
                    onSign = onSign,
                    onBroadcast = onBroadcast,
                    onBackToForm = onBackToForm,
                    onResetSend = onResetSend,
                )

                // Transaction History (one-shot snapshot, no device needed)
                VerticalSpacer(32.dp)
                TransactionHistorySection(
                    uiState = uiState,
                    onInputChange = onTxHistoryInputChange,
                    onAccountTypeChange = onTxHistoryAccountTypeChange,
                    onLookup = onLookupTxHistory,
                )

                // Event Watcher (live subscription, no device needed)
                VerticalSpacer(32.dp)
                WatcherSection(
                    uiState = uiState,
                    trezorState = trezorState,
                    onExtendedKeyChange = onWatcherExtendedKeyChange,
                    onGapLimitChange = onWatcherGapLimitChange,
                    onAccountTypeChange = onWatcherAccountTypeChange,
                    onStartWatcher = onStartWatcher,
                    onStopWatcher = onStopWatcher,
                    onPopulateFromXpub = onPopulateWatcherFromXpub,
                )

                // Debug Log Window
                DebugLogSection()
            }
        }
    }
}

@Composable
private fun WalletModeRow(
    walletMode: TrezorWalletMode,
    passphraseEntryCapable: Boolean,
    onSetWalletMode: (TrezorWalletMode, String) -> Unit,
) {
    var showChooser by remember { mutableStateOf(false) }
    var showHostEntry by remember { mutableStateOf(false) }

    Column {
        VerticalSpacer(16.dp)
        Caption13Up("WALLET", color = Colors.White64)
        VerticalSpacer(8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TagButton(
                text = "Standard",
                onClick = { onSetWalletMode(TrezorWalletMode.STANDARD, "") },
                isSelected = walletMode == TrezorWalletMode.STANDARD,
            )
            TagButton(
                text = "Passphrase",
                onClick = {
                    // Capable devices can enter on-device, so offer the choice;
                    // otherwise go straight to host passphrase entry.
                    if (passphraseEntryCapable) showChooser = true else showHostEntry = true
                },
                isSelected = walletMode != TrezorWalletMode.STANDARD,
            )
        }
        VerticalSpacer(4.dp)
        Footnote(
            text = when (walletMode) {
                TrezorWalletMode.STANDARD -> "Standard wallet (no passphrase)"
                TrezorWalletMode.PASSPHRASE_HOST -> "Hidden wallet — passphrase entered on this phone"
                TrezorWalletMode.PASSPHRASE_DEVICE -> "Hidden wallet — passphrase entered on the Trezor"
            },
            color = Colors.White64,
        )
    }

    if (showChooser) {
        WalletModeChooserDialog(
            onHost = {
                showChooser = false
                showHostEntry = true
            },
            onTrezor = {
                showChooser = false
                onSetWalletMode(TrezorWalletMode.PASSPHRASE_DEVICE, "")
            },
            onCancel = { showChooser = false },
        )
    }

    if (showHostEntry) {
        // Collect the passphrase up front — on THP it's bound at session creation.
        // Only offer the "enter on Trezor" shortcut when the device supports it;
        // otherwise picking it would select PASSPHRASE_DEVICE and fail to connect.
        PassphraseDialog(
            allowDeviceEntry = passphraseEntryCapable,
            onSubmit = { passphrase ->
                showHostEntry = false
                onSetWalletMode(TrezorWalletMode.PASSPHRASE_HOST, passphrase)
            },
            onUseTrezor = {
                showHostEntry = false
                onSetWalletMode(TrezorWalletMode.PASSPHRASE_DEVICE, "")
            },
            onCancel = { showHostEntry = false },
        )
    }
}

@Composable
private fun NetworkSelectorRow(
    selectedNetwork: BitkitCoreNetwork,
    onNetworkChange: (BitkitCoreNetwork) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        BitkitCoreNetwork.entries.filter { it != BitkitCoreNetwork.SIGNET && it != BitkitCoreNetwork.TESTNET4 }
            .forEach { network ->
                TagButton(
                    text = network.name,
                    onClick = { onNetworkChange(network) },
                    isSelected = network == selectedNetwork,
                )
            }
    }
}

@Composable
private fun DebugLogSection() {
    var expanded by remember { mutableStateOf(false) }
    val debugLines by TrezorDebugLog.lines.collectAsStateWithLifecycle()

    Column {
        VerticalSpacer(16.dp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SecondaryButton(
                text = if (expanded) "Hide (${debugLines.size})" else "Show Log (${debugLines.size})",
                onClick = { expanded = !expanded },
                size = ButtonSize.Small,
                modifier = Modifier.weight(1f),
            )
            if (expanded) {
                val onCopyLogs = copyToClipboard(
                    text = debugLines.joinToString("\n"),
                    label = "Trezor Debug Log",
                )
                SecondaryButton(
                    text = "Copy",
                    onClick = onCopyLogs,
                    size = ButtonSize.Small,
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(
                    text = "Clear",
                    onClick = { TrezorDebugLog.clear() },
                    size = ButtonSize.Small,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            val listState = rememberLazyListState()

            LaunchedEffect(debugLines.size) {
                if (debugLines.isNotEmpty()) {
                    listState.animateScrollToItem(debugLines.size - 1)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Colors.Black.copy(alpha = 0.5f))
                    .padding(8.dp),
            ) {
                items(debugLines) { line ->
                    Footnote(
                        text = line,
                        color = Colors.White80,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusRow(trezorState: TrezorState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_settings_dev),
                contentDescription = null,
                tint = Colors.White80,
                modifier = Modifier.size(20.dp)
            )
            HorizontalSpacer(8.dp)
            CaptionB(
                text = "Trezor",
                color = Colors.White,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                trezorState.isAutoReconnecting -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Colors.Brand
                    )
                    HorizontalSpacer(8.dp)
                    Caption("Reconnecting...", color = Colors.White64)
                }

                trezorState.isScanning -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Colors.Brand
                    )
                    HorizontalSpacer(8.dp)
                    Caption("Scanning...", color = Colors.White64)
                }

                trezorState.isConnecting -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Colors.Brand
                    )
                    HorizontalSpacer(8.dp)
                    Caption("Connecting...", color = Colors.White64)
                }

                trezorState.connectedDevice() != null -> {
                    StatusBadge(text = "Connected", color = Colors.Green)
                }

                else -> {
                    StatusBadge(text = "Ready", color = Colors.Brand)
                }
            }
        }
    }
}

@Composable
internal fun StatusBadge(text: String, color: Color) {
    Caption(
        text = text,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun ActionButtonsRow(
    trezorState: TrezorState,
    onScan: () -> Unit,
    onDisconnect: () -> Unit,
    permissionsGranted: Boolean = true,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (trezorState.isAutoReconnecting) return@Row
        if (trezorState.connectedDevice() != null) {
            SecondaryButton(
                text = "Disconnect",
                onClick = onDisconnect,
                size = ButtonSize.Small,
                modifier = Modifier.weight(1f)
            )
            PrimaryButton(
                text = if (permissionsGranted) "Scan" else "Grant Permissions",
                onClick = onScan,
                enabled = !trezorState.isScanning,
                size = ButtonSize.Small,
                modifier = Modifier.weight(1f)
            )
        } else {
            PrimaryButton(
                text = if (permissionsGranted) "Scan" else "Grant Permissions",
                onClick = onScan,
                enabled = !trezorState.isScanning,
                size = ButtonSize.Small,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Preview
@Composable
private fun PreviewReady() {
    AppThemeSurface {
        Content(
            trezorState = TrezorState(),
            uiState = TrezorUiState(),
        )
    }
}

@Preview
@Composable
private fun PreviewScanning() {
    AppThemeSurface {
        Content(
            trezorState = TrezorState(isScanning = true),
            uiState = TrezorUiState(),
        )
    }
}

@Preview
@Composable
private fun PreviewWithDevices() {
    AppThemeSurface {
        Content(
            trezorState = TrezorState(
                knownDevices = listOf(TrezorPreviewData.sampleKnownDevice).toImmutableList(),
                nearbyDevices = listOf(TrezorPreviewData.sampleNearbyDevice).toImmutableList(),
                connected = ConnectedTrezorDevice(
                    id = TrezorPreviewData.sampleKnownDevice.id,
                    features = TrezorPreviewData.sampleFeatures,
                ),
            ),
            uiState = TrezorUiState(),
        )
    }
}

@Preview
@Composable
private fun PreviewConnectedWithData() {
    AppThemeSurface {
        Content(
            trezorState = TrezorPreviewData.connectedStateWithResults,
            uiState = TrezorPreviewData.uiStateWithSignature,
        )
    }
}

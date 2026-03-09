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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.synonym.bitkitcore.TrezorCoinType
import com.synonym.bitkitcore.TrezorDeviceInfo
import com.synonym.bitkitcore.TrezorSortingStrategy
import com.synonym.bitkitcore.TrezorTransportType
import to.bitkit.R
import to.bitkit.repositories.KnownDevice
import to.bitkit.repositories.TrezorState
import to.bitkit.services.TrezorDebugLog
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.copyToClipboard
import to.bitkit.viewmodels.TrezorUiState
import to.bitkit.viewmodels.TrezorViewModel

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
    val needsPairingCode by viewModel.needsPairingCode.collectAsStateWithLifecycle()

    val permissionsState = rememberMultiplePermissionsState(bluetoothPermissions)

    LaunchedEffect(Unit) {
        viewModel.initialize()
    }

    val onScanWithPermissions: () -> Unit = {
        if (permissionsState.allPermissionsGranted) {
            viewModel.scan()
        } else {
            permissionsState.launchMultiplePermissionRequest()
        }
    }

    if (needsPairingCode) {
        PairingCodeDialog(
            onSubmit = viewModel::submitPairingCode,
            onCancel = viewModel::cancelPairingCode,
        )
    }

    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.settings__adv__trezor),
            onBackClick = onBack,
            actions = { DrawerNavIcon() },
        )
        TrezorContent(
            trezorState = trezorState,
            uiState = uiState,
            onInitialize = viewModel::initialize,
            onScan = onScanWithPermissions,
            onConnectNearby = viewModel::connect,
            onConnectKnown = viewModel::connectKnownDevice,
            onForgetDevice = viewModel::forgetDevice,
            onGetAddress = viewModel::getAddress,
            onGetPublicKey = viewModel::getPublicKey,
            onIncrementIndex = viewModel::incrementAddressIndex,
            onDisconnect = viewModel::disconnect,
            onSignMessage = viewModel::signMessage,
            onVerifyMessage = viewModel::verifyMessage,
            onMessageChange = viewModel::setMessageToSign,
            onClearError = viewModel::clearError,
            onLookupInputChange = viewModel::setLookupInput,
            onLookup = viewModel::lookupBalanceInfo,
            onNetworkChange = viewModel::setSelectedNetwork,
            onSendAddressChange = viewModel::setSendAddress,
            onSendAmountChange = viewModel::setSendAmount,
            onSendFeeRateChange = viewModel::setSendFeeRate,
            onToggleSendMax = viewModel::toggleSendMax,
            onSortingStrategyChange = viewModel::setSortingStrategy,
            onCompose = viewModel::composeTx,
            onSign = viewModel::signComposedTx,
            onBroadcast = viewModel::broadcastSignedTx,
            onBackToForm = viewModel::backToComposeForm,
            onResetSend = viewModel::resetSendFlow,
            permissionsGranted = permissionsState.allPermissionsGranted,
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun TrezorContent(
    trezorState: TrezorState,
    uiState: TrezorUiState,
    onInitialize: () -> Unit = {},
    onScan: () -> Unit = {},
    onConnectNearby: (String) -> Unit = {},
    onConnectKnown: (String) -> Unit = {},
    onForgetDevice: (KnownDevice) -> Unit = {},
    onGetAddress: (Boolean) -> Unit = {},
    onGetPublicKey: (Boolean) -> Unit = {},
    onIncrementIndex: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    onSignMessage: () -> Unit = {},
    onVerifyMessage: () -> Unit = {},
    onMessageChange: (String) -> Unit = {},
    onClearError: () -> Unit = {},
    onLookupInputChange: (String) -> Unit = {},
    onLookup: () -> Unit = {},
    onNetworkChange: (TrezorCoinType) -> Unit = {},
    onSendAddressChange: (String) -> Unit = {},
    onSendAmountChange: (String) -> Unit = {},
    onSendFeeRateChange: (String) -> Unit = {},
    onToggleSendMax: () -> Unit = {},
    onSortingStrategyChange: (TrezorSortingStrategy) -> Unit = {},
    onCompose: () -> Unit = {},
    onSign: () -> Unit = {},
    onBroadcast: () -> Unit = {},
    onBackToForm: () -> Unit = {},
    onResetSend: () -> Unit = {},
    permissionsGranted: Boolean = true,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .verticalScroll(rememberScrollState())
    ) {
        Text13Up("TREZOR TEST", color = Colors.White64)
        VerticalSpacer(8.dp)
        NetworkSelectorRow(
            selectedNetwork = uiState.selectedNetwork,
            onNetworkChange = onNetworkChange,
        )
        VerticalSpacer(12.dp)

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

                Spacer(modifier = Modifier.height(16.dp))

                ActionButtonsRow(
                    trezorState = trezorState,
                    onInitialize = onInitialize,
                    onScan = onScan,
                    onDisconnect = onDisconnect,
                    permissionsGranted = permissionsGranted,
                )

                // Known Devices Section
                AnimatedVisibility(
                    visible = trezorState.knownDevices.isNotEmpty(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Column {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "My Devices (${trezorState.knownDevices.size})",
                            color = Colors.White64,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        trezorState.knownDevices.forEach { device ->
                            val isConnected = trezorState.connectedDeviceId == device.id
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
                            Spacer(modifier = Modifier.height(8.dp))
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
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "New Devices (${trezorState.nearbyDevices.size})",
                            color = Colors.White64,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        trezorState.nearbyDevices.forEach { device ->
                            DeviceCard(
                                device = device,
                                onClick = {
                                    if (!trezorState.isConnecting) {
                                        onConnectNearby(device.id)
                                    }
                                },
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }

                // Connected Device Info
                AnimatedVisibility(
                    visible = trezorState.connectedDevice != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    trezorState.connectedDevice?.let { features ->
                        Column {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Connected Device",
                                color = Colors.White64,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            ConnectedDeviceInfo(features)

                            Spacer(modifier = Modifier.height(16.dp))

                            AddressSection(
                                trezorState = trezorState,
                                uiState = uiState,
                                onGetAddress = onGetAddress,
                                onIncrementIndex = onIncrementIndex,
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            PublicKeySection(
                                trezorState = trezorState,
                                uiState = uiState,
                                onGetPublicKey = onGetPublicKey,
                            )

                            Spacer(modifier = Modifier.height(16.dp))

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
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Colors.Red.copy(alpha = 0.1f))
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    text = error,
                                    color = Colors.Red,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    painter = painterResource(R.drawable.ic_copy),
                                    contentDescription = "Copy error",
                                    tint = Colors.Red,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clickableAlpha(onClick = onCopyError)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
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
                Spacer(modifier = Modifier.height(16.dp))
                BalanceLookupSection(
                    uiState = uiState,
                    isDeviceConnected = trezorState.connectedDevice != null,
                    onInputChange = onLookupInputChange,
                    onLookup = onLookup,
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

                // Debug Log Window
                DebugLogSection()
            }
        }
    }
}

@Composable
private fun NetworkSelectorRow(
    selectedNetwork: TrezorCoinType,
    onNetworkChange: (TrezorCoinType) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        TrezorCoinType.entries.filter { it != TrezorCoinType.SIGNET }.forEach { network ->
            val isSelected = network == selectedNetwork
            val color = if (isSelected) Colors.Brand else Colors.White32
            Text(
                text = network.name,
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(color.copy(alpha = 0.15f))
                    .clickableAlpha(onClick = { onNetworkChange(network) })
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun DebugLogSection() {
    var expanded by remember { mutableStateOf(false) }
    val debugLines by TrezorDebugLog.lines.collectAsStateWithLifecycle()

    Column {
        Spacer(modifier = Modifier.height(16.dp))
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
                    Text(
                        text = line,
                        color = Colors.White80,
                        fontSize = 9.sp,
                        lineHeight = 12.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
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
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Trezor",
                color = Colors.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
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
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Reconnecting...", color = Colors.White64, fontSize = 12.sp)
                }

                trezorState.isScanning -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Colors.Brand
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scanning...", color = Colors.White64, fontSize = 12.sp)
                }

                trezorState.isConnecting -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Colors.Brand
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connecting...", color = Colors.White64, fontSize = 12.sp)
                }

                trezorState.connectedDevice != null -> {
                    StatusBadge(text = "Connected", color = Colors.Green)
                }

                trezorState.isInitialized -> {
                    StatusBadge(text = "Ready", color = Colors.Brand)
                }

                else -> {
                    StatusBadge(text = "Not initialized", color = Colors.White32)
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun ActionButtonsRow(
    trezorState: TrezorState,
    onInitialize: () -> Unit,
    onScan: () -> Unit,
    onDisconnect: () -> Unit,
    permissionsGranted: Boolean = true,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (trezorState.isAutoReconnecting) return@Row
        if (!trezorState.isInitialized) {
            PrimaryButton(
                text = "Initialize",
                onClick = onInitialize,
                size = ButtonSize.Small,
                modifier = Modifier.weight(1f)
            )
        } else if (trezorState.connectedDevice != null) {
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
private fun PreviewNotInitialized() {
    AppThemeSurface {
        TrezorContent(
            trezorState = TrezorState(),
            uiState = TrezorUiState(),
        )
    }
}

@Preview
@Composable
private fun PreviewInitialized() {
    AppThemeSurface {
        TrezorContent(
            trezorState = TrezorState(isInitialized = true),
            uiState = TrezorUiState(),
        )
    }
}

@Preview
@Composable
private fun PreviewWithDevices() {
    val knownDevices = listOf(
        KnownDevice(
            id = "usb-1",
            transportType = "usb",
            name = "Trezor Safe 5",
            path = "/dev/usb/001",
            label = "My Savings",
            model = "Safe 5",
            lastConnectedAt = System.currentTimeMillis(),
        ),
    )
    val nearbyDevices = listOf(
        TrezorDeviceInfo(
            id = "ble-1",
            transportType = TrezorTransportType.BLUETOOTH,
            name = "Trezor Safe 7",
            path = "AA:BB:CC:DD:EE:FF",
            label = null,
            model = "Safe 7",
            isBootloader = false,
        ),
    )

    AppThemeSurface {
        TrezorContent(
            trezorState = TrezorState(
                isInitialized = true,
                knownDevices = knownDevices,
                nearbyDevices = nearbyDevices,
                connectedDeviceId = "usb-1",
            ),
            uiState = TrezorUiState(),
        )
    }
}

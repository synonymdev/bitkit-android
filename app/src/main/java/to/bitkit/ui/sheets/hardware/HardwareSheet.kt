package to.bitkit.ui.sheets.hardware

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.serialization.Serializable
import to.bitkit.R
import to.bitkit.ext.isBluetoothEnabled
import to.bitkit.ext.startActivityAppSettings
import to.bitkit.ui.components.Sheet
import to.bitkit.ui.components.SheetSize
import to.bitkit.ui.navigateTo
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.utils.composableWithDefaultTransitions
import to.bitkit.viewmodels.AppViewModel

/** Runtime nearby-devices permissions needed to BLE-scan: SCAN + CONNECT on Android 12+, else fine location. */
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

/**
 * Entry point for the hardware-wallet connect flow opened from the home suggestion card and the
 * Hardware Wallets settings Add button. Hosts the four connect steps (Intro -> Searching -> Found
 * -> Paired) plus the Pair Device step shown when the device asks for its one-time pairing code.
 * Continuing from the intro starts USB discovery immediately; Bluetooth discovery is included once
 * the nearby-devices permission is granted and Bluetooth is turned on.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun HardwareSheet(
    sheet: Sheet.Hardware,
    appViewModel: AppViewModel,
    onFinish: () -> Unit = {},
    viewModel: HwConnectViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var showBlePermissionDialog by remember { mutableStateOf(false) }
    var blePermissionRequested by remember { mutableStateOf(false) }

    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (context.isBluetoothEnabled) viewModel.setBluetoothScanningEnabled()
    }

    val enableBleScanning: () -> Unit = {
        if (context.isBluetoothEnabled) {
            viewModel.setBluetoothScanningEnabled()
        } else {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }
    val blePermissions = rememberMultiplePermissionsState(bluetoothPermissions) { results ->
        if (results.values.all { it }) {
            enableBleScanning()
        } else {
            showBlePermissionDialog = true
        }
    }
    val requestBleAccess: () -> Unit = {
        when {
            blePermissions.allPermissionsGranted -> enableBleScanning()
            blePermissionRequested && !blePermissions.shouldShowRationale -> showBlePermissionDialog = true
            else -> {
                blePermissionRequested = true
                blePermissions.launchMultiplePermissionRequest()
            }
        }
    }
    val onIntroContinue: () -> Unit = {
        val includeBluetooth = blePermissions.allPermissionsGranted && context.isBluetoothEnabled
        viewModel.onIntroContinue(includeBluetooth = includeBluetooth)
        if (!includeBluetooth) {
            requestBleAccess()
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.resetState() }
    }

    ConnectEffectHandler(
        viewModel = viewModel,
        navController = navController,
        appViewModel = appViewModel,
        onFinish = onFinish,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .sheetHeight(SheetSize.LARGE)
            .testTag("HardwareWalletSheet")
    ) {
        NavHost(
            navController = navController,
            startDestination = sheet.route,
        ) {
            composableWithDefaultTransitions<HardwareRoute.Intro> {
                HwIntroSheet(
                    onContinue = onIntroContinue,
                    onCancel = appViewModel::hideSheet,
                )
            }
            composableWithDefaultTransitions<HardwareRoute.Searching> {
                HwSearchingSheet(
                    errorMessage = uiState.errorMessage,
                    onCancel = appViewModel::hideSheet,
                )
            }
            composableWithDefaultTransitions<HardwareRoute.Found> { backStackEntry ->
                val route = backStackEntry.toRoute<HardwareRoute.Found>()
                LaunchedEffect(route.deviceId, route.deviceModel) {
                    viewModel.onFoundRoute(
                        deviceId = route.deviceId,
                        deviceModel = route.deviceModel,
                    )
                }
                val deviceModel = uiState.deviceModel.ifBlank {
                    route.deviceModel.ifBlank { stringResource(R.string.hardware__device_model_trezor) }
                }
                HwFoundSheet(
                    deviceModel = deviceModel,
                    isConnecting = uiState.isConnecting,
                    errorMessage = uiState.errorMessage,
                    onConnect = { viewModel.onConnectClick(route.deviceId) },
                    onCancel = {
                        viewModel.cancelConnect()
                        appViewModel.hideSheet()
                    },
                )
            }
            composableWithDefaultTransitions<HardwareRoute.Paired> {
                HwPairedSheet(
                    uiState = uiState,
                    onLabelChange = viewModel::onLabelChange,
                    onFinish = viewModel::onFinishClick,
                )
            }
            composableWithDefaultTransitions<HardwareRoute.PairCode> {
                HwPairCodeSheet(
                    onSubmit = appViewModel::submitPairingCode,
                    onCancel = appViewModel::cancelPairingCode,
                )
            }
        }
    }

    BackHandler {
        viewModel.cancelConnect()
        appViewModel.hideSheet()
    }

    if (showBlePermissionDialog) {
        AppAlertDialog(
            title = stringResource(R.string.hardware__bluetooth_permission_title),
            text = stringResource(R.string.hardware__bluetooth_permission_text),
            confirmText = stringResource(R.string.hardware__bluetooth_permission_settings),
            onConfirm = {
                showBlePermissionDialog = false
                context.startActivityAppSettings()
            },
            onDismiss = { showBlePermissionDialog = false },
        )
    }
}

@Composable
private fun ConnectEffectHandler(
    viewModel: HwConnectViewModel,
    navController: NavHostController,
    appViewModel: AppViewModel,
    onFinish: () -> Unit,
) {
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                HwConnectEffect.NavigateToSearching -> navController.navigateTo(HardwareRoute.Searching)
                is HwConnectEffect.NavigateToFound -> navController.navigateTo(
                    HardwareRoute.Found(
                        deviceId = effect.deviceId,
                        deviceModel = effect.deviceModel,
                    ),
                )
                HwConnectEffect.NavigateToPairCode -> navController.navigateTo(HardwareRoute.PairCode)
                HwConnectEffect.NavigateToPaired -> navController.navigateTo(HardwareRoute.Paired)
                HwConnectEffect.Dismiss -> appViewModel.hideSheet()
                HwConnectEffect.Finish -> {
                    appViewModel.hideSheet()
                    onFinish()
                }
            }
        }
    }
}

sealed interface HardwareRoute {
    @Serializable
    data object Intro : HardwareRoute

    @Serializable
    data object Searching : HardwareRoute

    @Serializable
    data class Found(
        val deviceId: String? = null,
        val deviceModel: String = "",
    ) : HardwareRoute

    @Serializable
    data object Paired : HardwareRoute

    @Serializable
    data object PairCode : HardwareRoute
}

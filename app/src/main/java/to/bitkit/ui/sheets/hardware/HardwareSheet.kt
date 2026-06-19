package to.bitkit.ui.sheets.hardware

import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.serialization.Serializable
import to.bitkit.ui.components.Sheet
import to.bitkit.ui.components.SheetSize
import to.bitkit.ui.navigateTo
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.utils.composableWithDefaultTransitions
import to.bitkit.viewmodels.AppViewModel

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
 * Continuing from the intro requests the runtime Bluetooth-scan permission before searching.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun HardwareSheet(
    sheet: Sheet.Hardware,
    appViewModel: AppViewModel,
    viewModel: HwConnectViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // BLE discovery needs the runtime nearby-devices permission; request it before searching.
    val blePermissions = rememberMultiplePermissionsState(bluetoothPermissions) { results ->
        if (results.values.all { it }) viewModel.onIntroContinue()
    }
    val onIntroContinue: () -> Unit = {
        if (blePermissions.allPermissionsGranted) {
            viewModel.onIntroContinue()
        } else {
            blePermissions.launchMultiplePermissionRequest()
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.resetState() }
    }

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                HwConnectEffect.NavigateToSearching -> navController.navigateTo(HardwareRoute.Searching)
                HwConnectEffect.NavigateToFound -> navController.navigateTo(HardwareRoute.Found)
                HwConnectEffect.NavigateToPairCode -> navController.navigateTo(HardwareRoute.PairCode)
                HwConnectEffect.NavigateToPaired -> navController.navigateTo(HardwareRoute.Paired)
                HwConnectEffect.Dismiss -> appViewModel.hideSheet()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .sheetHeight(SheetSize.LARGE)
            .testTag("HardwareSheet")
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
                HwSearchingSheet(onCancel = appViewModel::hideSheet)
            }
            composableWithDefaultTransitions<HardwareRoute.Found> {
                HwFoundSheet(
                    deviceModel = uiState.deviceModel,
                    isConnecting = uiState.isConnecting,
                    onConnect = viewModel::onConnectClick,
                    onCancel = appViewModel::hideSheet,
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
}

sealed interface HardwareRoute {
    @Serializable
    data object Intro : HardwareRoute

    @Serializable
    data object Searching : HardwareRoute

    @Serializable
    data object Found : HardwareRoute

    @Serializable
    data object Paired : HardwareRoute

    @Serializable
    data object PairCode : HardwareRoute
}

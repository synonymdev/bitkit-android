package to.bitkit.ui.screens.wallets

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.ext.requiresPermission
import to.bitkit.ui.AppViewModel
import to.bitkit.ui.MainUiState
import to.bitkit.ui.WalletViewModel
import to.bitkit.ui.components.BalanceSummary
import to.bitkit.ui.postNotificationsPermission
import to.bitkit.ui.scaffold.AppScaffold
import to.bitkit.ui.screens.wallets.receive.ReceiveQRScreen
import to.bitkit.ui.screens.wallets.send.SendOptionsView
import to.bitkit.ui.screens.wallets.activity.ActivityLatest
import to.bitkit.ui.screens.wallets.activity.ActivityType
import to.bitkit.ui.shared.TabBar
import to.bitkit.ui.shared.util.qrCodeScanner
import to.bitkit.ui.theme.AppShapes

@Composable
fun HomeScreen(
    viewModel: WalletViewModel,
    appViewModel: AppViewModel,
    navController: NavController,
) {
    val uiState by viewModel.uiState.collectAsState()

    HomeScreen(viewModel, appViewModel, uiState, navController)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    viewModel: WalletViewModel,
    appViewModel: AppViewModel,
    uiState: MainUiState,
    navController: NavController,
) = AppScaffold(navController, viewModel, stringResource(R.string.app_name)) {
    RequestNotificationPermissions()
    var showReceiveSheet by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxSize()
        ) {
            BalanceSummary(navController)
            Spacer(modifier = Modifier.height(24.dp))
            Text("Activity", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(16.dp))
            ActivityLatest(ActivityType.ALL, viewModel, navController)
        }

        val scanner = qrCodeScanner()
        TabBar(
            onSendClicked = { viewModel.showSendSheet = true },
            onReceiveClicked = { showReceiveSheet = true },
            onScanClicked = {
                scanner?.startScan()?.addOnCompleteListener { task ->
                    task.takeIf { it.isSuccessful }?.result?.rawValue?.let { data ->
                        viewModel.onScanSuccess(data)
                    }
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // Send Sheet
        if (viewModel.showSendSheet) {
            ModalBottomSheet(
                onDismissRequest = { viewModel.showSendSheet = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                shape = AppShapes.sheet,
                containerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(top = 100.dp)
            ) {
                SendOptionsView(
                    onComplete = { sheet ->
                        viewModel.showSendSheet = false
                        sheet?.let { appViewModel.showNewTransactionSheet(it) }
                    }
                )
            }
        }

        // Receive Sheet
        if (showReceiveSheet) {
            ModalBottomSheet(
                onDismissRequest = { showReceiveSheet = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                shape = AppShapes.sheet,
                containerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(top = 100.dp)
            ) {
                ReceiveQRScreen(uiState)
            }
        }
    }
}

@Composable
fun RequestNotificationPermissions() {
    val context = LocalContext.current
    var isGranted by remember { mutableStateOf(!context.requiresPermission(postNotificationsPermission)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        isGranted = it
    }

    LaunchedEffect(isGranted) {
        if (!isGranted) {
            launcher.launch(postNotificationsPermission)
        }
    }
}

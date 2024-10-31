package to.bitkit.ui.screens.wallet

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import to.bitkit.ui.MainUiState
import to.bitkit.ui.WalletViewModel
import to.bitkit.ui.components.BalanceSummary
import to.bitkit.ui.screens.receive.ReceiveQRScreen
import to.bitkit.ui.screens.send.SendOptionsView
import to.bitkit.ui.screens.wallet.activity.ActivityLatest
import to.bitkit.ui.screens.wallet.activity.ActivityType
import to.bitkit.ui.shared.TabBar
import to.bitkit.ui.shared.util.qrCodeScanner
import to.bitkit.ui.theme.AppShapes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: WalletViewModel,
    uiState: MainUiState.Content,
    navController: NavHostController,
) {
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
            BalanceSummary(uiState, navController)
            Spacer(modifier = Modifier.height(24.dp))
            Text("Activity", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(16.dp))
            ActivityLatest(ActivityType.ALL, navController)
        }

        val scanner = qrCodeScanner()
        TabBar(
            onSendClicked = { viewModel.showSendSheet = true },
            onReceiveClicked = { showReceiveSheet = true },
            onScanClicked = {
                scanner.startScan().addOnCompleteListener { task ->
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
                SendOptionsView()
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

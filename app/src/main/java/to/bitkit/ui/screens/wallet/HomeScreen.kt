package to.bitkit.ui.screens.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import to.bitkit.ui.Routes
import to.bitkit.ui.WalletViewModel
import to.bitkit.ui.screens.receive.ReceiveQRScreen
import to.bitkit.ui.screens.send.SendOptionsView
import to.bitkit.ui.components.BalanceSummary
import to.bitkit.ui.shared.TabBar
import to.bitkit.ui.theme.AppShapes

object WalletRoutes {
    const val HOME = "HOME"
    const val SAVINGS = "SAVINGS"
    const val SPENDING = "SPENDING"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: WalletViewModel,
    uiState: MainUiState.Content,
    navController: NavHostController,
) {
    var showReceiveNavigation by remember { mutableStateOf(false) }
    var showSendNavigation by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        val walletNavController = rememberNavController()
        NavHost(
            navController = walletNavController,
            startDestination = WalletRoutes.HOME,
            modifier = Modifier.fillMaxSize()
        ) {
            composable(WalletRoutes.HOME) {
                MainWalletScreen(uiState, walletNavController)
            }
            composable(WalletRoutes.SAVINGS) {
                SavingsWalletScreen(navController)
            }
            composable(WalletRoutes.SPENDING) {
                SpendingWalletScreen()
            }
        }
        TabBar(
            onSendClicked = { showSendNavigation = true },
            onReceiveClicked = { showReceiveNavigation = true },
            onScanClicked = { navController.navigate(Routes.Scanner.destination) },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
        // Send Sheet
        if (showSendNavigation) {
            ModalBottomSheet(
                onDismissRequest = { showSendNavigation = false },
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
        if (showReceiveNavigation) {
            ModalBottomSheet(
                onDismissRequest = { showReceiveNavigation = false },
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
private fun MainWalletScreen(
    uiState: MainUiState.Content,
    walletNavController: NavHostController,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        BalanceSummary(uiState, walletNavController)
    }
}

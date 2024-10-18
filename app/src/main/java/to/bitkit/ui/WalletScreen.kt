package to.bitkit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import to.bitkit.ui.screens.receive.ReceiveQRView
import to.bitkit.ui.screens.send.SendOptionsView
import to.bitkit.ui.shared.BalanceSummary
import to.bitkit.ui.shared.TabBar
import to.bitkit.ui.theme.AppThemeSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(
    viewModel: WalletViewModel,
    uiState: MainUiState.Content,
    navController: NavHostController,
    content: @Composable () -> Unit = {},
) {
    var showReceiveNavigation by remember { mutableStateOf(false) }
    var showSendNavigation by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            BalanceSummary(uiState, navController)

            // TODO: Debug UI (Hide)
            content()
            Spacer(modifier = Modifier.height(1.dp))
        }
        TabBar(
            onSendClicked = { showSendNavigation = true },
            onReceiveClicked = { showReceiveNavigation = true },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
        // Send Sheet
        if (showSendNavigation) {
            ModalBottomSheet(
                onDismissRequest = { showSendNavigation = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                shape = MaterialTheme.shapes.medium,
            ) {
                val sheetHeight = LocalConfiguration.current.screenHeightDp.dp - 100.dp
                SendOptionsView(modifier = Modifier.height(sheetHeight))
            }
        }
        // Receive Sheet
        if (showReceiveNavigation) {
            ModalBottomSheet(
                onDismissRequest = { showReceiveNavigation = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                shape = MaterialTheme.shapes.medium,
            ) {
                val sheetHeight = LocalConfiguration.current.screenHeightDp.dp - 100.dp
                ReceiveQRView(modifier = Modifier.height(sheetHeight))
            }
        }
    }
}

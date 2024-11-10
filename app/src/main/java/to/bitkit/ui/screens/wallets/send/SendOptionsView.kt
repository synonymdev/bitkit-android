package to.bitkit.ui.screens.wallets.send

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import to.bitkit.ext.toast
import to.bitkit.ui.components.NavButton
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.qrCodeScanner
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun SendOptionsView(
) {
    val sendViewModel = hiltViewModel<SendViewModel>()
    Column(modifier = Modifier.fillMaxSize()) {
        val navController = rememberNavController()
        LaunchedEffect(sendViewModel, navController) {
            sendViewModel.effect.collect {
                when (it) {
                    is SendEffect.NavigateToAmount -> navController.navigate(SendRoutes.Amount)
                    is SendEffect.NavigateToAddress -> navController.navigate(SendRoutes.Address)
                    is SendEffect.NavigateToReview -> navController.navigate(SendRoutes.ReviewAndSend)
                }
            }
        }

        NavHost(
            navController = navController,
            startDestination = SendRoutes.Options,
        ) {
            composable<SendRoutes.Options> {
                SendOptionsContent(
                    onPasteInvoice = { sendViewModel.onPasteInvoice(it) },
                    onContactClick = { toast("Coming Soon") },
                    onEnterManuallyClick = { sendViewModel.setEvent(SendEvent.EnterManually) },
                    onScanSuccess = { sendViewModel.onScanSuccess(it) }
                )
            }
            composable<SendRoutes.Address> {
                SendAddressScreen(
                    onBack = { navController.popBackStack() },
                    onContinue = { sendViewModel.setEvent(SendEvent.AddressContinue(it)) },
                )
            }
            composable<SendRoutes.Amount> {
                SendAmountScreen(
                    onBack = { navController.popBackStack() },
                    onContinue = { sendViewModel.setEvent(SendEvent.AmountContinue(it)) }
                )
            }
            composable<SendRoutes.ReviewAndSend> {
                val uiState by sendViewModel.uiState.collectAsStateWithLifecycle()
                SendAndReviewScreen(
                    onBack = { navController.popBackStack() },
                    onContinue = { sendViewModel.setEvent(SendEvent.SwipeToPay) },
                    uiState = uiState,
                )
            }
        }
    }
}

@Composable
private fun SendOptionsContent(
    onContactClick: () -> Unit,
    onPasteInvoice: (String) -> Unit,
    onEnterManuallyClick: () -> Unit,
    onScanSuccess: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        SheetTopBar("Send Bitcoin")
        Text(
            text = "TO",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Normal,
        )
        Spacer(modifier = Modifier.height(4.dp))

        NavButton("Contact", showIcon = false) {
            onContactClick()
        }
        Spacer(modifier = Modifier.height(4.dp))

        val clipboard = LocalClipboardManager.current
        NavButton("Paste Invoice", showIcon = false) {
            val uri = clipboard.getText()?.text.orEmpty().trim()
            onPasteInvoice(uri)
        }
        Spacer(modifier = Modifier.height(4.dp))

        NavButton("Enter Manually") {
            onEnterManuallyClick()
        }
        Spacer(modifier = Modifier.height(4.dp))

        val scanner = qrCodeScanner()
        NavButton("Scan QR Code") {
            scanner?.startScan()?.addOnCompleteListener { task ->
                task.takeIf { it.isSuccessful }?.result?.rawValue?.let { data ->
                    onScanSuccess(data)
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

// region preview
@Preview(showBackground = true)
@Composable
fun SendOptionsContentPreview() {
    AppThemeSurface {
        SendOptionsContent(
            onContactClick = {},
            onPasteInvoice = {},
            onEnterManuallyClick = {},
            onScanSuccess = {},
        )
    }
}
// endregion

private object SendRoutes {
    @Serializable
    data object Options

    @Serializable
    data object Address

    @Serializable
    data object Amount

    @Serializable
    data object ReviewAndSend
}

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import to.bitkit.R
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
                    onEvent = { sendViewModel.setEvent(it) }
                )
            }
            composable<SendRoutes.Address> {
                val uiState by sendViewModel.uiState.collectAsStateWithLifecycle()
                SendAddressScreen(
                    uiState = uiState,
                    onBack = { navController.popBackStack() },
                    onEvent = { sendViewModel.setEvent(it) },
                )
            }
            composable<SendRoutes.Amount> {
                val uiState by sendViewModel.uiState.collectAsStateWithLifecycle()
                SendAmountScreen(
                    uiState = uiState,
                    onBack = { navController.popBackStack() },
                    onEvent = { sendViewModel.setEvent(it) }
                )
            }
            composable<SendRoutes.ReviewAndSend> {
                val uiState by sendViewModel.uiState.collectAsStateWithLifecycle()
                SendAndReviewScreen(
                    onBack = { navController.popBackStack() },
                    onEvent = { sendViewModel.setEvent(it) },
                    uiState = uiState,
                )
            }
        }
    }
}

@Composable
private fun SendOptionsContent(
    onEvent: (SendEvent) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        SheetTopBar(stringResource(R.string.title_send))
        Text(
            text = stringResource(R.string.label_to),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Normal,
        )
        Spacer(modifier = Modifier.height(4.dp))

        NavButton(stringResource(R.string.contact), showIcon = false) {
            onEvent(SendEvent.Contact)
        }
        Spacer(modifier = Modifier.height(4.dp))

        val clipboard = LocalClipboardManager.current
        NavButton(stringResource(R.string.paste_invoice), showIcon = false) {
            val uri = clipboard.getText()?.text.orEmpty().trim()
            onEvent(SendEvent.Paste(uri))
        }
        Spacer(modifier = Modifier.height(4.dp))

        NavButton(stringResource(R.string.enter_manually)) {
            onEvent(SendEvent.EnterManually)
        }
        Spacer(modifier = Modifier.height(4.dp))

        val scanner = qrCodeScanner()
        NavButton(stringResource(R.string.scan_qr)) {
            scanner?.startScan()?.addOnCompleteListener { task ->
                task.takeIf { it.isSuccessful }?.result?.rawValue?.let { data ->
                    onEvent(SendEvent.Scan(data))
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
            onEvent = {},
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

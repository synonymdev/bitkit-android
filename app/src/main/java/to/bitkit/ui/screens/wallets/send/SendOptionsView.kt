package to.bitkit.ui.screens.wallets.send

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import to.bitkit.R
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.RectangleButton
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.screens.scanner.QrScanningScreen
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.SendEffect
import to.bitkit.viewmodels.SendEvent

@Composable
fun SendOptionsView(
    appViewModel: AppViewModel,
    startDestination: SendRoute = SendRoute.Options,
    onComplete: (NewTransactionSheetDetails?) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(.875f)
            .imePadding()
    ) {
        val navController = rememberNavController()
        LaunchedEffect(appViewModel, navController) {
            appViewModel.sendEffect.collect {
                when (it) {
                    is SendEffect.NavigateToAmount -> navController.navigate(SendRoute.Amount)
                    is SendEffect.NavigateToAddress -> navController.navigate(SendRoute.Address)
                    is SendEffect.NavigateToScan -> navController.navigate(SendRoute.QrScanner)
                    is SendEffect.NavigateToReview -> navController.navigate(SendRoute.ReviewAndSend)
                    is SendEffect.PaymentSuccess -> onComplete(it.sheet)
                }
            }
        }

        NavHost(
            navController = navController,
            startDestination = startDestination,
        ) {
            composable<SendRoute.Options> {
                SendOptionsContent(
                    onEvent = { appViewModel.setSendEvent(it) }
                )
            }
            composable<SendRoute.Address> {
                val uiState by appViewModel.sendUiState.collectAsStateWithLifecycle()
                SendAddressScreen(
                    uiState = uiState,
                    onBack = { navController.popBackStack() },
                    onEvent = { appViewModel.setSendEvent(it) },
                )
            }
            composable<SendRoute.Amount> {
                val uiState by appViewModel.sendUiState.collectAsStateWithLifecycle()
                SendAmountScreen(
                    uiState = uiState,
                    onBack = { navController.popBackStack() },
                    onEvent = { appViewModel.setSendEvent(it) }
                )
            }
            composable<SendRoute.QrScanner> {
                QrScanningScreen(navController = navController) { qrCode ->
                    navController.popBackStack()
                    appViewModel.onScanSuccess(data = qrCode)
                }
            }
            composable<SendRoute.ReviewAndSend> {
                val uiState by appViewModel.sendUiState.collectAsStateWithLifecycle()
                SendAndReviewScreen(
                    uiState = uiState,
                    onBack = { navController.popBackStack() },
                    onEvent = { appViewModel.setSendEvent(it) },
                )
            }
        }
    }
}

@Composable
private fun SendOptionsContent(
    onEvent: (SendEvent) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val app = appViewModel
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        SheetTopBar(stringResource(R.string.title_send))
        Caption13Up(text = stringResource(R.string.wallet__send_to))
        Spacer(modifier = Modifier.height(4.dp))

        RectangleButton(
            label = stringResource(R.string.contact),
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_users),
                    contentDescription = null,
                    tint = Colors.Brand,
                    modifier = Modifier.size(28.dp),
                )
            },
            modifier = Modifier.padding(bottom = 4.dp)
        ) {
            scope.launch {
                app?.toast(Exception("Coming soon: Contact"))
            }
        }

        val clipboard = LocalClipboardManager.current
        RectangleButton(
            label = stringResource(R.string.paste_invoice),
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_clipboard_text),
                    contentDescription = null,
                    tint = Colors.Brand,
                    modifier = Modifier.size(28.dp),
                )
            },
            modifier = Modifier.padding(bottom = 4.dp)
        ) {
            val uri = clipboard.getText()?.text.orEmpty().trim()
            onEvent(SendEvent.Paste(uri))
        }

        RectangleButton(
            label = stringResource(R.string.enter_manually),
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_pencil_simple),
                    contentDescription = null,
                    tint = Colors.Brand,
                    modifier = Modifier.size(28.dp),
                )
            },
            modifier = Modifier.padding(bottom = 4.dp)
        ) {
            onEvent(SendEvent.EnterManually)
        }

        RectangleButton(
            label = stringResource(R.string.wallet__recipient_scan),
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_scan),
                    contentDescription = null,
                    tint = Colors.Brand,
                    modifier = Modifier.size(28.dp),
                )
            },
        ) {
            onEvent(SendEvent.Scan)
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

// region preview
@Preview(showBackground = true)
@Composable
private fun SendOptionsContentPreview() {
    AppThemeSurface {
        SendOptionsContent(
            onEvent = {},
        )
    }
}
// endregion

interface SendRoute {
    @Serializable
    data object Options : SendRoute

    @Serializable
    data object Address : SendRoute

    @Serializable
    data object Amount : SendRoute

    @Serializable
    data object QrScanner : SendRoute

    @Serializable
    data object ReviewAndSend : SendRoute
}

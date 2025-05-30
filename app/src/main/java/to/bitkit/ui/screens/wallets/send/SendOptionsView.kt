package to.bitkit.ui.screens.wallets.send

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import to.bitkit.R
import to.bitkit.ext.setClipboardText
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.RectangleButton
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.screens.scanner.QrScanningScreen
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.composableWithDefaultTransitions
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.SendEffect
import to.bitkit.viewmodels.SendEvent
import to.bitkit.viewmodels.WalletViewModel

@Composable
fun SendOptionsView(
    appViewModel: AppViewModel,
    walletViewModel: WalletViewModel,
    startDestination: SendRoute = SendRoute.Options,
    onComplete: (NewTransactionSheetDetails?) -> Unit,
) {
    val context = LocalContext.current
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
                    is SendEffect.PaymentSuccess -> {
                        onComplete(it.sheet)
                        context.setClipboardText(text = "")
                    }
                    is SendEffect.NavigateToQuickPay -> {
                        navController.navigate(SendRoute.QuickPay(it.invoice, it.amount))
                    }
                }
            }
        }

        NavHost(
            navController = navController,
            startDestination = startDestination,
        ) {
            composableWithDefaultTransitions<SendRoute.Options> {
                SendOptionsContent(
                    onEvent = { appViewModel.setSendEvent(it) }
                )
            }
            composableWithDefaultTransitions<SendRoute.Address> {
                val uiState by appViewModel.sendUiState.collectAsStateWithLifecycle()
                SendAddressScreen(
                    uiState = uiState,
                    onBack = { navController.popBackStack() },
                    onEvent = { appViewModel.setSendEvent(it) },
                )
            }
            composableWithDefaultTransitions<SendRoute.Amount> {
                val uiState by appViewModel.sendUiState.collectAsStateWithLifecycle()
                val walletUiState by walletViewModel.uiState.collectAsStateWithLifecycle()
                SendAmountScreen(
                    uiState = uiState,
                    walletUiState = walletUiState,
                    onBack = { navController.popBackStack() },
                    onEvent = { appViewModel.setSendEvent(it) }
                )
            }
            composableWithDefaultTransitions<SendRoute.QrScanner> {
                QrScanningScreen(navController = navController) { qrCode ->
                    navController.popBackStack()
                    appViewModel.onScanSuccess(data = qrCode)
                }
            }
            composableWithDefaultTransitions<SendRoute.ReviewAndSend> { backStackEntry ->
                val uiState by appViewModel.sendUiState.collectAsStateWithLifecycle()
                SendAndReviewScreen(
                    savedStateHandle = backStackEntry.savedStateHandle,
                    uiState = uiState,
                    onBack = { navController.popBackStack() },
                    onEvent = { appViewModel.setSendEvent(it) },
                    onClickAddTag = { navController.navigate(SendRoute.AddTag) },
                    onClickTag = { tag -> appViewModel.removeTag(tag) },
                    onNavigateToPin = { navController.navigate(SendRoute.PinCheck) }
                )
            }
            composableWithDefaultTransitions<SendRoute.AddTag> {
                AddTagScreen(
                    onBack = { navController.popBackStack() },
                    onTagSelected = { tag ->
                        appViewModel.addTagToSelected(tag)
                        navController.popBackStack()
                    },
                )
            }
            composableWithDefaultTransitions<SendRoute.PinCheck> {
                PinCheckScreen(
                    onBack = { navController.popBackStack() },
                    onSuccess = {
                        navController.previousBackStackEntry
                            ?.savedStateHandle
                            ?.set(PIN_CHECK_RESULT_KEY, true)
                        navController.popBackStack()
                        appViewModel.setSendEvent(SendEvent.SwipeToPay)
                    },
                )
            }
            composableWithDefaultTransitions<SendRoute.QuickPay> { backStackEntry ->
                val route = backStackEntry.toRoute<SendRoute.QuickPay>()
                QuickPaySendScreen(
                    invoice = route.invoice,
                    amount = route.amount,
                    onPaymentComplete = {
                        onComplete(null)
                    },
                    onShowError = { errorMessage ->
                        navController.navigate(SendRoute.Error(errorMessage))
                    }
                )
            }
            composableWithDefaultTransitions<SendRoute.Error> { backStackEntry ->
                val route = backStackEntry.toRoute<SendRoute.Error>()
                SendErrorScreen(
                    errorMessage = route.errorMessage,
                    onRetry = {
                        appViewModel.setSendEvent(SendEvent.Reset)

                        if (startDestination == SendRoute.Options) {
                            navController.navigate(SendRoute.Options) {
                                popUpTo<SendRoute.Options> { inclusive = true }
                            }
                        } else {
                            onComplete(null)
                        }
                    },
                    onClose = {
                        onComplete(null)
                    }
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
            .gradientBackground()
            .padding(horizontal = 16.dp)
    ) {
        SheetTopBar(titleText = stringResource(R.string.wallet__send_bitcoin))
        Spacer(Modifier.height(32.dp))
        Caption13Up(text = stringResource(R.string.wallet__send_to))
        Spacer(modifier = Modifier.height(16.dp))

        RectangleButton(
            label = stringResource(R.string.wallet__recipient_contact),
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
            label = stringResource(R.string.wallet__recipient_invoice),
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
            label = stringResource(R.string.wallet__recipient_manual),
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

        Image(
            painter = painterResource(R.drawable.coin_stack_logo),
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SendOptionsContentPreview() {
    AppThemeSurface {
        SendOptionsContent(
            onEvent = {},
        )
    }
}

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

    @Serializable
    data object AddTag : SendRoute

    @Serializable
    data object PinCheck : SendRoute

    @Serializable
    data class QuickPay(val invoice: String, val amount: Long) : SendRoute

    @Serializable
    data class Error(val errorMessage: String) : SendRoute
}

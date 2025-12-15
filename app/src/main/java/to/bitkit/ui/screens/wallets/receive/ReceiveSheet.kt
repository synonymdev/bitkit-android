package to.bitkit.ui.screens.wallets.receive

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import to.bitkit.repositories.LightningState
import to.bitkit.ui.screens.wallets.send.AddTagScreen
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.utils.NotificationUtils
import to.bitkit.ui.utils.composableWithDefaultTransitions
import to.bitkit.ui.walletViewModel
import to.bitkit.viewmodels.AmountInputViewModel
import to.bitkit.viewmodels.MainUiState
import to.bitkit.viewmodels.SettingsViewModel

@Composable
fun ReceiveSheet(
    navigateToExternalConnection: () -> Unit,
    walletState: MainUiState,
    editInvoiceAmountViewModel: AmountInputViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    val wallet = requireNotNull(walletViewModel)
    val navController = rememberNavController()

    LaunchedEffect(Unit) { editInvoiceAmountViewModel.clearInput() }

    val cjitInvoice = remember { mutableStateOf<String?>(null) }
    val showCreateCjit = remember { mutableStateOf(false) }
    val cjitEntryDetails = remember { mutableStateOf<CjitEntryDetails?>(null) }
    val lightningState: LightningState by wallet.lightningState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        wallet.resetPreActivityMetadataTagsForCurrentInvoice()
        wallet.refreshReceiveState()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .sheetHeight()
            .imePadding()
            .testTag("ReceiveScreen")
    ) {
        NavHost(
            navController = navController,
            startDestination = ReceiveRoute.QR,
        ) {
            composableWithDefaultTransitions<ReceiveRoute.QR> {
                LaunchedEffect(cjitInvoice.value) {
                    showCreateCjit.value = !cjitInvoice.value.isNullOrBlank()
                }

                ReceiveQrScreen(
                    cjitInvoice = cjitInvoice.value,
                    walletState = walletState,
                    onClickReceiveCjit = {
                        if (lightningState.isGeoBlocked) {
                            navController.navigate(ReceiveRoute.GeoBlock)
                        } else {
                            showCreateCjit.value = true
                            navController.navigate(ReceiveRoute.Amount)
                        }
                    },
                    onClickEditInvoice = { navController.navigate(ReceiveRoute.EditInvoice) },
                )
            }
            composableWithDefaultTransitions<ReceiveRoute.Amount> {
                ReceiveAmountScreen(
                    onCjitCreated = { entry ->
                        cjitEntryDetails.value = entry
                        navController.navigate(ReceiveRoute.Confirm)
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composableWithDefaultTransitions<ReceiveRoute.GeoBlock> {
                LocationBlockScreen(
                    onBackPressed = { navController.popBackStack() },
                    navigateAdvancedSetup = navigateToExternalConnection,
                )
            }
            composableWithDefaultTransitions<ReceiveRoute.Confirm> {
                cjitEntryDetails.value?.let { entryDetails ->
                    ReceiveConfirmScreen(
                        entry = entryDetails,
                        onLearnMore = { navController.navigate(ReceiveRoute.Liquidity) },
                        onContinue = { invoice ->
                            cjitInvoice.value = invoice
                            navController.navigate(ReceiveRoute.QR) { popUpTo(ReceiveRoute.QR) { inclusive = true } }
                        },
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            composableWithDefaultTransitions<ReceiveRoute.ConfirmIncreaseInbound> {
                cjitEntryDetails.value?.let { entryDetails ->
                    ReceiveConfirmScreen(
                        entry = entryDetails,
                        onLearnMore = { navController.navigate(ReceiveRoute.LiquidityAdditional) },
                        onContinue = { invoice ->
                            cjitInvoice.value = invoice
                            navController.navigate(ReceiveRoute.QR) { popUpTo(ReceiveRoute.QR) { inclusive = true } }
                        },
                        isAdditional = true,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            composableWithDefaultTransitions<ReceiveRoute.Liquidity> {
                cjitEntryDetails.value?.let { entryDetails ->
                    val context = LocalContext.current
                    val notificationsGranted by settingsViewModel.notificationsGranted.collectAsStateWithLifecycle()

                    ReceiveLiquidityScreen(
                        entry = entryDetails,
                        onContinue = { navController.popBackStack() },
                        onBack = { navController.popBackStack() },
                        hasNotificationPermission = notificationsGranted,
                        onSwitchClick = {
                            NotificationUtils.openNotificationSettings(context)
                        },
                    )
                }
            }
            composableWithDefaultTransitions<ReceiveRoute.LiquidityAdditional> {
                cjitEntryDetails.value?.let { entryDetails ->
                    val context = LocalContext.current
                    val notificationsGranted by settingsViewModel.notificationsGranted.collectAsStateWithLifecycle()

                    ReceiveLiquidityScreen(
                        entry = entryDetails,
                        onContinue = { navController.popBackStack() },
                        isAdditional = true,
                        onBack = { navController.popBackStack() },
                        hasNotificationPermission = notificationsGranted,
                        onSwitchClick = {
                            NotificationUtils.openNotificationSettings(context)
                        },
                    )
                }
            }
            composableWithDefaultTransitions<ReceiveRoute.EditInvoice> {
                val walletUiState by wallet.walletState.collectAsStateWithLifecycle()
                @Suppress("ViewModelForwarding")
                EditInvoiceScreen(
                    amountInputViewModel = editInvoiceAmountViewModel,
                    walletUiState = walletUiState,
                    onBack = { navController.popBackStack() },
                    updateInvoice = wallet::updateBip21Invoice,
                    onClickAddTag = { navController.navigate(ReceiveRoute.AddTag) },
                    onClickTag = wallet::removeTag,
                    onDescriptionUpdate = wallet::updateBip21Description,
                    navigateReceiveConfirm = { entry ->
                        cjitEntryDetails.value = entry
                        navController.navigate(ReceiveRoute.ConfirmIncreaseInbound)
                    }
                )
            }
            composableWithDefaultTransitions<ReceiveRoute.AddTag> {
                AddTagScreen(
                    onBack = {
                        navController.popBackStack()
                    },
                    onTagSelected = { tag ->
                        wallet.addTagToSelected(tag)
                        navController.popBackStack()
                    },
                    tqgInputTestTag = "TagInputReceive",
                    addButtonTestTag = "ReceiveTagsSubmit",
                )
            }
        }
    }
}

sealed interface ReceiveRoute {
    @Serializable
    data object QR : ReceiveRoute

    @Serializable
    data object Amount : ReceiveRoute

    @Serializable
    data object Confirm : ReceiveRoute

    @Serializable
    data object ConfirmIncreaseInbound : ReceiveRoute

    @Serializable
    data object Liquidity : ReceiveRoute

    @Serializable
    data object LiquidityAdditional : ReceiveRoute

    @Serializable
    data object EditInvoice : ReceiveRoute

    @Serializable
    data object AddTag : ReceiveRoute

    @Serializable
    data object GeoBlock : ReceiveRoute
}

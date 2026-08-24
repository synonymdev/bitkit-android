package to.bitkit.ui.screens.wallets.receive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import to.bitkit.R
import to.bitkit.repositories.LightningState
import to.bitkit.repositories.PaykitPaymentRequest
import to.bitkit.repositories.PaykitPaymentRequestDraft
import to.bitkit.repositories.WalletState
import to.bitkit.ui.components.ConnectionIssuesView
import to.bitkit.ui.navigateTo
import to.bitkit.ui.openNotificationSettings
import to.bitkit.ui.screens.paymentrequests.PaymentRequestDetailsScreen
import to.bitkit.ui.screens.paymentrequests.PaymentRequestRecipientScreen
import to.bitkit.ui.screens.paymentrequests.PaymentRequestSentScreen
import to.bitkit.ui.screens.wallets.send.AddTagScreen
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.utils.ScreenDeepLinks
import to.bitkit.ui.utils.composableWithDefaultTransitions
import to.bitkit.ui.utils.rememberNotificationToggleClick
import to.bitkit.ui.walletViewModel
import to.bitkit.viewmodels.AmountInputViewModel
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.SettingsViewModel
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
@Composable
fun ReceiveSheet(
    appViewModel: AppViewModel,
    navigateToExternalConnection: () -> Unit,
    walletState: WalletState,
    isOffline: Boolean,
    startRoute: ReceiveRoute = ReceiveRoute.QR,
    editInvoiceAmountViewModel: AmountInputViewModel = hiltViewModel(),
    paymentRequestAmountViewModel: AmountInputViewModel = hiltViewModel(key = "PaymentRequestAmount"),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    val wallet = requireNotNull(walletViewModel)
    val navController = rememberNavController()

    LaunchedEffect(Unit) { editInvoiceAmountViewModel.clearInput() }

    val cjitInvoice = remember { mutableStateOf<String?>(null) }
    val showCreateCjit = remember { mutableStateOf(false) }
    val cjitEntryDetails = remember { mutableStateOf<CjitEntryDetails?>(null) }
    val lightningState: LightningState by wallet.lightningState.collectAsStateWithLifecycle()
    val paymentRequestTargets by appViewModel.eligiblePaymentRequestTargets.collectAsStateWithLifecycle()
    var paymentRequestDraft by remember {
        mutableStateOf(
            PaykitPaymentRequestDraft(
                amountSats = 0uL,
                note = "",
                expiresAt = Clock.System.now() + 7.days,
            )
        )
    }
    var createdPaymentRequest by remember { mutableStateOf<PaykitPaymentRequest?>(null) }

    LaunchedEffect(Unit) {
        wallet.resetPreActivityMetadataTagsForCurrentInvoice()
        wallet.refreshReceiveState()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .sheetHeight(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .testTag("ReceiveScreen"),
        ) {
            NavHost(
                navController = navController,
                startDestination = startRoute,
            ) {
                composableWithDefaultTransitions<ReceiveRoute.QR> {
                    LaunchedEffect(cjitInvoice.value) {
                        showCreateCjit.value = !cjitInvoice.value.isNullOrBlank()
                    }

                    ReceiveQrScreen(
                        cjitInvoice = cjitInvoice.value,
                        walletState = walletState,
                        lightningState = lightningState,
                        onClickReceiveCjit = {
                            if (lightningState.isGeoBlocked) {
                                navController.navigateTo(ReceiveRoute.GeoBlock)
                            } else {
                                showCreateCjit.value = true
                                navController.navigateTo(ReceiveRoute.Amount)
                            }
                        },
                        onClickEditInvoice = { navController.navigateTo(ReceiveRoute.EditInvoice) },
                    )
                }
                composableWithDefaultTransitions<ReceiveRoute.PaymentRequestDetails> {
                    PaymentRequestDetailsScreen(
                        amountInputViewModel = paymentRequestAmountViewModel,
                        initialDraft = paymentRequestDraft,
                        onBack = { navController.popBackStack() },
                        onContinue = {
                            paymentRequestDraft = it
                            navController.navigateTo(ReceiveRoute.PaymentRequestRecipient)
                        },
                    )
                }
                composableWithDefaultTransitions<ReceiveRoute.PaymentRequestExpiration> {
                    PaymentRequestDetailsScreen(
                        amountInputViewModel = paymentRequestAmountViewModel,
                        initialDraft = paymentRequestDraft,
                        onBack = { navController.popBackStack() },
                        onContinue = {
                            paymentRequestDraft = it
                            navController.popBackStack()
                        },
                    )
                }
                composableWithDefaultTransitions<ReceiveRoute.PaymentRequestRecipient> {
                    PaymentRequestRecipientScreen(
                        appViewModel = appViewModel,
                        draft = paymentRequestDraft,
                        onEditExpiration = {
                            navController.navigateTo(ReceiveRoute.PaymentRequestExpiration)
                        },
                        onSent = {
                            createdPaymentRequest = it
                            navController.navigateTo(ReceiveRoute.PaymentRequestSent)
                        },
                    )
                }
                composableWithDefaultTransitions<ReceiveRoute.PaymentRequestSent> {
                    createdPaymentRequest?.let {
                        PaymentRequestSentScreen(
                            appViewModel = appViewModel,
                            request = it,
                            onDone = appViewModel::hideSheet,
                        )
                    }
                }
                composableWithDefaultTransitions<ReceiveRoute.Amount> {
                    ReceiveAmountScreen(
                        onCjitCreated = { entry ->
                            cjitEntryDetails.value = entry
                            navController.navigateTo(ReceiveRoute.Confirm)
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
                            onLearnMore = { navController.navigateTo(ReceiveRoute.Liquidity) },
                            onContinue = { invoice ->
                                cjitInvoice.value = invoice
                                navController.navigateTo(
                                    ReceiveRoute.QR
                                ) { popUpTo(ReceiveRoute.QR) { inclusive = true } }
                            },
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
                composableWithDefaultTransitions<ReceiveRoute.ConfirmIncreaseInbound> {
                    cjitEntryDetails.value?.let { entryDetails ->
                        ReceiveConfirmScreen(
                            entry = entryDetails,
                            onLearnMore = { navController.navigateTo(ReceiveRoute.LiquidityAdditional) },
                            onContinue = { invoice ->
                                cjitInvoice.value = invoice
                                navController.navigateTo(
                                    ReceiveRoute.QR
                                ) { popUpTo(ReceiveRoute.QR) { inclusive = true } }
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
                        val onNotificationSwitchClick = rememberNotificationToggleClick(
                            isGranted = notificationsGranted,
                            onPermissionResult = { granted -> settingsViewModel.setNotificationPreference(granted) },
                            onOpenSystemSettings = { context.openNotificationSettings() },
                        )

                        ReceiveLiquidityScreen(
                            entry = entryDetails,
                            onContinue = { navController.popBackStack() },
                            onBack = { navController.popBackStack() },
                            hasNotificationPermission = notificationsGranted,
                            onSwitchClick = onNotificationSwitchClick,
                        )
                    }
                }
                composableWithDefaultTransitions<ReceiveRoute.LiquidityAdditional> {
                    cjitEntryDetails.value?.let { entryDetails ->
                        val context = LocalContext.current
                        val notificationsGranted by settingsViewModel.notificationsGranted.collectAsStateWithLifecycle()
                        val onNotificationSwitchClick = rememberNotificationToggleClick(
                            isGranted = notificationsGranted,
                            onPermissionResult = { granted -> settingsViewModel.setNotificationPreference(granted) },
                            onOpenSystemSettings = { context.openNotificationSettings() },
                        )

                        ReceiveLiquidityScreen(
                            entry = entryDetails,
                            onContinue = { navController.popBackStack() },
                            isAdditional = true,
                            onBack = { navController.popBackStack() },
                            hasNotificationPermission = notificationsGranted,
                            onSwitchClick = onNotificationSwitchClick,
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
                        onClickAddTag = { navController.navigateTo(ReceiveRoute.AddTag) },
                        onClickTag = wallet::removeTag,
                        onDescriptionUpdate = wallet::updateBip21Description,
                        showPaymentRequestButton = paymentRequestTargets.isNotEmpty(),
                        onClickPaymentRequest = { amountSats, note ->
                            paymentRequestDraft = PaykitPaymentRequestDraft(
                                amountSats = amountSats,
                                note = note,
                                expiresAt = Clock.System.now() + 7.days,
                            )
                            navController.navigateTo(ReceiveRoute.PaymentRequestRecipient)
                        },
                        navigateReceiveConfirm = { entry ->
                            cjitEntryDetails.value = entry
                            navController.navigateTo(ReceiveRoute.ConfirmIncreaseInbound)
                        },
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

        AnimatedVisibility(
            visible = isOffline,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            ConnectionIssuesView(titleText = stringResource(R.string.wallet__receive_bitcoin))
        }
    }
}

sealed interface ReceiveRoute {
    sealed interface DeepLinkStart : ReceiveRoute

    sealed interface InternalOnly : ReceiveRoute

    @Serializable
    data object QR : DeepLinkStart

    @Serializable
    data object Amount : DeepLinkStart

    @Serializable
    data object Confirm : InternalOnly

    @Serializable
    data object ConfirmIncreaseInbound : InternalOnly

    @Serializable
    data object Liquidity : InternalOnly

    @Serializable
    data object LiquidityAdditional : InternalOnly

    @Serializable
    data object EditInvoice : DeepLinkStart

    @Serializable
    data object AddTag : DeepLinkStart

    @Serializable
    data object PaymentRequestDetails : InternalOnly

    @Serializable
    data object PaymentRequestExpiration : InternalOnly

    @Serializable
    data object PaymentRequestRecipient : InternalOnly

    @Serializable
    data object PaymentRequestSent : InternalOnly

    @Serializable
    data object GeoBlock : DeepLinkStart

    companion object {
        private val DEEP_LINK_STARTS: List<DeepLinkStart> = listOf(
            QR,
            Amount,
            EditInvoice,
            AddTag,
            GeoBlock,
        )

        fun fromDeepLink(path: String): DeepLinkStart? =
            ScreenDeepLinks.matchStart(path, QR, DEEP_LINK_STARTS)
    }
}

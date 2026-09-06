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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import to.bitkit.R
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.repositories.LightningState
import to.bitkit.repositories.PaykitPaymentRequest
import to.bitkit.repositories.PaykitPaymentRequestDraft
import to.bitkit.repositories.PaykitPaymentRequestTarget
import to.bitkit.repositories.WalletState
import to.bitkit.ui.components.ConnectionIssuesView
import to.bitkit.ui.navigateTo
import to.bitkit.ui.openNotificationSettings
import to.bitkit.ui.screens.paymentrequests.PaymentRequestAmountScreen
import to.bitkit.ui.screens.paymentrequests.PaymentRequestDetailsScreen
import to.bitkit.ui.screens.paymentrequests.PaymentRequestRecipientScreen
import to.bitkit.ui.screens.paymentrequests.PaymentRequestSentScreen
import to.bitkit.ui.screens.transfer.hardware.HwPassphrasePromptSheet
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
@Suppress("CyclomaticComplexMethod")
@Composable
fun ReceiveSheet(
    appViewModel: AppViewModel,
    navigateToExternalConnection: () -> Unit,
    walletState: WalletState,
    isOffline: Boolean,
    startRoute: ReceiveRoute = ReceiveRoute.QR,
    hardwareWalletId: String? = null,
    editInvoiceAmountViewModel: AmountInputViewModel = hiltViewModel(),
    paymentRequestAmountViewModel: AmountInputViewModel = hiltViewModel(key = "PaymentRequestAmount"),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    hwReceiveViewModel: HwReceiveViewModel = hiltViewModel(),
) {
    val wallet = requireNotNull(walletViewModel)
    val navController = rememberNavController()
    val rootRoute = startRoute.rootRoute()

    LaunchedEffect(Unit) { editInvoiceAmountViewModel.clearInput() }
    LaunchedEffect(startRoute) { navController.navigateToReceiveStart(startRoute) }

    val cjitInvoice = remember { mutableStateOf<String?>(null) }
    val showCreateCjit = remember { mutableStateOf(false) }
    val cjitEntryDetails = remember { mutableStateOf<CjitEntryDetails?>(null) }
    val invoiceEditState = remember { ReceiveInvoiceEditState() }
    val lightningState: LightningState by wallet.lightningState.collectAsStateWithLifecycle()
    val paymentRequestTargets by appViewModel.eligiblePaymentRequestTargets.collectAsStateWithLifecycle()
    val paymentRequestContacts by appViewModel.pubkyContacts.collectAsStateWithLifecycle()
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
    val hardwareWallets by hwReceiveViewModel.wallets.collectAsStateWithLifecycle()
    val hwReceiveState by hwReceiveViewModel.uiState.collectAsStateWithLifecycle()
    val selectedHardwareWalletId = hardwareWalletId ?: hardwareWallets.singleOrNull()?.id

    DisposableEffect(hwReceiveViewModel) {
        onDispose(hwReceiveViewModel::cancel)
    }
    var selectedPaymentRequestTarget by remember(startRoute) {
        mutableStateOf(
            (startRoute as? ReceiveRoute.PaymentRequestAmount)?.let {
                val publicKey = it.publicKey ?: return@let null
                val receiverPath = it.receiverPath ?: return@let null
                PaykitPaymentRequestTarget(publicKey, receiverPath)
            }
        )
    }
    var skipPaymentRequestAmount by remember { mutableStateOf(false) }
    var isEditingPaymentRequestAmount by remember { mutableStateOf(false) }

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
                startDestination = rootRoute,
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
                        onClickEditInvoice = {
                            invoiceEditState.beginSoftwareEdit()
                            navController.navigateTo(ReceiveRoute.EditInvoice)
                        },
                        onClickHardwareEditInvoice = {
                            invoiceEditState.beginHardwareEdit()
                            navController.navigateTo(ReceiveRoute.EditInvoice)
                        },
                        initialTab = invoiceEditState.initialTab(hardwareWalletId),
                        hardwareWalletId = selectedHardwareWalletId,
                        hardwareReceiveState = hwReceiveState,
                        onLoadHardwareAddress = hwReceiveViewModel::loadAddress,
                        onRetryHardwareAddress = hwReceiveViewModel::retryAddress,
                        onVerifyHardwareAddress = hwReceiveViewModel::verifyAddress,
                        showPaymentRequestContacts = paymentRequestTargets.isNotEmpty(),
                        onClickPaymentRequestContacts = {
                            paymentRequestDraft = paymentRequestDraft.copy(
                                amountSats = 0uL,
                                note = "",
                                expiresAt = Clock.System.now() + 7.days,
                            )
                            selectedPaymentRequestTarget = null
                            skipPaymentRequestAmount = false
                            isEditingPaymentRequestAmount = false
                            navController.navigateTo(ReceiveRoute.PaymentRequestRecipient)
                        },
                    )
                }
                composableWithDefaultTransitions<ReceiveRoute.PaymentRequestAmount> { backStackEntry ->
                    val route = backStackEntry.toRoute<ReceiveRoute.PaymentRequestAmount>()
                    val routeTarget = route.publicKey?.let { publicKey ->
                        route.receiverPath?.let { receiverPath -> PaykitPaymentRequestTarget(publicKey, receiverPath) }
                    }
                    val contact = (routeTarget ?: selectedPaymentRequestTarget)?.let { target ->
                        paymentRequestContacts.firstOrNull {
                            PubkyPublicKeyFormat.matches(it.publicKey, target.publicKey)
                        }
                    }
                    PaymentRequestAmountScreen(
                        amountInputViewModel = paymentRequestAmountViewModel,
                        initialDraft = paymentRequestDraft,
                        contact = contact,
                        onBack = {
                            isEditingPaymentRequestAmount = false
                            if (!navController.popBackStack()) appViewModel.hideSheet()
                        },
                        onContinue = {
                            paymentRequestDraft = it
                            if (isEditingPaymentRequestAmount) {
                                isEditingPaymentRequestAmount = false
                                navController.popBackStack()
                            } else {
                                navController.navigateTo(ReceiveRoute.PaymentRequestDetails)
                            }
                        },
                    )
                }
                composableWithDefaultTransitions<ReceiveRoute.PaymentRequestRecipient> {
                    PaymentRequestRecipientScreen(
                        appViewModel = appViewModel,
                        onBack = {
                            if (!navController.popBackStack()) appViewModel.hideSheet()
                        },
                        onSelected = { target ->
                            selectedPaymentRequestTarget = target
                            navController.navigateTo(
                                if (skipPaymentRequestAmount) {
                                    ReceiveRoute.PaymentRequestDetails
                                } else {
                                    ReceiveRoute.PaymentRequestAmount()
                                }
                            )
                        },
                    )
                }
                composableWithDefaultTransitions<ReceiveRoute.PaymentRequestDetails> {
                    val target = selectedPaymentRequestTarget
                    if (target != null) {
                        PaymentRequestDetailsScreen(
                            appViewModel = appViewModel,
                            draft = paymentRequestDraft,
                            target = target,
                            onBack = { navController.popBackStack() },
                            onEditAmount = {
                                paymentRequestDraft = it
                                isEditingPaymentRequestAmount = true
                                navController.navigateTo(ReceiveRoute.PaymentRequestAmount())
                            },
                            onSent = {
                                createdPaymentRequest = it
                                navController.navigateToPaymentRequestSent()
                            },
                        )
                    } else {
                        LaunchedEffect(Unit) {
                            if (!navController.popBackStack()) appViewModel.hideSheet()
                        }
                    }
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
                            selectedPaymentRequestTarget = null
                            skipPaymentRequestAmount = true
                            isEditingPaymentRequestAmount = false
                            navController.navigateTo(ReceiveRoute.PaymentRequestRecipient)
                        },
                        navigateReceiveConfirm = { entry ->
                            cjitEntryDetails.value = entry
                            navController.navigateTo(ReceiveRoute.ConfirmIncreaseInbound)
                        },
                        onchainOnly = invoiceEditState.isHardwareInvoice,
                        updateOnchainInvoice = wallet::setBip21AmountSats,
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

        ReceivePassphrasePrompt(
            state = hwReceiveState,
            onSubmit = hwReceiveViewModel::submitPassphrase,
            onDismiss = hwReceiveViewModel::dismissPassphrase,
        )

        AnimatedVisibility(
            visible = isOffline,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            ConnectionIssuesView(titleText = stringResource(R.string.wallet__receive_bitcoin))
        }
    }
}

@Stable
internal class ReceiveInvoiceEditState {
    var isHardwareInvoice by mutableStateOf(false)
        private set

    fun beginSoftwareEdit() {
        isHardwareInvoice = false
    }

    fun beginHardwareEdit() {
        isHardwareInvoice = true
    }

    fun initialTab(hardwareWalletId: String?): ReceiveTab? =
        ReceiveTab.TREZOR.takeIf { hardwareWalletId != null || isHardwareInvoice }
}

@Composable
internal fun ReceivePassphrasePrompt(
    state: HwReceiveUiState,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!state.isPassphraseRequired) return

    HwPassphrasePromptSheet(
        isVerifying = state.isVerifyingPassphrase,
        onSubmit = onSubmit,
        onDismiss = onDismiss,
        bodyText = stringResource(R.string.hardware__passphrase_verify_address_text),
    )
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
    data object PaymentRequestRecipient : InternalOnly

    @Serializable
    data class PaymentRequestAmount(
        val publicKey: String? = null,
        val receiverPath: String? = null,
    ) : InternalOnly

    @Serializable
    data object PaymentRequestDetails : InternalOnly

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

internal fun ReceiveRoute.rootRoute(): ReceiveRoute = if (this is ReceiveRoute.DeepLinkStart) ReceiveRoute.QR else this

internal fun NavController.navigateToReceiveStart(startRoute: ReceiveRoute) {
    if (startRoute != startRoute.rootRoute()) navigateTo(startRoute)
}

internal fun NavController.navigateToPaymentRequestSent() {
    navigateTo(ReceiveRoute.PaymentRequestSent) {
        popUpTo(graph.id) { inclusive = true }
    }
}

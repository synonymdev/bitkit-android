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
import to.bitkit.models.PaykitAmount
import to.bitkit.models.PaykitAsset
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.repositories.LightningState
import to.bitkit.repositories.PaykitPaymentRequest
import to.bitkit.repositories.PaykitPaymentRequestDraft
import to.bitkit.repositories.PaykitPaymentRequestTarget
import to.bitkit.repositories.WalletState
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.components.ConnectionIssuesView
import to.bitkit.ui.components.modelNameRes
import to.bitkit.ui.navigateTo
import to.bitkit.ui.screens.paymentrequests.PaymentRequestAmountScreen
import to.bitkit.ui.screens.paymentrequests.PaymentRequestDetailsScreen
import to.bitkit.ui.screens.paymentrequests.PaymentRequestRecipientScreen
import to.bitkit.ui.screens.paymentrequests.PaymentRequestSentScreen
import to.bitkit.ui.screens.transfer.hardware.HwPassphrasePromptSheet
import to.bitkit.ui.screens.wallets.send.AddTagScreen
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.utils.ScreenDeepLinks
import to.bitkit.ui.utils.composableWithDefaultTransitions
import to.bitkit.ui.walletViewModel
import to.bitkit.utils.Bip21Utils
import to.bitkit.viewmodels.AmountInputViewModel
import to.bitkit.viewmodels.AppViewModel
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
@Composable
fun ReceiveSheet(
    appViewModel: AppViewModel,
    navigateToPrimaryNode: () -> Unit,
    walletState: WalletState,
    isOffline: Boolean,
    startRoute: ReceiveRoute = ReceiveRoute.QR,
    initialTab: ReceiveTab? = null,
    onBlockingChange: (Boolean) -> Unit = {},
    onUsdtSelected: (Boolean) -> Unit = {},
    hardwareWalletId: String? = null,
    editInvoiceAmountViewModel: AmountInputViewModel = hiltViewModel(),
    paymentRequestAmountViewModel: AmountInputViewModel = hiltViewModel(key = "PaymentRequestAmount"),
    hwReceiveViewModel: HwReceiveViewModel = hiltViewModel(),
    primaryReceiveViewModel: PrimaryReceiveViewModel = hiltViewModel(),
    directFundingViewModel: DirectFundingReceiveViewModel = hiltViewModel(),
) {
    val wallet = requireNotNull(walletViewModel)
    val navController = rememberNavController()
    val rootRoute = startRoute.rootRoute()
    val currencies = LocalCurrencies.current

    LaunchedEffect(Unit) { editInvoiceAmountViewModel.clearInput() }
    val primaryReceiveState by primaryReceiveViewModel.uiState.collectAsStateWithLifecycle()
    val hasPrimaryNode by primaryReceiveViewModel.hasPrimaryNode.collectAsStateWithLifecycle()
    val displayedWalletState = primaryReceiveState.offer?.takeIf { primaryReceiveState.invoice != null }?.let { offer ->
        walletState.copy(
            bip21 = Bip21Utils.buildBip21Url(
                bitcoinAddress = primaryReceiveState.onchainAddress.orEmpty(),
                amountSats = offer.amountSats,
                message = offer.description,
            ),
            onchainAddress = primaryReceiveState.onchainAddress.orEmpty(),
            bip21AmountSats = offer.amountSats,
            bip21Description = offer.description,
        )
    } ?: walletState
    val directFundingRequest by directFundingViewModel.request.collectAsStateWithLifecycle()
    LaunchedEffect(displayedWalletState.bip21, displayedWalletState.onchainAddress, primaryReceiveState.invoice) {
        directFundingViewModel.update(
            displayedWalletState.bip21AmountSats,
            displayedWalletState.onchainAddress,
            primaryReceiveState.invoice ?: displayedWalletState.bip21,
        )
    }
    val invoiceEditState = remember { ReceiveInvoiceEditState() }

    LaunchedEffect(startRoute) {
        primaryReceiveViewModel.clear()
        navController.navigateToReceiveStart(startRoute)
    }
    var editInvoiceSourceTab by remember { mutableStateOf(ReceiveTab.AUTO) }
    var requestedAmountSats by remember { mutableStateOf<ULong?>(null) }
    val lightningState: LightningState by wallet.lightningState.collectAsStateWithLifecycle()
    val paymentRequestTargets by appViewModel.eligiblePaymentRequestTargets.collectAsStateWithLifecycle()
    val paymentRequestContacts by appViewModel.pubkyContacts.collectAsStateWithLifecycle()
    var paymentRequestDraft by remember {
        mutableStateOf(
            PaykitPaymentRequestDraft(
                amount = PaykitAmount(PaykitAsset.BTC, 0uL),
                note = "",
                expiresAt = Clock.System.now() + 7.days,
            )
        )
    }
    var createdPaymentRequest by remember { mutableStateOf<PaykitPaymentRequest?>(null) }
    val hardwareWallets by hwReceiveViewModel.wallets.collectAsStateWithLifecycle()
    val hwReceiveState by hwReceiveViewModel.uiState.collectAsStateWithLifecycle()
    val selectedHardwareWalletId = hardwareWalletId ?: hardwareWallets.singleOrNull()?.id

    DisposableEffect(hwReceiveViewModel, primaryReceiveViewModel) {
        onDispose {
            hwReceiveViewModel.cancel()
            primaryReceiveViewModel.clear()
            directFundingViewModel.clear()
        }
    }
    var selectedPaymentRequestTarget by remember(startRoute) {
        mutableStateOf(
            (startRoute as? ReceiveRoute.PaymentRequestAmount)?.let {
                val publicKey = it.publicKey ?: return@let null
                PaykitPaymentRequestTarget(publicKey)
            }
        )
    }
    var skipPaymentRequestAmount by remember { mutableStateOf(false) }
    var isEditingPaymentRequestAmount by remember { mutableStateOf(false) }

    fun resetEditInvoiceAmount() {
        val amountSats = walletState.bip21AmountSats
        if (amountSats == null || amountSats == 0uL) {
            editInvoiceAmountViewModel.clearInput()
        } else {
            editInvoiceAmountViewModel.setSats(amountSats.toLong(), currencies)
        }
    }

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
                    ReceiveQrScreen(
                        onUsdtSelected = onUsdtSelected,
                        primaryInvoice = primaryReceiveState.invoice,
                        directFundingRequest = directFundingRequest?.requestFor(
                            displayedWalletState.bip21AmountSats,
                            displayedWalletState.onchainAddress,
                            primaryReceiveState.invoice ?: displayedWalletState.bip21,
                        ),
                        walletState = displayedWalletState,
                        lightningState = lightningState,
                        onClickReceiveLightning = {
                            if (!hasPrimaryNode) {
                                navigateToPrimaryNode()
                            } else {
                                requestedAmountSats = walletState.bip21AmountSats
                                primaryReceiveViewModel.clear()
                                navController.navigateTo(ReceiveRoute.Amount)
                            }
                        },
                        onClickEditInvoice = {
                            editInvoiceSourceTab = it
                            invoiceEditState.beginSoftwareEdit(it)
                            resetEditInvoiceAmount()
                            navController.navigateTo(ReceiveRoute.EditInvoice)
                        },
                        onClickHardwareEditInvoice = {
                            editInvoiceSourceTab = ReceiveTab.HARDWARE
                            invoiceEditState.beginHardwareEdit()
                            resetEditInvoiceAmount()
                            navController.navigateTo(ReceiveRoute.EditInvoice)
                        },
                        initialTab = invoiceEditState.initialTab(hardwareWalletId) ?: initialTab,
                        onBlockingChange = onBlockingChange,
                        onClose = appViewModel::hideSheet,
                        hardwareWalletId = selectedHardwareWalletId,
                        hardwareTabLabel = hardwareWallets.firstOrNull { it.id == selectedHardwareWalletId }
                            ?.let { stringResource(it.vendor.modelNameRes()) },
                        hardwareReceiveState = hwReceiveState,
                        onLoadHardwareAddress = hwReceiveViewModel::loadAddress,
                        onRetryHardwareAddress = hwReceiveViewModel::retryAddress,
                        onRetryInvoice = { wallet.retryReceiveInvoice() },
                        onVerifyHardwareAddress = hwReceiveViewModel::verifyAddress,
                        showPaymentRequestContacts = paymentRequestTargets.isNotEmpty(),
                        onClickPaymentRequestContacts = {
                            paymentRequestDraft = paymentRequestDraft.copy(
                                amount = PaykitAmount(PaykitAsset.BTC, 0uL),
                                acceptedPaymentEndpointIdentifiers = null,
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
                        PaykitPaymentRequestTarget(publicKey)
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
                        primaryReceiveViewModel = primaryReceiveViewModel,
                        description = walletState.bip21Description,
                        initialAmountSats = requestedAmountSats,
                        onQuoted = { navController.navigateTo(ReceiveRoute.Confirm) },
                        onBack = {
                            primaryReceiveViewModel.clear()
                            navController.popBackStack()
                        },
                    )
                }
                composableWithDefaultTransitions<ReceiveRoute.Confirm> {
                    PrimaryReceiveConfirmScreen(
                        viewModel = primaryReceiveViewModel,
                        onCreated = { amountSats ->
                            wallet.updateOnchainBip21Amount(amountSats)
                            navController.navigateTo(ReceiveRoute.QR) {
                                popUpTo(ReceiveRoute.QR) { inclusive = true }
                            }
                        },
                        onBack = { navController.popBackStack() },
                    )
                }
                composableWithDefaultTransitions<ReceiveRoute.EditInvoice> {
                    val walletUiState by wallet.walletState.collectAsStateWithLifecycle()
                    @Suppress("ViewModelForwarding")
                    EditInvoiceScreen(
                        amountInputViewModel = editInvoiceAmountViewModel,
                        walletUiState = walletUiState,
                        sourceTab = editInvoiceSourceTab,
                        onBack = { navController.popBackStack() },
                        updateInvoice = {
                            primaryReceiveViewModel.clear()
                            wallet.updateBip21Invoice(it)
                        },
                        onClickAddTag = { navController.navigateTo(ReceiveRoute.AddTag) },
                        onClickTag = wallet::removeTag,
                        onDescriptionUpdate = {
                            primaryReceiveViewModel.clear()
                            wallet.updateBip21Description(it)
                        },
                        showPaymentRequestButton = paymentRequestTargets.isNotEmpty(),
                        onClickPaymentRequest = { amountSats, note ->
                            paymentRequestDraft = PaykitPaymentRequestDraft(
                                amount = PaykitAmount(PaykitAsset.BTC, amountSats),
                                note = note,
                                expiresAt = Clock.System.now() + 7.days,
                            )
                            selectedPaymentRequestTarget = null
                            skipPaymentRequestAmount = true
                            isEditingPaymentRequestAmount = false
                            navController.navigateTo(ReceiveRoute.PaymentRequestRecipient)
                        },
                        onchainOnly = invoiceEditState.isHardwareInvoice,
                        updateOnchainInvoice = wallet::setBip21AmountSats,
                        onRequestLightning = { amountSats ->
                            requestedAmountSats = amountSats.takeIf { it > 0uL }
                            primaryReceiveViewModel.clear()
                            if (!hasPrimaryNode) {
                                navigateToPrimaryNode()
                            } else {
                                navController.navigateTo(ReceiveRoute.Amount)
                            }
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
    private var returnTab by mutableStateOf<ReceiveTab?>(null)

    fun beginSoftwareEdit(sourceTab: ReceiveTab) {
        isHardwareInvoice = false
        returnTab = sourceTab
    }

    fun beginHardwareEdit() {
        isHardwareInvoice = true
        returnTab = ReceiveTab.HARDWARE
    }

    fun initialTab(hardwareWalletId: String?): ReceiveTab? =
        returnTab ?: ReceiveTab.HARDWARE.takeIf { hardwareWalletId != null || isHardwareInvoice }
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
    data object EditInvoice : DeepLinkStart

    @Serializable
    data object AddTag : DeepLinkStart

    @Serializable
    data object PaymentRequestRecipient : InternalOnly

    @Serializable
    data class PaymentRequestAmount(
        val publicKey: String? = null,
    ) : InternalOnly

    @Serializable
    data object PaymentRequestDetails : InternalOnly

    @Serializable
    data object PaymentRequestSent : InternalOnly

    companion object {
        private val DEEP_LINK_STARTS: List<DeepLinkStart> = listOf(
            QR,
            Amount,
            EditInvoice,
            AddTag,
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

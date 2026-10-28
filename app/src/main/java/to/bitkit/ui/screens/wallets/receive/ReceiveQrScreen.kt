package to.bitkit.ui.screens.wallets.receive

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Devices.NEXUS_5
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.ChannelDetails
import to.bitkit.R
import to.bitkit.ext.calculateRemoteBalance
import to.bitkit.ext.setClipboardText
import to.bitkit.models.NodeLifecycleState
import to.bitkit.models.ReceiveLiquidityDecision
import to.bitkit.repositories.LightningState
import to.bitkit.repositories.WalletState
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.GradientCircularProgressIndicator
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.QrCodeImage
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.TertiaryButton
import to.bitkit.ui.components.Tooltip
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.rememberQrBitmap
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.screens.wallets.activity.components.CustomTabRowWithSpacing
import to.bitkit.ui.screens.wallets.usdt.UsdtDepositReceiveScreen
import to.bitkit.ui.shared.effects.SetMaxBrightness
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.shared.util.shareQrCode
import to.bitkit.ui.shared.util.shareText
import to.bitkit.ui.theme.AppShapes
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@OptIn(FlowPreview::class)
@Composable
@Suppress("CyclomaticComplexMethod")
fun ReceiveQrScreen(
    primaryInvoice: String?,
    walletState: WalletState,
    lightningState: LightningState,
    onClickEditInvoice: (ReceiveTab) -> Unit,
    onClickReceiveLightning: () -> Unit,
    modifier: Modifier = Modifier,
    directFundingRequest: String? = null,
    onClickHardwareEditInvoice: () -> Unit = { onClickEditInvoice(ReceiveTab.HARDWARE) },
    initialTab: ReceiveTab? = null,
    onClose: () -> Unit = {},
    onBlockingChange: (Boolean) -> Unit = {},
    onUsdtSelected: (Boolean) -> Unit = {},
    hardwareWalletId: String? = null,
    /** Vendor name shown on the hardware tab, e.g. "Trezor" or "Jade". */
    hardwareTabLabel: String? = null,
    hardwareReceiveState: HwReceiveUiState = HwReceiveUiState(),
    onLoadHardwareAddress: (String) -> Unit = {},
    onRetryHardwareAddress: () -> Unit = {},
    onRetryInvoice: () -> Unit = {},
    onVerifyHardwareAddress: () -> Unit = {},
    showPaymentRequestContacts: Boolean = false,
    onClickPaymentRequestContacts: () -> Unit = {},
) {
    SetMaxBrightness()

    val haptic = LocalHapticFeedback.current
    val hasReadyChannels = lightningState.channels.any { it.isChannelReady }
    val readyInboundLiquiditySats = remember(lightningState.channels) {
        lightningState.channels.filter { it.isChannelReady }.calculateRemoteBalance()
    }
    val canCreateLightningInvoice = remember(
        hasReadyChannels,
        readyInboundLiquiditySats,
        walletState.bip21AmountSats,
    ) {
        ReceiveLiquidityDecision.canCreateLightningInvoice(
            hasReadyChannels = hasReadyChannels,
            inboundCapacitySats = readyInboundLiquiditySats,
            invoiceAmountSats = walletState.bip21AmountSats,
        )
    }

    var showDetails by remember { mutableStateOf(false) }

    val usdtEnabled by appViewModel?.isUsdtEnabled?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(false) }
    val visibleTabs = remember(hardwareWalletId, usdtEnabled) {
        visibleReceiveTabs(hardwareWalletId != null, usdtEnabled)
    }
    val defaultTab = remember(visibleTabs, initialTab) {
        initialTab?.takeIf { it in visibleTabs } ?: visibleTabs.defaultReceiveTab()
    }

    val invoicesByTab = remember(
        visibleTabs,
        walletState.bip21,
        walletState.bolt11,
        walletState.onchainAddress,
        primaryInvoice,
        directFundingRequest,
        lightningState.nodeLifecycleState,
        canCreateLightningInvoice,
        walletState.hasReceiveInvoiceError,
        hardwareReceiveState.address,
        walletState.bip21AmountSats,
        walletState.bip21Description,
        walletState.isReceiveInvoiceUpdating,
    ) {
        visibleTabs.associateWith { tab ->
            getInvoiceForTab(
                tab = tab,
                bip21 = walletState.bip21,
                bolt11 = walletState.bolt11,
                primaryInvoice = primaryInvoice,
                directFundingRequest = directFundingRequest,
                isNodeRunning = lightningState.nodeLifecycleState.isRunning(),
                canCreateLightningInvoice = canCreateLightningInvoice && !walletState.hasReceiveInvoiceError,
                onchainAddress = walletState.onchainAddress,
                hardwareAddress = hardwareReceiveState.address?.address.orEmpty(),
                hardwareAmountSats = walletState.bip21AmountSats,
                hardwareMessage = walletState.bip21Description,
                isReceiveInvoiceUpdating = walletState.isReceiveInvoiceUpdating,
            )
        }
    }

    // LazyRow state with snap behavior; the pages wrap each end with a copy of the tab at the other end
    val scope = rememberCoroutineScope()
    val receivePages = remember(visibleTabs) {
        (listOf(visibleTabs.last()) + visibleTabs + visibleTabs.first()).toImmutableList()
    }
    val lazyListState = rememberLazyListState(
        initialFirstVisibleItemIndex = visibleTabs.pageIndexOf(defaultTab),
    )

    val snapBehavior = rememberSnapFlingBehavior(
        lazyListState = lazyListState,
        snapPosition = SnapPosition.Center,
    )

    // Calculate current tab based on scroll position for smooth indicator and color updates
    var selectedTab by remember {
        mutableStateOf(defaultTab)
    }
    var hasAppliedInitialTab by remember { mutableStateOf(false) }
    var appliedInitialTab by remember { mutableStateOf<ReceiveTab?>(null) }

    LaunchedEffect(visibleTabs, initialTab) {
        val requestedTab = initialTab?.takeIf { it in visibleTabs }
        val shouldApplyInitialTab = !hasAppliedInitialTab || requestedTab != null && requestedTab != appliedInitialTab
        if (shouldApplyInitialTab) {
            hasAppliedInitialTab = true
            appliedInitialTab = requestedTab
            requestedTab?.let {
                selectedTab = it
                lazyListState.scrollToItem(visibleTabs.pageIndexOf(it))
                return@LaunchedEffect
            }
        }
        if (selectedTab !in visibleTabs) {
            val fallbackTab = visibleTabs.defaultReceiveTab()
            selectedTab = fallbackTab
            lazyListState.scrollToItem(visibleTabs.pageIndexOf(fallbackTab))
        }
    }

    LaunchedEffect(lazyListState, visibleTabs.size) {
        snapshotFlow { lazyListState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index ->
                val tab = visibleTabs.getOrNull(index - 1) ?: return@collect
                if (selectedTab != ReceiveTab.USDT) selectedTab = tab
            }
    }

    // Swap a settled wrap page for the tab it shows, so a swipe past either end slides on to the other end
    LaunchedEffect(lazyListState, visibleTabs) {
        snapshotFlow { lazyListState.isScrollInProgress }
            .filter { !it }
            .collect {
                val tab = when (lazyListState.firstVisibleItemIndex) {
                    0 -> visibleTabs.last()
                    visibleTabs.size + 1 -> visibleTabs.first()
                    else -> return@collect
                }
                selectedTab = tab
                lazyListState.scrollToItem(visibleTabs.pageIndexOf(tab))
            }
    }

    LaunchedEffect(primaryInvoice) {
        if (primaryInvoice != null) {
            lazyListState.scrollToItem(visibleTabs.pageIndexOf(ReceiveTab.AUTO))
            selectedTab = ReceiveTab.AUTO
        }
    }

    LaunchedEffect(selectedTab, hardwareWalletId) {
        showDetails = false
        if (selectedTab == ReceiveTab.HARDWARE && hardwareWalletId != null) {
            onLoadHardwareAddress(hardwareWalletId)
        }
    }

    val showingLightningRequest = remember(lightningState, primaryInvoice, canCreateLightningInvoice) {
        !canCreateLightningInvoice &&
            lightningState.nodeLifecycleState.isRunning() &&
            primaryInvoice.isNullOrEmpty()
    }

    fun selectReceiveTab(tab: ReceiveTab) {
        val wrapsToFirst = selectedTab == visibleTabs.last() && tab == visibleTabs.first()
        selectedTab = tab
        scope.launch {
            // The pager composes again when leaving USDT; let it lay out before sliding
            repeat(2) { withFrameNanos {} }
            lazyListState.animateScrollToItem(if (wrapsToFirst) visibleTabs.size + 1 else visibleTabs.pageIndexOf(tab))
        }
    }

    DisposableEffect(selectedTab) {
        onUsdtSelected(selectedTab == ReceiveTab.USDT)
        onDispose { onUsdtSelected(false) }
    }

    if (selectedTab == ReceiveTab.USDT) {
        Column(
            modifier = modifier.fillMaxSize().background(
                Colors.Gray7
            ).navigationBarsPadding().padding(horizontal = 16.dp)
        ) {
            UsdtDepositReceiveScreen(
                viewModel = hiltViewModel(),
                tabs = visibleTabs,
                onSelectTab = ::selectReceiveTab,
                onBack = onClose,
                onBlockingChange = onBlockingChange,
                onContacts = onClickPaymentRequestContacts.takeIf { showPaymentRequestContacts },
            )
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .keepScreenOn()
    ) {
        SheetTopBar(
            titleText = stringResource(R.string.wallet__receive_bitcoin),
            action = if (showPaymentRequestContacts) {
                {
                    IconButton(
                        onClick = onClickPaymentRequestContacts,
                        modifier = Modifier.testTag("ReceivePaymentRequestContacts"),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_users),
                            contentDescription = stringResource(R.string.wallet__payment_request_choose_recipient),
                            tint = Colors.White,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            } else {
                null
            },
        )
        Column {
            VerticalSpacer(16.dp)

            // Tab row
            CustomTabRowWithSpacing(
                tabs = visibleTabs,
                currentTabIndex = visibleTabs.indexOf(selectedTab),
                labelOverrides = remember(hardwareTabLabel) {
                    hardwareTabLabel?.let { persistentMapOf(ReceiveTab.HARDWARE to it) } ?: persistentMapOf()
                },
                onTabChange = { tab ->
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val newIndex = visibleTabs.pageIndexOf(tab)
                    selectedTab = tab
                    showDetails = false
                    scope.launch {
                        // USDT replaces the pager at once; land it on the USDT page so the next swipe starts there
                        if (tab == ReceiveTab.USDT) {
                            lazyListState.scrollToItem(newIndex)
                        } else {
                            lazyListState.animateScrollToItem(newIndex)
                        }
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            VerticalSpacer(16.dp)

            // Content area (QR or Details) with LazyRow
            LazyRow(
                state = lazyListState,
                flingBehavior = snapBehavior,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                userScrollEnabled = true,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                itemsIndexed(
                    items = receivePages,
                    key = { index, tab -> receivePageKey(index, tab, visibleTabs.size) }
                ) { _, tab ->
                    val displayMode = getReceiveInvoiceDisplayMode(
                        tab = tab,
                        primaryInvoice = primaryInvoice,
                        hasInvoiceError = walletState.hasReceiveInvoiceError,
                        isUpdating = walletState.isReceiveInvoiceUpdating,
                        showDetails = showDetails,
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillParentMaxWidth()
                            .fillParentMaxHeight()
                    ) {
                        when {
                            showingLightningRequest && tab == ReceiveTab.SPENDING -> {
                                PrimaryReceiveOnboardingView(
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            tab == ReceiveTab.HARDWARE && hardwareReceiveState.address == null -> {
                                HardwareAddressLoadingView(
                                    isLoading = hardwareReceiveState.isLoadingAddress,
                                    hasFailed = hardwareReceiveState.addressLoadFailed,
                                    onRetry = onRetryHardwareAddress,
                                    modifier = Modifier.weight(1f),
                                )
                            }

                            displayMode == ReceiveInvoiceDisplayMode.ERROR -> {
                                ReceiveInvoiceErrorView(
                                    onRetry = onRetryInvoice,
                                    onEdit = { onClickEditInvoice(tab) },
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            displayMode == ReceiveInvoiceDisplayMode.DETAILS -> {
                                ReceiveDetailsView(
                                    tab = tab,
                                    walletState = walletState,
                                    primaryInvoice = primaryInvoice,
                                    isNodeRunning = lightningState.nodeLifecycleState.isRunning(),
                                    onClickEditInvoice = { onClickEditInvoice(tab) },
                                    onClickHardwareEditInvoice = onClickHardwareEditInvoice,
                                    hardwareAddress = hardwareReceiveState.address?.address,
                                    hardwareInvoice = invoicesByTab[ReceiveTab.HARDWARE].orEmpty(),
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            else -> {
                                val invoice = invoicesByTab[tab].orEmpty()
                                val copyText = when (tab) {
                                    ReceiveTab.SAVINGS -> getSavingsCopyText(
                                        walletState.bip21,
                                        walletState.onchainAddress,
                                    )

                                    ReceiveTab.HARDWARE -> invoice.takeIf { '?' in it }
                                        ?: hardwareReceiveState.address?.address.orEmpty()

                                    else -> invoice
                                }

                                key(displayMode, invoice) {
                                    ReceiveQrView(
                                        uri = invoice,
                                        copyText = copyText,
                                        qrLogoPainter = painterResource(getQrLogoResource(tab)),
                                        onClickEditInvoice = if (tab == ReceiveTab.HARDWARE) {
                                            onClickHardwareEditInvoice
                                        } else {
                                            { onClickEditInvoice(tab) }
                                        },
                                        accentColor = tab.accentColor,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }
            }

            VerticalSpacer(24.dp)

            if (showingLightningRequest && selectedTab == ReceiveTab.AUTO) {
                SecondaryButton(
                    text = stringResource(R.string.wallet_primary__receive_action),
                    onClick = onClickReceiveLightning,
                    modifier = Modifier.padding(horizontal = 16.dp).testTag("PrimaryReceiveRequest")
                )
                VerticalSpacer(12.dp)
            }

            val showLightningRequestButton = showingLightningRequest && selectedTab == ReceiveTab.SPENDING
            val buttonVariant = when {
                showLightningRequestButton -> BottomButtonVariant.REQUEST_LIGHTNING
                showDetails -> BottomButtonVariant.SHOW_QR
                else -> BottomButtonVariant.SHOW_DETAILS
            }
            Crossfade(
                targetState = buttonVariant,
                label = "ReceiveBottomButtonCrossfade",
            ) { variant ->
                when (variant) {
                    BottomButtonVariant.REQUEST_LIGHTNING -> PrimaryButton(
                        text = stringResource(R.string.wallet_primary__receive_action),
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_lightning_alt),
                                tint = Colors.Purple,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            onClickReceiveLightning()
                            showDetails = false
                        },
                        fullWidth = true,
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .testTag("PrimaryReceiveRequest")
                    )

                    BottomButtonVariant.SHOW_QR -> Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    ) {
                        if (selectedTab == ReceiveTab.HARDWARE) {
                            SecondaryButton(
                                text = stringResource(R.string.hardware__verify_address),
                                enabled = hardwareReceiveState.address != null,
                                isLoading = hardwareReceiveState.isVerifyingAddress,
                                onClick = onVerifyHardwareAddress,
                                modifier = Modifier.testTag("HardwareVerifyAddress")
                            )
                        }

                        PrimaryButton(
                            text = stringResource(R.string.wallet__receive_show_qr),
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_qr_purple),
                                    tint = Colors.White,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            onClick = { showDetails = false },
                            fullWidth = true,
                            modifier = Modifier.testTag("QRCode")
                        )
                    }

                    BottomButtonVariant.SHOW_DETAILS -> PrimaryButton(
                        text = stringResource(R.string.wallet__receive_show_details),
                        onClick = { showDetails = true },
                        enabled = selectedTab != ReceiveTab.HARDWARE || hardwareReceiveState.address != null,
                        fullWidth = true,
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .testTag("ShowDetails")
                    )
                }
            }

            VerticalSpacer(16.dp)
        }
    }
}

private fun List<ReceiveTab>.pageIndexOf(tab: ReceiveTab): Int = indexOf(tab).coerceAtLeast(0) + 1

private fun receivePageKey(index: Int, tab: ReceiveTab, tabCount: Int): String = when (index) {
    0 -> "wrap-start-${tab.name}"
    tabCount + 1 -> "wrap-end-${tab.name}"
    else -> tab.name
}

private fun List<ReceiveTab>.defaultReceiveTab(): ReceiveTab {
    return if (contains(ReceiveTab.AUTO)) ReceiveTab.AUTO else ReceiveTab.SAVINGS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveQrView(
    uri: String,
    copyText: String,
    qrLogoPainter: Painter?,
    onClickEditInvoice: (() -> Unit)?,
    accentColor: Color,
    modifier: Modifier = Modifier,
    shareContent: String = copyText,
) {
    val context = LocalContext.current
    val qrButtonTooltipState = rememberTooltipState()
    val coroutineScope = rememberCoroutineScope()
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        QrCodeImage(
            content = uri,
            logoPainter = qrLogoPainter,
            tipMessage = stringResource(R.string.wallet__receive_copied),
            onBitmapGenerated = { bitmap -> qrBitmap = bitmap },
            testTag = "QRCode",
            copyContent = copyText,
            modifier = Modifier.weight(1f, fill = false)
        )

        VerticalSpacer(16.dp)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (onClickEditInvoice != null) {
                PrimaryButton(
                    text = stringResource(R.string.common__edit),
                    size = ButtonSize.Small,
                    onClick = onClickEditInvoice,
                    fullWidth = false,
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_pencil_simple),
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("SpecifyInvoiceButton")
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                Tooltip(
                    text = stringResource(R.string.wallet__receive_copied),
                    tooltipState = qrButtonTooltipState,
                ) {
                    PrimaryButton(
                        text = stringResource(R.string.common__copy),
                        size = ButtonSize.Small,
                        enabled = copyText.isNotBlank(),
                        onClick = {
                            context.setClipboardText(copyText)
                            coroutineScope.launch { qrButtonTooltipState.show() }
                        },
                        fullWidth = true,
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_copy),
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        modifier = Modifier
                            .testTag("ReceiveCopyQR")
                    )
                }
            }
            PrimaryButton(
                text = stringResource(R.string.common__share),
                size = ButtonSize.Small,
                enabled = copyText.isNotBlank(),
                onClick = {
                    qrBitmap?.let { bitmap ->
                        shareQrCode(context, bitmap, shareContent)
                    } ?: shareText(context, shareContent)
                },
                fullWidth = false,
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_share),
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                },
                modifier = Modifier.weight(1f)
            )
        }
        VerticalSpacer(16.dp)
    }
}

@Composable
fun PrimaryReceiveOnboardingView(modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(AppShapes.small)
            .background(color = Colors.Black)
            .padding(32.dp)
    ) {
        Display(stringResource(R.string.wallet_primary__lightning).withAccent(accentColor = Colors.Purple))
        VerticalSpacer(8.dp)
        BodyM(
            stringResource(R.string.wallet_primary__receive_description),
            color = Colors.White64,
            modifier = Modifier.fillMaxWidth()
        )
        VerticalSpacer(32.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_lightning_alt),
                tint = Colors.Purple,
                contentDescription = null,
                modifier = Modifier
                    .size(64.dp)
                    .align(Alignment.TopCenter)
            )
            Icon(
                painter = painterResource(R.drawable.arrow),
                tint = Colors.Purple,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 32.dp)
                    .fillMaxHeight()
            )
        }
    }
}

@Composable
private fun ReceiveDetailsView(
    tab: ReceiveTab,
    walletState: WalletState,
    primaryInvoice: String?,
    isNodeRunning: Boolean,
    onClickEditInvoice: () -> Unit,
    modifier: Modifier = Modifier,
    onClickHardwareEditInvoice: () -> Unit = onClickEditInvoice,
    hardwareAddress: String? = null,
    hardwareInvoice: String = "",
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Colors.Black),
        shape = AppShapes.small,
        modifier = modifier
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(32.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            when (tab) {
                ReceiveTab.SAVINGS -> {
                    if (walletState.onchainAddress.isNotEmpty()) {
                        CopyAddressCard(
                            title = stringResource(R.string.wallet__receive_bitcoin_invoice),
                            address = removeLightningFromBip21(
                                bip21 = walletState.bip21,
                                fallbackAddress = walletState.onchainAddress
                            ),
                            body = walletState.onchainAddress,
                            type = CopyAddressType.ONCHAIN,
                            onClickEditInvoice = onClickEditInvoice,
                            testTag = "ReceiveOnchainAddress",
                        )
                    }
                }

                ReceiveTab.AUTO -> {
                    // Show both onchain AND lightning if available
                    if (walletState.onchainAddress.isNotEmpty()) {
                        CopyAddressCard(
                            title = stringResource(R.string.wallet__receive_bitcoin_invoice),
                            address = removeLightningFromBip21(
                                bip21 = walletState.bip21,
                                fallbackAddress = walletState.onchainAddress
                            ),
                            body = walletState.onchainAddress,
                            type = CopyAddressType.ONCHAIN,
                            onClickEditInvoice = onClickEditInvoice,
                            testTag = "ReceiveOnchainAddress",
                        )
                    }
                    val lightningInvoice = primaryInvoice ?: walletState.bolt11.takeIf {
                        !walletState.isReceiveInvoiceUpdating && !walletState.hasReceiveInvoiceError
                    }
                    if (isNodeRunning && !lightningInvoice.isNullOrEmpty()) {
                        CopyAddressCard(
                            title = stringResource(R.string.wallet__receive_lightning_invoice),
                            address = lightningInvoice,
                            type = CopyAddressType.LIGHTNING,
                            onClickEditInvoice = onClickEditInvoice,
                            testTag = "ReceiveLightningAddress",
                        )
                    }
                }

                ReceiveTab.SPENDING -> {
                    val lightningInvoice = (primaryInvoice ?: walletState.bolt11).takeIf {
                        isNodeRunning && !walletState.isReceiveInvoiceUpdating && !walletState.hasReceiveInvoiceError
                    }
                    if (!lightningInvoice.isNullOrEmpty()) {
                        CopyAddressCard(
                            title = stringResource(R.string.wallet__receive_lightning_invoice),
                            address = lightningInvoice,
                            type = CopyAddressType.LIGHTNING,
                            onClickEditInvoice = onClickEditInvoice,
                            testTag = "ReceiveLightningAddress",
                        )
                    } else {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("ReceiveLightningLoading")
                        ) {
                            GradientCircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                }

                ReceiveTab.USDT -> Unit
                ReceiveTab.HARDWARE -> {
                    hardwareAddress?.let { address ->
                        CopyAddressCard(
                            title = stringResource(R.string.wallet__receive_bitcoin_invoice),
                            address = hardwareInvoice.ifBlank { address },
                            body = address,
                            type = CopyAddressType.ONCHAIN,
                            onClickEditInvoice = onClickHardwareEditInvoice,
                            accentColor = Colors.Blue,
                            testTag = "ReceiveHardwareAddress",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HardwareAddressLoadingView(
    isLoading: Boolean,
    hasFailed: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxWidth(),
    ) {
        if (hasFailed) {
            BodyM(
                text = stringResource(R.string.hardware__receive_address_error),
                color = Colors.White64,
            )
            VerticalSpacer(16.dp)
            TertiaryButton(
                text = stringResource(R.string.common__try_again),
                onClick = onRetry,
                fullWidth = false,
            )
        } else if (isLoading) {
            GradientCircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun ReceiveInvoiceErrorView(
    onRetry: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth()
    ) {
        BodyM(text = stringResource(R.string.wallet__error_invoice_update))
        VerticalSpacer(16.dp)
        PrimaryButton(
            text = stringResource(R.string.common__try_again),
            onClick = onRetry,
            modifier = Modifier.testTag("ReceiveInvoiceRetry")
        )
        VerticalSpacer(16.dp)
        SecondaryButton(
            text = stringResource(R.string.common__edit),
            onClick = onEdit,
            modifier = Modifier.testTag("SpecifyInvoiceButton")
        )
    }
}

private enum class BottomButtonVariant { REQUEST_LIGHTNING, SHOW_QR, SHOW_DETAILS }

enum class CopyAddressType { ONCHAIN, LIGHTNING }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CopyAddressCard(
    title: String,
    address: String,
    type: CopyAddressType,
    onClickEditInvoice: (() -> Unit)?,
    body: String? = null,
    testTag: String? = null,
    accentColor: Color? = null,
    shareContent: String = address,
    shareQrContent: String? = null,
) {
    val context = LocalContext.current
    val shareBitmap = shareQrContent?.let { rememberQrBitmap(it, 400.dp) }

    val tooltipState = rememberTooltipState()
    val coroutineScope = rememberCoroutineScope()
    val buttonAccentColor = accentColor ?: when (type) {
        CopyAddressType.ONCHAIN -> Colors.Brand
        CopyAddressType.LIGHTNING -> Colors.Purple
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        Caption13Up(text = title, color = Colors.White64)
        VerticalSpacer(16.dp)
        BodyS(
            text = (body ?: address),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = testTag?.let { Modifier.testTag(it) } ?: Modifier
        )
        VerticalSpacer(16.dp)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (onClickEditInvoice != null) {
                PrimaryButton(
                    text = stringResource(R.string.common__edit),
                    size = ButtonSize.Small,
                    onClick = onClickEditInvoice,
                    fullWidth = false,
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_pencil_simple),
                            contentDescription = null,
                            tint = buttonAccentColor,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("SpecifyInvoiceButton")
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                Tooltip(
                    text = stringResource(R.string.wallet__receive_copied),
                    tooltipState = tooltipState
                ) {
                    Box {
                        PrimaryButton(
                            text = stringResource(R.string.common__copy),
                            size = ButtonSize.Small,
                            onClick = {
                                context.setClipboardText(address)
                                coroutineScope.launch { tooltipState.show() }
                            },
                            fullWidth = true,
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_copy),
                                    contentDescription = null,
                                    tint = buttonAccentColor,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                        )
                    }
                }
            }
            PrimaryButton(
                text = stringResource(R.string.common__share),
                size = ButtonSize.Small,
                enabled = shareQrContent == null || shareBitmap != null,
                onClick = {
                    shareBitmap?.let { shareQrCode(context, it, shareContent) } ?: shareText(context, shareContent)
                },
                fullWidth = false,
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_share),
                        contentDescription = null,
                        tint = buttonAccentColor,
                        modifier = Modifier.size(18.dp)
                    )
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Suppress("SpellCheckingInspection")
@Preview(showSystemUi = true, name = "Savings Mode")
@Composable
private fun PreviewSavingsMode() {
    AppThemeSurface {
        BottomSheetPreview {
            ReceiveQrScreen(
                primaryInvoice = null,
                walletState = WalletState(
                    onchainAddress = "bcrt1qfserxgtuesul4m9zva56wzk849yf9l8rk4qy0l",
                ),
                lightningState = LightningState(
                    nodeLifecycleState = NodeLifecycleState.Running,
                    channels = persistentListOf()
                ),
                onClickEditInvoice = {},
                modifier = Modifier.sheetHeight(),
                initialTab = ReceiveTab.SAVINGS,
                onClickReceiveLightning = {},
            )
        }
    }
}

@Suppress("SpellCheckingInspection")
@Preview(showSystemUi = true, name = "Auto Mode")
@Composable
private fun PreviewAutoMode() {
    // Mock channel for preview (AUTO tab requires non-empty channels list)
    val mockChannel = ChannelDetails(
        channelId = "0".repeat(64),
        counterpartyNodeId = "0".repeat(66),
        fundingTxo = null,
        shortChannelId = null,
        outboundScidAlias = null,
        inboundScidAlias = null,
        channelValueSats = 1000000uL,
        unspendablePunishmentReserve = null,
        userChannelId = "0".repeat(32),
        feerateSatPer1000Weight = 1000u,
        outboundCapacityMsat = 500000000uL,
        inboundCapacityMsat = 500000000uL,
        confirmationsRequired = null,
        confirmations = null,
        isOutbound = true,
        isChannelReady = true,
        isUsable = true,
        isAnnounced = false,
        cltvExpiryDelta = null,
        counterpartyUnspendablePunishmentReserve = 0uL,
        counterpartyOutboundHtlcMinimumMsat = null,
        counterpartyOutboundHtlcMaximumMsat = null,
        counterpartyForwardingInfoFeeBaseMsat = null,
        counterpartyForwardingInfoFeeProportionalMillionths = null,
        counterpartyForwardingInfoCltvExpiryDelta = null,
        nextOutboundHtlcLimitMsat = 0uL,
        nextOutboundHtlcMinimumMsat = 0uL,
        forceCloseSpendDelay = null,
        inboundHtlcMinimumMsat = 0uL,
        inboundHtlcMaximumMsat = null,
        claimableOnCloseSats = 0uL,
        config = org.lightningdevkit.ldknode.ChannelConfig(
            forwardingFeeProportionalMillionths = 0u,
            forwardingFeeBaseMsat = 0u,
            cltvExpiryDelta = 0u,
            maxDustHtlcExposure = org.lightningdevkit.ldknode.MaxDustHtlcExposure.FeeRateMultiplier(0uL),
            forceCloseAvoidanceMaxFeeSatoshis = 0uL,
            acceptUnderpayingHtlcs = false,
        ),
    )

    AppThemeSurface {
        BottomSheetPreview {
            ReceiveQrScreen(
                primaryInvoice = null,
                walletState = WalletState(
                    onchainAddress = "bcrt1qfserxgtuesul4m9zva56wzk849yf9l8rk4qy0l",
                    bolt11 = "lnbcrt500u1pn7umn7pp5x0s9lt9fwrff6rp70pz3guwnjgw97sjuv79vhx9n2ps8q6tcdehhxapqd9h8vmmfv" +
                        "djjqen0wgsyqvpsxqcrqvpsxqcrqvpsxqcrqvpsxqcrqvpsxqcrqvpsxqcrqvpsxqcrqvpsxq",
                    bip21 = "bitcoin:bcrt1qfserxgtuesul4m9zva56wzk849yf9l8rk4qy0l?lightning=" +
                        "lnbcrt500u1pn7umn7pp5x0s9lt9fwrff6rp70pz3guwnjgw97sjuv79vhx9n2ps8q6tcdehhxapqd9h8vmmfv" +
                        "djjqen0wgsyqvpsxqcrqvpsxqcrqvpsxqcrqvpsxqcrqvpsxqcrqvpsxqcrqvpsxqcrqvpsxq",
                ),
                lightningState = LightningState(
                    nodeLifecycleState = NodeLifecycleState.Running,
                    channels = persistentListOf(mockChannel),
                ),
                onClickEditInvoice = {},
                modifier = Modifier.sheetHeight(),
                initialTab = ReceiveTab.AUTO,
                onClickReceiveLightning = {},
            )
        }
    }
}

@Suppress("SpellCheckingInspection")
@Preview(showSystemUi = true, name = "Spending Mode")
@Composable
private fun PreviewSpendingMode() {
    val mockChannel = ChannelDetails(
        channelId = "0".repeat(64),
        counterpartyNodeId = "0".repeat(66),
        fundingTxo = null,
        shortChannelId = null,
        outboundScidAlias = null,
        inboundScidAlias = null,
        channelValueSats = 1000000uL,
        unspendablePunishmentReserve = null,
        userChannelId = "0".repeat(32),
        feerateSatPer1000Weight = 1000u,
        outboundCapacityMsat = 500000000uL,
        inboundCapacityMsat = 500000000uL,
        confirmationsRequired = null,
        confirmations = null,
        isOutbound = true,
        isChannelReady = true,
        isUsable = true,
        isAnnounced = false,
        cltvExpiryDelta = null,
        counterpartyUnspendablePunishmentReserve = 0uL,
        counterpartyOutboundHtlcMinimumMsat = null,
        counterpartyOutboundHtlcMaximumMsat = null,
        counterpartyForwardingInfoFeeBaseMsat = null,
        counterpartyForwardingInfoFeeProportionalMillionths = null,
        counterpartyForwardingInfoCltvExpiryDelta = null,
        nextOutboundHtlcLimitMsat = 0uL,
        nextOutboundHtlcMinimumMsat = 0uL,
        forceCloseSpendDelay = null,
        inboundHtlcMinimumMsat = 0uL,
        inboundHtlcMaximumMsat = null,
        claimableOnCloseSats = 0uL,
        config = org.lightningdevkit.ldknode.ChannelConfig(
            forwardingFeeProportionalMillionths = 0u,
            forwardingFeeBaseMsat = 0u,
            cltvExpiryDelta = 0u,
            maxDustHtlcExposure = org.lightningdevkit.ldknode.MaxDustHtlcExposure.FeeRateMultiplier(0uL),
            forceCloseAvoidanceMaxFeeSatoshis = 0uL,
            acceptUnderpayingHtlcs = false,
        ),
    )

    AppThemeSurface {
        BottomSheetPreview {
            ReceiveQrScreen(
                primaryInvoice = null,
                walletState = WalletState(
                    bolt11 = "lnbcrt500u1pn7umn7pp5x0s9lt9fwrff6rp70pz3guwnjgw97sjuv79vhx9n2ps8q6tcdehhxapqd9h8vmmfv" +
                        "djjqen0wgsyqvpsxqcrqvpsxqcrqvpsxqcrqvpsxqcrqvpsxqcrqvpsxqcrqvpsxqcrqvpsxq"
                ),
                lightningState = LightningState(
                    nodeLifecycleState = NodeLifecycleState.Running,
                    channels = persistentListOf(mockChannel),
                ),
                onClickEditInvoice = {},
                modifier = Modifier.sheetHeight(),
                initialTab = ReceiveTab.SPENDING,
                onClickReceiveLightning = {},
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewNodeNotReady() {
    AppThemeSurface {
        BottomSheetPreview {
            ReceiveQrScreen(
                primaryInvoice = null,
                walletState = WalletState(),
                lightningState = LightningState(
                    nodeLifecycleState = NodeLifecycleState.Starting,
                ),
                onClickReceiveLightning = {},
                onClickEditInvoice = {},
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}

@Preview(showSystemUi = true, device = NEXUS_5)
@Composable
private fun PreviewSmall() {
    AppThemeSurface {
        BottomSheetPreview {
            ReceiveQrScreen(
                primaryInvoice = null,
                walletState = WalletState(),
                lightningState = LightningState(
                    nodeLifecycleState = NodeLifecycleState.Running,
                ),
                onClickEditInvoice = {},
                modifier = Modifier.sheetHeight(),
                onClickReceiveLightning = {},
            )
        }
    }
}

@Suppress("SpellCheckingInspection")
@Preview(showSystemUi = true, name = "Auto Mode")
@Composable
private fun PreviewDetailsMode() {
    AppThemeSurface {
        Column(
            modifier = Modifier
                .gradientBackground()
                .fillMaxSize()
                .padding(16.dp)
        ) {
            ReceiveDetailsView(
                tab = ReceiveTab.AUTO,
                walletState = WalletState(
                    onchainAddress = "bcrt1qfserxgtuesul4m9zva56wzk849yf9l8rk4qy0l",
                    bolt11 = "lnbcrt500u1pn7umn7pp5x0s9lt9fwrff6rp70pz3guwnjgw97sjuv79vhx9n2ps8q6tcdehhxapqd9h8vmmfv" +
                        "djjqen0wgsyqvpsxqcrqvpsxqcrqvpsxqcrqvpsxqcrqvpsxqcrqvpsxqcrqvpsxqcrqvpsxq"
                ),
                primaryInvoice = null,
                isNodeRunning = true,
                onClickEditInvoice = {},
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Suppress("SpellCheckingInspection")
@Preview(showSystemUi = true, name = "Spending Details Loading")
@Composable
private fun PreviewDetailsModeSpendingLoading() {
    AppThemeSurface {
        Column(
            modifier = Modifier
                .gradientBackground()
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Cached bolt11 with a node that is not running: the stale invoice is withheld in favor of loading
            ReceiveDetailsView(
                tab = ReceiveTab.SPENDING,
                walletState = WalletState(
                    bolt11 = "lnbcrt500u1pn7umn7pp5x0s9lt9fwrff6rp70pz3guwnjgw97sjuv79vhx9n2ps8q6tcdehhxapqd9h8vmmfv" +
                        "djjqen0wgsyqvpsxqcrqvpsxqcrqvpsxqcrqvpsxqcrqvpsxqcrqvpsxqcrqvpsxqcrqvpsxq"
                ),
                primaryInvoice = null,
                isNodeRunning = false,
                onClickEditInvoice = {},
                modifier = Modifier.weight(1f)
            )
        }
    }
}

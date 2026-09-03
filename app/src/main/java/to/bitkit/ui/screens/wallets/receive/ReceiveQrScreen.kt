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
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.ChannelDetails
import to.bitkit.R
import to.bitkit.ext.calculateRemoteBalance
import to.bitkit.ext.setClipboardText
import to.bitkit.models.NodeLifecycleState
import to.bitkit.models.ReceiveLiquidityDecision
import to.bitkit.repositories.LightningState
import to.bitkit.repositories.WalletState
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
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.screens.wallets.activity.components.CustomTabRowWithSpacing
import to.bitkit.ui.shared.effects.SetMaxBrightness
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.shared.util.shareQrCode
import to.bitkit.ui.shared.util.shareText
import to.bitkit.ui.theme.AppShapes
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Suppress("CyclomaticComplexMethod")
@OptIn(FlowPreview::class)
@Composable
fun ReceiveQrScreen(
    cjitInvoice: String?,
    walletState: WalletState,
    lightningState: LightningState,
    onClickEditInvoice: (ReceiveTab) -> Unit,
    onClickReceiveCjit: () -> Unit,
    onClickHardwareEditInvoice: () -> Unit = onClickEditInvoice,
    modifier: Modifier = Modifier,
    initialTab: ReceiveTab? = null,
    hardwareWalletId: String? = null,
    hardwareReceiveState: HwReceiveUiState = HwReceiveUiState(),
    onLoadHardwareAddress: (String) -> Unit = {},
    onRetryHardwareAddress: () -> Unit = {},
    onVerifyHardwareAddress: () -> Unit = {},
) {
    SetMaxBrightness()

    val haptic = LocalHapticFeedback.current
    val inboundLiquiditySats = lightningState.channels.calculateRemoteBalance()
    val hasUsableChannels = lightningState.channels.any { it.isChannelReady }
    val canCreateLightningInvoice = remember(
        hasUsableChannels,
        lightningState.channels,
        walletState.bip21AmountSats,
    ) {
        ReceiveLiquidityDecision.canCreateLightningInvoice(
            hasReadyChannels = hasUsableChannels,
            inboundCapacitySats = inboundLiquiditySats,
            invoiceAmountSats = walletState.bip21AmountSats,
        )
    }

    var showDetails by remember { mutableStateOf(false) }

    val visibleTabs = remember(canCreateLightningInvoice, cjitInvoice, hardwareWalletId) {
        buildList {
            if (hardwareWalletId != null) {
                add(ReceiveTab.TREZOR)
            }
            add(ReceiveTab.SAVINGS)
            if (canCreateLightningInvoice && cjitInvoice.isNullOrEmpty()) {
                add(ReceiveTab.AUTO)
            }
            add(ReceiveTab.SPENDING)
        }.toImmutableList()
    }
    val defaultTab = remember(visibleTabs, initialTab) {
        initialTab?.takeIf { it in visibleTabs } ?: visibleTabs.defaultReceiveTab()
    }

    val invoicesByTab = remember(
        visibleTabs,
        walletState.bip21,
        walletState.bolt11,
        walletState.onchainAddress,
        cjitInvoice,
        lightningState.nodeLifecycleState,
        hardwareReceiveState.address,
        walletState.bip21AmountSats,
        walletState.bip21Description,
    ) {
        visibleTabs.associateWith { tab ->
            getInvoiceForTab(
                tab = tab,
                bip21 = walletState.bip21,
                bolt11 = walletState.bolt11,
                cjitInvoice = cjitInvoice,
                isNodeRunning = lightningState.nodeLifecycleState.isRunning(),
                canCreateLightningInvoice = canCreateLightningInvoice,
                onchainAddress = walletState.onchainAddress,
                hardwareAddress = hardwareReceiveState.address?.address.orEmpty(),
                hardwareAmountSats = walletState.bip21AmountSats,
                hardwareMessage = walletState.bip21Description,
            )
        }
    }

    // LazyRow state with snap behavior
    val scope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState(
        initialFirstVisibleItemIndex = visibleTabs.indexOf(initialTab ?: ReceiveTab.SAVINGS).coerceAtLeast(0),
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

    LaunchedEffect(visibleTabs, initialTab) {
        if (!hasAppliedInitialTab) {
            hasAppliedInitialTab = true
            initialTab?.takeIf { it in visibleTabs }?.let { requestedTab ->
                selectedTab = requestedTab
                lazyListState.scrollToItem(visibleTabs.indexOf(requestedTab))
            }
        }
        if (selectedTab !in visibleTabs) {
            selectedTab = visibleTabs.first()
            lazyListState.scrollToItem(0)
        }
    }

    LaunchedEffect(canCreateLightningInvoice, cjitInvoice) {
        if (!canCreateLightningInvoice && cjitInvoice.isNullOrEmpty()) {
            selectedTab = ReceiveTab.SAVINGS
            lazyListState.scrollToItem(0)
        }
    }

    LaunchedEffect(lazyListState, visibleTabs.size) {
        snapshotFlow { lazyListState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index ->
                if (index < visibleTabs.size && index > -1) {
                    val tab = visibleTabs[index]
                    selectedTab = tab
                }
            }
    }

    // Auto-switch to AUTO tab when it becomes available for the first time
    LaunchedEffect(canCreateLightningInvoice, cjitInvoice) {
        if (canCreateLightningInvoice && cjitInvoice.isNullOrEmpty() && visibleTabs.contains(ReceiveTab.AUTO)) {
            val autoIndex = visibleTabs.indexOf(ReceiveTab.AUTO)
            if (autoIndex != -1) {
                lazyListState.animateScrollToItem(autoIndex)
                selectedTab = ReceiveTab.AUTO
            }
        }
    }

    // Auto-switch to Spending tab when CJIT is not null
    LaunchedEffect(cjitInvoice) {
        if (cjitInvoice != null) {
            val spendingIndex = visibleTabs.indexOf(ReceiveTab.SPENDING)
            if (spendingIndex != -1) {
                lazyListState.animateScrollToItem(spendingIndex)
                selectedTab = ReceiveTab.SPENDING
            }
        }
    }

    LaunchedEffect(selectedTab, hardwareWalletId) {
        showDetails = false
        if (selectedTab == ReceiveTab.TREZOR && hardwareWalletId != null) {
            onLoadHardwareAddress(hardwareWalletId)
        }
    }

    val showingCjitOnboarding = remember(lightningState, cjitInvoice, canCreateLightningInvoice) {
        !canCreateLightningInvoice &&
            lightningState.nodeLifecycleState.isRunning() &&
            cjitInvoice.isNullOrEmpty()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .keepScreenOn()
    ) {
        SheetTopBar(stringResource(R.string.wallet__receive_bitcoin))
        Column {
            VerticalSpacer(16.dp)

            // Tab row
            CustomTabRowWithSpacing(
                tabs = visibleTabs,
                currentTabIndex = visibleTabs.indexOf(selectedTab),
                selectedColor = Colors.White,
                onTabChange = { tab ->
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val newIndex = visibleTabs.indexOf(tab)
                    selectedTab = tab
                    showDetails = false
                    scope.launch {
                        lazyListState.animateScrollToItem(newIndex)
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
                    items = visibleTabs,
                    key = { _, tab -> tab.name }
                ) { _, tab ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillParentMaxWidth()
                            .fillParentMaxHeight()
                    ) {
                        when {
                            showingCjitOnboarding && tab == ReceiveTab.SPENDING -> {
                                CjitOnBoardingView(
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            tab == ReceiveTab.TREZOR && hardwareReceiveState.address == null -> {
                                HardwareAddressLoadingView(
                                    isLoading = hardwareReceiveState.isLoadingAddress,
                                    hasFailed = hardwareReceiveState.addressLoadFailed,
                                    onRetry = onRetryHardwareAddress,
                                    modifier = Modifier.weight(1f),
                                )
                            }

                            showDetails -> {
                                ReceiveDetailsView(
                                    tab = tab,
                                    walletState = walletState,
                                    cjitInvoice = cjitInvoice,
                                    isNodeRunning = lightningState.nodeLifecycleState.isRunning(),
                                    onClickEditInvoice = { onClickEditInvoice(tab) },
                                    onClickHardwareEditInvoice = onClickHardwareEditInvoice,
                                    hardwareAddress = hardwareReceiveState.address?.address,
                                    hardwareInvoice = invoicesByTab[ReceiveTab.TREZOR].orEmpty(),
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

                                    ReceiveTab.TREZOR -> invoice.takeIf { '?' in it }
                                        ?: hardwareReceiveState.address?.address.orEmpty()

                                    else -> invoice
                                }

                                ReceiveQrView(
                                    uri = invoice,
                                    copyText = copyText,
                                    qrLogoPainter = painterResource(getQrLogoResource(tab)),
                                    onClickEditInvoice = if (tab == ReceiveTab.TREZOR) {
                                        onClickHardwareEditInvoice
                                    } else if (cjitInvoice.isNullOrEmpty()) {
                                        { onClickEditInvoice(tab) }
                                    } else {
                                        onClickReceiveCjit
                                    },
                                    tab = tab,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }

            VerticalSpacer(24.dp)

            val showCjitButton = showingCjitOnboarding && selectedTab == ReceiveTab.SPENDING
            val buttonVariant = when {
                showCjitButton -> BottomButtonVariant.CJIT
                showDetails -> BottomButtonVariant.SHOW_QR
                else -> BottomButtonVariant.SHOW_DETAILS
            }
            Crossfade(
                targetState = buttonVariant,
                label = "ReceiveBottomButtonCrossfade",
            ) { variant ->
                when (variant) {
                    BottomButtonVariant.CJIT -> PrimaryButton(
                        text = stringResource(R.string.wallet__receive__cjit),
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_lightning_alt),
                                tint = Colors.Purple,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            onClickReceiveCjit()
                            showDetails = false
                        },
                        fullWidth = true,
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .testTag("ShowDetails")
                    )

                    BottomButtonVariant.SHOW_QR -> Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    ) {
                        if (selectedTab == ReceiveTab.TREZOR) {
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
                        enabled = selectedTab != ReceiveTab.TREZOR || hardwareReceiveState.address != null,
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

private fun List<ReceiveTab>.defaultReceiveTab(): ReceiveTab {
    return if (contains(ReceiveTab.AUTO)) ReceiveTab.AUTO else ReceiveTab.SAVINGS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReceiveQrView(
    uri: String,
    copyText: String,
    qrLogoPainter: Painter,
    onClickEditInvoice: () -> Unit,
    tab: ReceiveTab,
    modifier: Modifier = Modifier,
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
            PrimaryButton(
                text = stringResource(R.string.common__edit),
                size = ButtonSize.Small,
                onClick = onClickEditInvoice,
                fullWidth = false,
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_pencil_simple),
                        contentDescription = null,
                        tint = tab.accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("SpecifyInvoiceButton")
            )
            Box(modifier = Modifier.weight(1f)) {
                Tooltip(
                    text = stringResource(R.string.wallet__receive_copied),
                    tooltipState = qrButtonTooltipState,
                ) {
                    PrimaryButton(
                        text = stringResource(R.string.common__copy),
                        size = ButtonSize.Small,
                        onClick = {
                            context.setClipboardText(copyText)
                            coroutineScope.launch { qrButtonTooltipState.show() }
                        },
                        fullWidth = true,
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_copy),
                                contentDescription = null,
                                tint = tab.accentColor,
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
                onClick = {
                    qrBitmap?.let { bitmap ->
                        shareQrCode(context, bitmap, copyText)
                    } ?: shareText(context, copyText)
                },
                fullWidth = false,
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_share),
                        contentDescription = null,
                        tint = tab.accentColor,
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
fun CjitOnBoardingView(modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(AppShapes.small)
            .background(color = Colors.Black)
            .padding(32.dp)
    ) {
        Display(stringResource(R.string.wallet__receive_onboarding_title).withAccent(accentColor = Colors.Purple))
        VerticalSpacer(8.dp)
        BodyM(
            stringResource(R.string.wallet__receive_onboarding_description),
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
    cjitInvoice: String?,
    isNodeRunning: Boolean,
    onClickEditInvoice: () -> Unit,
    onClickHardwareEditInvoice: () -> Unit = onClickEditInvoice,
    hardwareAddress: String? = null,
    hardwareInvoice: String = "",
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Colors.Black),
        shape = AppShapes.small,
        modifier = modifier
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(32.dp),
            modifier = Modifier.padding(32.dp),
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
                    if (cjitInvoice != null || walletState.bolt11.isNotEmpty()) {
                        CopyAddressCard(
                            title = stringResource(R.string.wallet__receive_lightning_invoice),
                            address = cjitInvoice ?: walletState.bolt11,
                            type = CopyAddressType.LIGHTNING,
                            onClickEditInvoice = onClickEditInvoice,
                            testTag = "ReceiveLightningAddress",
                        )
                    }
                }

                ReceiveTab.SPENDING -> {
                    val lightningInvoice = cjitInvoice ?: walletState.bolt11.takeIf { isNodeRunning }
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

                ReceiveTab.TREZOR -> {
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

private enum class BottomButtonVariant { CJIT, SHOW_QR, SHOW_DETAILS }

enum class CopyAddressType { ONCHAIN, LIGHTNING }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CopyAddressCard(
    title: String,
    address: String,
    type: CopyAddressType,
    onClickEditInvoice: () -> Unit,
    body: String? = null,
    testTag: String? = null,
    accentColor: Color? = null,
) {
    val context = LocalContext.current

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
            Tooltip(
                text = stringResource(R.string.wallet__receive_copied),
                tooltipState = tooltipState,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    PrimaryButton(
                        text = stringResource(R.string.common__copy),
                        size = ButtonSize.Small,
                        onClick = {
                            context.setClipboardText(address)
                            coroutineScope.launch { tooltipState.show() }
                        },
                        fullWidth = false,
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
            PrimaryButton(
                text = stringResource(R.string.common__share),
                size = ButtonSize.Small,
                onClick = { shareText(context, address) },
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
                cjitInvoice = null,
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
                onClickReceiveCjit = {},
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
                cjitInvoice = null,
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
                onClickReceiveCjit = {},
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
                cjitInvoice = null,
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
                onClickReceiveCjit = {},
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
                cjitInvoice = null,
                walletState = WalletState(),
                lightningState = LightningState(
                    nodeLifecycleState = NodeLifecycleState.Starting,
                ),
                onClickReceiveCjit = {},
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
                cjitInvoice = null,
                walletState = WalletState(),
                lightningState = LightningState(
                    nodeLifecycleState = NodeLifecycleState.Running,
                ),
                onClickEditInvoice = {},
                modifier = Modifier.sheetHeight(),
                onClickReceiveCjit = {},
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
                cjitInvoice = null,
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
                cjitInvoice = null,
                isNodeRunning = false,
                onClickEditInvoice = {},
                modifier = Modifier.weight(1f)
            )
        }
    }
}

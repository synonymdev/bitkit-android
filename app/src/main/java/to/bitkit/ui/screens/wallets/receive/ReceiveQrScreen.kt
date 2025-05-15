package to.bitkit.ui.screens.wallets.receive

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Devices.PIXEL_TABLET
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ext.truncate
import to.bitkit.models.NodeLifecycleState
import to.bitkit.models.NodeLifecycleState.Running
import to.bitkit.repositories.LightningState
import to.bitkit.ui.appViewModel
import to.bitkit.ui.blocktankViewModel
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.Headline
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.QrCodeImage
import to.bitkit.ui.components.Tooltip
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.screens.wallets.send.AddTagScreen
import to.bitkit.ui.shared.PagerWithIndicator
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.shared.util.shareText
import to.bitkit.ui.theme.AppShapes
import to.bitkit.ui.theme.AppSwitchDefaults
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.ui.walletViewModel
import to.bitkit.viewmodels.MainUiState
import to.bitkit.viewmodels.WalletViewModelEffects

private object ReceiveRoutes {
    const val QR = "qr"
    const val AMOUNT = "amount"
    const val CONFIRM = "confirm"
    const val CONFIRM_INCREASE_INBOUND = "confirm_increase_inbound"
    const val LIQUIDITY = "liquidity"
    const val LIQUIDITY_ADDITIONAL = "liquidity_additional"
    const val EDIT_INVOICE = "edit_invoice"
    const val ADD_TAG = "add_tag"
    const val LOCATION_BLOCK = "location_block"
}

@Composable
fun ReceiveQrSheet(
    navigateToExternalConnection: () -> Unit,
    walletState: MainUiState,
    modifier: Modifier = Modifier,
) {
    val app = appViewModel ?: return
    val wallet = walletViewModel ?: return
    val blocktank = blocktankViewModel ?: return

    val navController = rememberNavController()

    val cjitInvoice = remember { mutableStateOf<String?>(null) }
    val showCreateCjit = remember { mutableStateOf(false) }
    val cjitEntryDetails = remember { mutableStateOf<CjitEntryDetails?>(null) }
    val lightningState : LightningState by wallet.lightningState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        try {
            coroutineScope {
                launch { wallet.refreshBip21() }
                launch { blocktank.refreshInfo() }
            }
        } catch (e: Exception) {
            app.toast(e)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(.875f)
            .imePadding()
    ) {
        NavHost(
            navController = navController,
            startDestination = ReceiveRoutes.QR,
        ) {
            composable(ReceiveRoutes.QR) {
                LaunchedEffect(cjitInvoice.value) {
                    showCreateCjit.value = !cjitInvoice.value.isNullOrBlank()
                }

                LaunchedEffect(Unit) {
                    wallet.walletEffect.collect { effect ->
                        when(effect) {
                            WalletViewModelEffects.NavigateGeoBlockScreen -> {
                                navController.navigate(ReceiveRoutes.LOCATION_BLOCK)
                            }
                        }
                    }
                }

                ReceiveQrScreen(
                    cjitInvoice = cjitInvoice,
                    cjitActive = showCreateCjit,
                    walletState = walletState,
                    onCjitToggle = { active ->
                        when {
                            active && lightningState.shouldBlockLightning -> navController.navigate(ReceiveRoutes.LOCATION_BLOCK)

                            !active -> {
                                showCreateCjit.value = false
                                cjitInvoice.value = null
                            }
                            active && cjitInvoice.value == null -> {
                                showCreateCjit.value = true
                                navController.navigate(ReceiveRoutes.AMOUNT)
                            }
                        }
                    },
                    onClickEditInvoice = { navController.navigate(ReceiveRoutes.EDIT_INVOICE) },
                    onClickReceiveOnSpending = { wallet.toggleReceiveOnSpending() }
                )
            }
            composable(ReceiveRoutes.AMOUNT) {
                ReceiveAmountScreen(
                    onCjitCreated = { entry ->
                        cjitEntryDetails.value = entry
                        navController.navigate(ReceiveRoutes.CONFIRM)
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(ReceiveRoutes.LOCATION_BLOCK) {
                LocationBlockScreen(
                    onBackPressed = { navController.popBackStack() },
                    navigateAdvancedSetup = navigateToExternalConnection
                )
            }
            composable(ReceiveRoutes.CONFIRM) {
                cjitEntryDetails.value?.let { entryDetails ->
                    ReceiveConfirmScreen(
                        entry = entryDetails,
                        onLearnMore = { navController.navigate(ReceiveRoutes.LIQUIDITY) },
                        onContinue = { invoice ->
                            cjitInvoice.value = invoice
                            navController.navigate(ReceiveRoutes.QR) { popUpTo(ReceiveRoutes.QR) { inclusive = true } }
                        },
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            composable(ReceiveRoutes.CONFIRM_INCREASE_INBOUND) {
                cjitEntryDetails.value?.let { entryDetails ->
                    ReceiveConfirmScreen(
                        entry = entryDetails,
                        onLearnMore = { navController.navigate(ReceiveRoutes.LIQUIDITY_ADDITIONAL) },
                        onContinue = { invoice ->
                            cjitInvoice.value = invoice
                            navController.navigate(ReceiveRoutes.QR) { popUpTo(ReceiveRoutes.QR) { inclusive = true } }
                        },
                        isAdditional = true,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            composable(ReceiveRoutes.LIQUIDITY) {
                cjitEntryDetails.value?.let { entryDetails ->
                    ReceiveLiquidityScreen(
                        entry = entryDetails,
                        onContinue = { navController.popBackStack() },
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            composable(ReceiveRoutes.LIQUIDITY_ADDITIONAL) {
                cjitEntryDetails.value?.let { entryDetails ->
                    ReceiveLiquidityScreen(
                        entry = entryDetails,
                        onContinue = { navController.popBackStack() },
                        isAdditional = true,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            composable(ReceiveRoutes.EDIT_INVOICE) {
                val walletUiState by wallet.walletState.collectAsStateWithLifecycle()
                EditInvoiceScreen(
                    walletUiState = walletUiState,
                    onBack = { navController.popBackStack() },
                    updateInvoice = { sats ->
                        wallet.updateBip21Invoice(amountSats = sats)
                    },
                    onClickAddTag = {
                        navController.navigate(ReceiveRoutes.ADD_TAG)
                    },
                    onClickTag = { tagToRemove ->
                        wallet.removeTag(tagToRemove)
                    },
                    onDescriptionUpdate = { newText ->
                        wallet.updateBip21Description(newText = newText)
                    },
                    onInputUpdated = { newText ->
                        wallet.updateBalanceInput(newText)
                    },
                    navigateReceiveConfirm = { entry ->
                        cjitEntryDetails.value = entry
                        navController.navigate(ReceiveRoutes.CONFIRM_INCREASE_INBOUND)
                    }
                )
            }
            composable(ReceiveRoutes.ADD_TAG) {
                AddTagScreen(
                    onBack = {
                        navController.popBackStack()
                    },
                    onTagSelected = { tag ->
                        wallet.addTagToSelected(tag)
                        navController.popBackStack()
                    }
                )

            }
        }
    }
}

@Composable
private fun ReceiveQrScreen(
    cjitInvoice: MutableState<String?>,
    cjitActive: MutableState<Boolean>,
    walletState: MainUiState,
    onCjitToggle: (Boolean) -> Unit,
    onClickEditInvoice: () -> Unit,
    onClickReceiveOnSpending: () -> Unit,
) {
    val context = LocalContext.current
    val window = remember(context) { (context as Activity).window }

    // Keep screen on and set brightness to max while this composable is active
    DisposableEffect(Unit) {
        val originalBrightness = window.attributes.screenBrightness
        val originalFlags = window.attributes.flags

        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
            flags = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        }

        onDispose {
            window.attributes = window.attributes.apply {
                screenBrightness = originalBrightness
                flags = originalFlags
            }
        }
    }

    val qrLogoImageRes by remember(walletState, cjitInvoice.value) {
        val resId = when {
            cjitInvoice.value?.isNotEmpty() == true -> R.drawable.ic_ln_circle
            walletState.bolt11.isNotEmpty() && walletState.onchainAddress.isNotEmpty() -> R.drawable.ic_unified_circle
            else -> R.drawable.ic_btc_circle
        }
        mutableIntStateOf(resId)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .gradientBackground()
    ) {
        SheetTopBar(stringResource(R.string.wallet__receive_bitcoin))
        Spacer(Modifier.height(24.dp))

        val onchainAddress = walletState.onchainAddress
        val uri = cjitInvoice.value ?: walletState.bip21

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .weight(1f)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
            ) {
                val pagerState = rememberPagerState(initialPage = 0) { 2 }
                PagerWithIndicator(pagerState) {
                    when (it) {
                        0 -> ReceiveQrSlide(
                            uri = uri,
                            qrLogoPainter = painterResource(qrLogoImageRes),
                            modifier = Modifier.fillMaxWidth(),
                            onClickEditInvoice = onClickEditInvoice
                        )

                        1 -> CopyValuesSlide(
                            onchainAddress = onchainAddress,
                            bolt11 = walletState.bolt11,
                            cjitInvoice = cjitInvoice.value,
                            receiveOnSpendingBalance = walletState.receiveOnSpendingBalance
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            AnimatedVisibility(walletState.nodeLifecycleState.isRunning() && walletState.channels.isEmpty()) {
                ReceiveLightningFunds(
                    cjitInvoice = cjitInvoice,
                    cjitActive = cjitActive,
                    onCjitToggle = onCjitToggle,
                )
            }
            AnimatedVisibility(walletState.nodeLifecycleState.isRunning() && walletState.channels.isNotEmpty()) {
                Column {
                    AnimatedVisibility(!walletState.receiveOnSpendingBalance) {
                        Headline(
                            text = stringResource(R.string.wallet__receive_text_lnfunds).withAccent(accentColor = Colors.Purple)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BodyM(text = stringResource(R.string.wallet__receive_spending))
                        Spacer(modifier = Modifier.weight(1f))
                        AnimatedVisibility(!walletState.receiveOnSpendingBalance) {
                            Icon(
                                painter = painterResource(R.drawable.empty_state_arrow_horizontal),
                                contentDescription = null,
                                tint = Colors.White64,
                                modifier = Modifier
                                    .rotate(17.33f)
                                    .padding(start = 7.65.dp, end = 13.19.dp)
                            )
                        }
                        Switch(
                            checked = walletState.receiveOnSpendingBalance,
                            onCheckedChange = { onClickReceiveOnSpending() },
                            colors = AppSwitchDefaults.colorsPurple,
                        )
                    }
                }
            }
            AnimatedVisibility(walletState.nodeLifecycleState.isStarting()) {
                BodyM(text = stringResource(R.string.wallet__receive_ldk_init))
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ReceiveLightningFunds(
    cjitInvoice: MutableState<String?>,
    cjitActive: MutableState<Boolean>,
    onCjitToggle: (Boolean) -> Unit,
) {
    Column {
        AnimatedVisibility(!cjitActive.value && cjitInvoice.value == null) {
            Headline(
                text = stringResource(R.string.wallet__receive_text_lnfunds).withAccent(accentColor = Colors.Purple)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            BodyM(text = stringResource(R.string.wallet__receive_spending))
            Spacer(modifier = Modifier.weight(1f))
            AnimatedVisibility(!cjitActive.value && cjitInvoice.value == null) {
                Icon(
                    painter = painterResource(R.drawable.empty_state_arrow_horizontal),
                    contentDescription = null,
                    tint = Colors.White64,
                    modifier = Modifier
                        .rotate(17.33f)
                        .padding(start = 7.65.dp, end = 13.19.dp)
                )
            }
            Switch(
                checked = cjitActive.value,
                onCheckedChange = onCjitToggle,
                colors = AppSwitchDefaults.colorsPurple,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReceiveQrSlide(
    uri: String,
    qrLogoPainter: Painter,
    modifier: Modifier,
    onClickEditInvoice: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val qrButtonTooltipState = rememberTooltipState()
    val coroutineScope = rememberCoroutineScope()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        QrCodeImage(
            content = uri,
            logoPainter = qrLogoPainter,
            modifier = Modifier.weight(1f, fill = false)
        )

        Spacer(modifier = Modifier.height(16.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            PrimaryButton(
                text = stringResource(R.string.common__edit),
                size = ButtonSize.Small,
                onClick = onClickEditInvoice,
                fullWidth = false,
                color = Colors.White10,
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_pencil_simple),
                        contentDescription = null,
                        tint = Colors.Brand,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
            Tooltip(
                text = stringResource(R.string.wallet__receive_copied),
                tooltipState = qrButtonTooltipState
            ) {
                PrimaryButton(
                    text = stringResource(R.string.common__copy),
                    size = ButtonSize.Small,
                    onClick = {
                        clipboard.setText(AnnotatedString(uri))
                        coroutineScope.launch { qrButtonTooltipState.show() }
                    },
                    fullWidth = false,
                    color = Colors.White10,
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_copy),
                            contentDescription = null,
                            tint = Colors.Brand,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }
            PrimaryButton(
                text = stringResource(R.string.common__share),
                size = ButtonSize.Small,
                onClick = { shareText(context, uri) },
                fullWidth = false,
                color = Colors.White10,
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_share),
                        contentDescription = null,
                        tint = Colors.Brand,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun CopyValuesSlide(
    onchainAddress: String,
    bolt11: String,
    cjitInvoice: String?,
    receiveOnSpendingBalance: Boolean
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Colors.White10),
        shape = AppShapes.small,
    ) {
        Column {
            if (onchainAddress.isNotEmpty() && cjitInvoice == null) {
                CopyAddressCard(
                    title = stringResource(R.string.wallet__receive_bitcoin_invoice),
                    address = onchainAddress,
                    type = CopyAddressType.ONCHAIN,
                )
            }
            if (bolt11.isNotEmpty() && receiveOnSpendingBalance) {
                CopyAddressCard(
                    title = stringResource(R.string.wallet__receive_lightning_invoice),
                    address = bolt11,
                    type = CopyAddressType.LIGHTNING,
                )
            } else if (cjitInvoice != null) {
                CopyAddressCard(
                    title = stringResource(R.string.wallet__receive_lightning_invoice),
                    address = cjitInvoice,
                    type = CopyAddressType.LIGHTNING,
                )
            }
        }
    }
}

enum class CopyAddressType { ONCHAIN, LIGHTNING }

@Composable
private fun CopyAddressCard(
    title: String,
    address: String,
    type: CopyAddressType,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
    ) {
        Row {
            Caption13Up(text = title, color = Colors.White64)

            Spacer(modifier = Modifier.width(3.dp))

            val iconRes = if (type == CopyAddressType.ONCHAIN) R.drawable.ic_bitcoin else R.drawable.ic_lightning_alt
            Icon(painter = painterResource(iconRes), contentDescription = null, tint = Colors.White64)
        }
        Spacer(modifier = Modifier.height(16.dp))
        BodyS(text = address.truncate(32).uppercase())
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PrimaryButton(
                text = stringResource(R.string.common__copy),
                size = ButtonSize.Small,
                onClick = { clipboard.setText(AnnotatedString(address)) },
                fullWidth = false,
                color = Colors.White10,
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_copy),
                        contentDescription = null,
                        tint = if (type == CopyAddressType.ONCHAIN) Colors.Brand else Colors.Purple,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
            PrimaryButton(
                text = stringResource(R.string.common__share),
                size = ButtonSize.Small,
                onClick = { shareText(context, address) },
                fullWidth = false,
                color = Colors.White10,
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_share),
                        contentDescription = null,
                        tint = if (type == CopyAddressType.ONCHAIN) Colors.Brand else Colors.Purple,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ReceiveQrScreenPreview() {
    AppThemeSurface {
        ReceiveQrScreen(
            cjitInvoice = remember { mutableStateOf(null) },
            cjitActive = remember { mutableStateOf(false) },
            walletState = MainUiState(
                nodeLifecycleState = Running,
            ),
            onCjitToggle = { },
            onClickEditInvoice = {},
            onClickReceiveOnSpending = {},
        )
    }
}

@Preview(showBackground = true, heightDp = 600)
@Composable
private fun ReceiveQrScreenPreviewSmallScreen() {
    AppThemeSurface {
        ReceiveQrScreen(
            cjitInvoice = remember { mutableStateOf(null) },
            cjitActive = remember { mutableStateOf(false) },
            walletState = MainUiState(
                nodeLifecycleState = Running,
            ),
            onCjitToggle = { },
            onClickEditInvoice = {},
            onClickReceiveOnSpending = {},
        )
    }
}

@Preview(showBackground = true, device = PIXEL_TABLET)
@Composable
private fun ReceiveQrScreenPreviewTablet() {
    AppThemeSurface {
        ReceiveQrScreen(
            cjitInvoice = remember { mutableStateOf(null) },
            cjitActive = remember { mutableStateOf(false) },
            walletState = MainUiState(
                nodeLifecycleState = NodeLifecycleState.Starting,
            ),
            onCjitToggle = { },
            onClickEditInvoice = {},
            onClickReceiveOnSpending = {},
        )
    }
}

@Suppress("SpellCheckingInspection")
@Preview(showBackground = true)
@Composable
private fun CopyValuesSlidePreview() {
    AppThemeSurface {
        Column(
            modifier = Modifier
                .gradientBackground()
                .padding(16.dp),
        ) {
            CopyValuesSlide(
                onchainAddress = "bcrt1qfserxgtuesul4m9zva56wzk849yf9l8rk4qy0l",
                bolt11 = "lnbcrt500u1pn7umn7pp5x0s9lt9fwrff6rp70pz3guwnjgw97sjuv79...",
                cjitInvoice = null,
                true
            )
        }
    }
}

package to.bitkit.ui.screens.wallets.usdt

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import breez_sdk_spark.PaymentStatus
import com.synonym.bitkitcore.usdtFormatAmount
import kotlinx.collections.immutable.persistentListOf
import to.bitkit.R
import to.bitkit.ext.uiDateStyleFor
import to.bitkit.models.formatToModernDisplay
import to.bitkit.repositories.SparkUsdQuote
import to.bitkit.repositories.SparkUsdState
import to.bitkit.repositories.SparkUsdTransfer
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.BottomSheet
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.CaptionB
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.GradientCircularProgressIndicator
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.NumberPad
import to.bitkit.ui.components.NumberPadActionButton
import to.bitkit.ui.components.NumberPadType
import to.bitkit.ui.components.PaymentAddressInput
import to.bitkit.ui.components.PaymentReviewIllustration
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.SwipeToConfirm
import to.bitkit.ui.components.TabBar
import to.bitkit.ui.components.UsdtAmountHeader
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.WalletBalanceContent
import to.bitkit.ui.components.usdtOverviewAmount
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.screens.wallets.activity.components.ActivityRowSurface
import to.bitkit.ui.screens.wallets.activity.components.AmountViewContent
import to.bitkit.ui.screens.wallets.activity.components.CircularIcon
import to.bitkit.ui.screens.wallets.activity.components.activityGroupTitleResource
import to.bitkit.ui.screens.wallets.receive.ReceiveQrView
import to.bitkit.ui.settingsViewModel
import to.bitkit.ui.shared.UiConstants
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.blockPointerInputPassthrough
import to.bitkit.ui.theme.AppTextStyles
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.NumberPadInputHandler
import to.bitkit.ui.utils.uiDateText

/** Home tile for the dollar balance on Spark, next to the Arbitrum USDT one. */
@Composable
fun RowScope.SparkUsdWalletBalance(onClick: () -> Unit, viewModel: SparkUsdViewModel = hiltViewModel()) {
    val wallet by viewModel.wallet.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    WalletBalanceContent(
        title = wallet?.ticker ?: "USDT",
        titleStyle = UsdtDetailStyles.Caption,
        icon = {
            Image(
                painterResource(R.drawable.tether_circle),
                contentDescription = null,
                modifier = Modifier.padding(end = 4.dp).size(24.dp)
            )
        },
        titleTrailing = {
            HorizontalSpacer(4.dp)
            CaptionB(stringResource(R.string.lightning__spark), color = Colors.White64)
        },
        modifier = Modifier.clickableAlpha(onClick = onClick).padding(vertical = 4.dp).testTag("SparkUsdWallet")
    ) {
        BodyMSB(
            when {
                settings.hideBalance -> UiConstants.HIDE_BALANCE_SHORT
                else -> wallet?.let { "$ " + usdtOverviewAmount(it.balance) } ?: "—"
            },
            style = UsdtDetailStyles.HistoryTitle
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SparkUsdWalletScreen(
    onBack: () -> Unit,
    initialRecipient: String = "",
    viewModel: SparkUsdViewModel = hiltViewModel(),
) {
    val wallet by viewModel.wallet.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val bridgeAddress by viewModel.bridgeAddress.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel, initialRecipient) {
        viewModel.refresh()
        if (initialRecipient.isNotEmpty()) {
            viewModel.startSend(initialRecipient)
            viewModel.continueWithRecipient()
        }
    }
    BackHandler(enabled = state.page == SparkUsdPage.WALLET, onBack = onBack)
    SparkUsdWalletContent(
        wallet = wallet,
        hideBalance = settings.hideBalance,
        onReceive = viewModel::showReceive,
        onSend = { viewModel.startSend() },
        onBack = onBack,
        onConvert = viewModel::startConvert,
        onBridge = viewModel::startBridge.takeIf { bridgeAddress != null },
    )
    if (state.page == SparkUsdPage.WALLET) return
    val blocked by rememberUpdatedState(state.busy)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !blocked },
    )
    BottomSheet(
        onDismissRequest = viewModel::dismiss,
        sheetState = sheetState,
        containerColor = Colors.Gray7,
        dragHandleTopPadding = 8.dp,
    ) {
        BackHandler(onBack = viewModel::back)
        Column(
            modifier = Modifier.sheetHeight(isModal = true).background(Colors.Gray7)
                .navigationBarsPadding().imePadding()
                .padding(bottom = 16.dp)
        ) {
            if (state.authenticationRequired) {
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    UsdtAuthentication(
                        useBiometrics = settings.isBiometricEnabled,
                        onCancel = viewModel::back,
                        onVerified = viewModel::authenticationVerified,
                    )
                }
            } else {
                SparkUsdSheetContent(
                    wallet = wallet,
                    state = state,
                    hideBalance = settings.hideBalance,
                    onBack = viewModel::back,
                    onRecipientChange = viewModel::setRecipientInput,
                    onRecipientContinue = viewModel::continueWithRecipient,
                    onAmountChange = viewModel::setAmountInput,
                    onMaximum = viewModel::setMaximum,
                    onReview = viewModel::review,
                    onConfirm = viewModel::confirm,
                    onDone = viewModel::dismiss,
                    onConvertDirection = viewModel::setConvertToUsd,
                    onQuote = viewModel::quote,
                    onConfirmQuote = viewModel::confirmQuote,
                )
            }
        }
    }
}

@Composable
private fun SparkUsdWalletContent(
    wallet: SparkUsdState?,
    hideBalance: Boolean,
    onReceive: () -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
    onConvert: () -> Unit = {},
    onBridge: (() -> Unit)? = null,
) {
    val settings = settingsViewModel
    val app = appViewModel
    val allowSwipe by settings?.enableSwipeToHideBalance?.collectAsStateWithLifecycle() ?: remember {
        mutableStateOf(false)
    }
    val transfers = wallet?.transfers ?: persistentListOf()
    Box(modifier = Modifier.fillMaxSize().background(Colors.Black).blockPointerInputPassthrough()) {
        UsdtCoinIllustration(
            modifier = Modifier.align(Alignment.TopEnd)
                .offset(x = 81.dp, y = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 4.dp)
                .scale(scaleX = -1f, scaleY = 1f)
        )
        Column {
            AppTopBar(
                titleText = (wallet?.ticker ?: "USDT") + " · " + stringResource(R.string.lightning__spark),
                onBackClick = onBack,
                icon = R.drawable.tether_circle,
                actions = { DrawerNavIcon() }
            )
            LazyColumn(modifier = Modifier.padding(horizontal = 16.dp)) {
                item {
                    VerticalSpacer(16.dp)
                    UsdtAmountHeader(
                        amount = wallet?.let { usdtFormatAmount(it.balance) } ?: "—",
                        displayAmount = wallet?.let { usdtOverviewAmount(it.balance) },
                        hideBalance = hideBalance,
                        onToggleHide = if (allowSwipe) ({ settings?.setHideBalance(!hideBalance) }) else null,
                        modifier = Modifier.testTag("SparkUsdBalance")
                    )
                    VerticalSpacer(24.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton(
                            text = stringResource(R.string.usdt__convert_spark),
                            onClick = onConvert,
                            size = ButtonSize.Small,
                            fullWidth = false,
                            testTag = "SparkUsdConvert",
                        )
                        onBridge?.let {
                            SecondaryButton(
                                text = stringResource(R.string.usdt__bridge_spark),
                                onClick = it,
                                size = ButtonSize.Small,
                                fullWidth = false,
                                testTag = "SparkUsdBridge",
                            )
                        }
                    }
                    VerticalSpacer(32.dp)
                }
                itemsIndexed(transfers, key = { _, transfer -> transfer.id }) { index, transfer ->
                    val heading = activityGroupTitleResource(transfer.timestamp)
                    if (index == 0 || activityGroupTitleResource(transfers[index - 1].timestamp) != heading) {
                        Caption13Up(
                            stringResource(heading),
                            color = Colors.White64,
                            modifier = Modifier.padding(top = if (index == 0) 0.dp else 16.dp, bottom = 16.dp)
                        )
                    }
                    SparkUsdActivityRow(transfer, hideBalance)
                    VerticalSpacer(16.dp)
                }
                item { VerticalSpacer(120.dp) }
            }
        }
        TabBar(onSendClick = onSend, onReceiveClick = onReceive, onScanClick = {
            app?.showScannerSheet(showBackButton = false)
        })
    }
}

@Composable
private fun SparkUsdActivityRow(transfer: SparkUsdTransfer, hideBalance: Boolean) {
    val color = when (transfer.status) {
        PaymentStatus.FAILED -> Colors.Red
        PaymentStatus.PENDING -> Colors.Yellow
        PaymentStatus.COMPLETED -> Colors.Usdt
    }
    val icon = when (transfer.status) {
        PaymentStatus.FAILED -> R.drawable.ic_x_circle
        PaymentStatus.PENDING -> R.drawable.ic_hourglass_simple
        PaymentStatus.COMPLETED -> if (transfer.isIncoming) R.drawable.ic_received else R.drawable.ic_sent
    }
    val title = when (transfer.status) {
        PaymentStatus.FAILED -> R.string.usdt__failed
        PaymentStatus.PENDING -> R.string.usdt__pending
        PaymentStatus.COMPLETED -> if (transfer.isIncoming) R.string.usdt__received else R.string.usdt__sent
    }
    ActivityRowSurface(onClick = {}, modifier = Modifier.testTag("SparkUsdActivity-" + transfer.id)) {
        CircularIcon(
            icon = painterResource(icon),
            iconColor = color,
            backgroundColor = color.copy(alpha = 0.16f),
            size = 40.dp
        )
        HorizontalSpacer(16.dp)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            BodyMSB(stringResource(title), maxLines = 1)
            CaptionB(
                uiDateText(transfer.timestamp, uiDateStyleFor(transfer.timestamp)),
                color = Colors.White64,
                maxLines = 1
            )
        }
        HorizontalSpacer(16.dp)
        AmountViewContent(
            title = usdtOverviewAmount(transfer.amount),
            titlePrefix = if (transfer.isIncoming) "+" else "−",
            subtitle = "$ " + usdtOverviewAmount(transfer.amount),
            hideBalance = hideBalance
        )
    }
}

@Composable
private fun SparkUsdSheetContent(
    wallet: SparkUsdState?,
    state: SparkUsdSendState,
    hideBalance: Boolean,
    onBack: () -> Unit,
    onRecipientChange: (String) -> Unit,
    onRecipientContinue: () -> Unit,
    onAmountChange: (String) -> Unit,
    onMaximum: () -> Unit,
    onReview: () -> Unit,
    onConfirm: () -> Unit,
    onDone: () -> Unit,
    onConvertDirection: (Boolean) -> Unit = {},
    onQuote: () -> Unit = {},
    onConfirmQuote: () -> Unit = {},
) {
    val title = when {
        state.page == SparkUsdPage.RECEIVE -> R.string.usdt__receive_title
        state.page == SparkUsdPage.CONVERT -> R.string.usdt__convert_spark
        state.page == SparkUsdPage.BRIDGE -> R.string.usdt__bridge_spark
        state.completed -> R.string.usdt__sent_title_spark
        state.page == SparkUsdPage.REVIEW -> R.string.wallet__send_review
        state.page == SparkUsdPage.AMOUNT -> R.string.usdt__amount
        else -> R.string.usdt__send_title_spark
    }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        SheetTopBar(stringResource(title), onBack = onBack.takeUnless { state.busy || state.completed })
        VerticalSpacer(16.dp)
        when (state.page) {
            SparkUsdPage.RECEIVE -> SparkUsdReceiveContent(state)
            SparkUsdPage.AMOUNT -> SparkUsdAmountContent(
                wallet,
                state,
                hideBalance,
                onAmountChange,
                onMaximum,
                onReview
            )
            SparkUsdPage.REVIEW -> SparkUsdReviewContent(state, onConfirm, onDone)
            SparkUsdPage.CONVERT, SparkUsdPage.BRIDGE -> SparkUsdConvertContent(
                state,
                onAmountChange,
                onConvertDirection,
                onQuote,
                onConfirmQuote,
                onDone,
            )
            else -> SparkUsdRecipientContent(state, onRecipientChange, onRecipientContinue)
        }
    }
}

@Composable
private fun ColumnScope.SparkUsdReceiveContent(state: SparkUsdSendState) {
    val request = state.receiveRequest
    BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
        when {
            request != null -> ReceiveQrView(
                uri = request,
                copyText = request,
                qrLogoPainter = painterResource(R.drawable.tether_circle),
                onClickEditInvoice = null,
                accentColor = Colors.Usdt,
                copyMessage = stringResource(R.string.usdt__receive_copied_spark),
                modifier = Modifier.fillMaxWidth().height(minOf(maxHeight, maxWidth + ButtonSize.Small.height + 32.dp))
                    .testTag("SparkUsdReceiveQr")
            )
            state.error != null -> BodyS(stringResource(state.error), color = Colors.Brand)
            else -> GradientCircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
    }
    BodyS(
        stringResource(R.string.usdt__receive_network, stringResource(R.string.lightning__spark)),
        color = Colors.White64,
    )
}

@Composable
private fun ColumnScope.SparkUsdRecipientContent(
    state: SparkUsdSendState,
    onRecipientChange: (String) -> Unit,
    onRecipientContinue: () -> Unit,
) {
    Caption13Up(stringResource(R.string.wallet__send_to), color = Colors.White64)
    VerticalSpacer(8.dp)
    PaymentAddressInput(
        value = state.recipientInput,
        onValueChange = onRecipientChange,
        placeholder = stringResource(R.string.usdt__address),
        modifier = Modifier.fillMaxWidth().weight(1f).testTag("SparkUsdRecipient")
    )
    VerticalSpacer(16.dp)
    state.error?.let {
        BodyS(stringResource(it), color = Colors.Brand, modifier = Modifier.testTag("SparkUsdError"))
        VerticalSpacer(16.dp)
    }
    PrimaryButton(
        text = stringResource(R.string.common__continue),
        enabled = state.recipientInput.isNotBlank() && !state.busy,
        isLoading = state.busy,
        onClick = onRecipientContinue,
        modifier = Modifier.testTag("SparkUsdRecipientContinue")
    )
}

@Composable
private fun SparkUsdAmountContent(
    wallet: SparkUsdState?,
    state: SparkUsdSendState,
    hideBalance: Boolean,
    onAmountChange: (String) -> Unit,
    onMaximum: () -> Unit,
    onReview: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val maxHeight = this.maxHeight
        Column(modifier = Modifier.fillMaxWidth()) {
            UsdtAmountHeader(state.amountInput.ifEmpty { "0" }, modifier = Modifier.testTag("SparkUsdAmount"))
            FillHeight(min = 12.dp)
            state.error?.let {
                BodyS(stringResource(it), color = Colors.Brand, modifier = Modifier.testTag("SparkUsdError"))
                VerticalSpacer(16.dp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Caption13Up(stringResource(R.string.wallet__send_available), color = Colors.White64)
                    VerticalSpacer(4.dp)
                    BodySSB(
                        when {
                            hideBalance -> UiConstants.HIDE_BALANCE_SHORT
                            else -> wallet?.let { "$ " + usdtOverviewAmount(it.balance) } ?: "—"
                        }
                    )
                }
                NumberPadActionButton(
                    text = stringResource(R.string.common__max),
                    color = Colors.Usdt,
                    onClick = onMaximum,
                    textStyle = AppTextStyles.CaptionM.copy(letterSpacing = 1.sp),
                    modifier = Modifier.testTag("SparkUsdMax")
                )
            }
            VerticalSpacer(16.dp)
            HorizontalDivider(color = Colors.White10)
            NumberPad(
                type = NumberPadType.DECIMAL,
                availableHeight = maxHeight,
                onDeleteLongPress = { onAmountChange("") },
                onPress = { key ->
                    onAmountChange(
                        NumberPadInputHandler.handleInput(key, state.amountInput, maxLength = 21, maxDecimals = 6)
                    )
                },
            )
            PrimaryButton(
                text = stringResource(R.string.common__continue),
                onClick = onReview,
                enabled = state.amountInput.isNotEmpty(),
                modifier = Modifier.testTag("SparkUsdReview")
            )
        }
    }
}

@Composable
private fun ColumnScope.SparkUsdReviewContent(state: SparkUsdSendState, onConfirm: () -> Unit, onDone: () -> Unit) {
    val amount = state.amount ?: 0uL
    val swipeProgress = remember { mutableFloatStateOf(0f) }
    UsdtAmountHeader(usdtFormatAmount(amount), displayAmount = usdtOverviewAmount(amount))
    VerticalSpacer(24.dp)
    Caption13Up(stringResource(R.string.wallet__send_to), color = Colors.White64)
    VerticalSpacer(8.dp)
    BodySSB(state.recipient.orEmpty(), maxLines = 2, modifier = Modifier.testTag("SparkUsdReviewRecipient"))
    FillHeight()
    if (!state.completed) {
        PaymentReviewIllustration(
            swipeProgress = { swipeProgress.floatValue },
            isUsdt = true,
            modifier = Modifier.height(200.dp)
        )
    }
    state.error?.let {
        BodyS(stringResource(it), color = Colors.Brand, modifier = Modifier.testTag("SparkUsdError"))
        VerticalSpacer(16.dp)
    }
    if (state.completed) {
        BodyS(stringResource(R.string.usdt__submitted_description), color = Colors.White64)
        VerticalSpacer(16.dp)
        PrimaryButton(
            text = stringResource(R.string.common__close),
            onClick = onDone,
            modifier = Modifier.testTag("SparkUsdDone")
        )
    } else {
        SwipeToConfirm(
            text = stringResource(R.string.wallet__send_swipe),
            color = Colors.Usdt,
            onConfirm = onConfirm,
            enabled = !state.busy,
            loading = state.busy,
            confirmed = state.busy || state.authenticationRequired,
            progress = swipeProgress,
            textStyle = AppTextStyles.BodySSB.copy(letterSpacing = 0.sp),
            modifier = Modifier.fillMaxWidth().testTag("SparkUsdConfirm")
        )
    }
}

@Composable
private fun ColumnScope.SparkUsdConvertContent(
    state: SparkUsdSendState,
    onAmountChange: (String) -> Unit,
    onConvertDirection: (Boolean) -> Unit,
    onQuote: () -> Unit,
    onConfirmQuote: () -> Unit,
    onDone: () -> Unit,
) {
    val quote = state.quote
    if (quote == null) {
        if (state.page == SparkUsdPage.CONVERT) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(true to R.string.usdt__convert_to_usd_spark, false to R.string.usdt__convert_to_btc_spark)
                    .forEach { (toUsd, label) ->
                        NumberPadActionButton(
                            text = stringResource(label),
                            color = if (state.convertToUsd == toUsd) Colors.Usdt else Colors.White64,
                            onClick = { onConvertDirection(toUsd) },
                            textStyle = AppTextStyles.CaptionM.copy(letterSpacing = 1.sp),
                            modifier = Modifier.testTag(if (toUsd) "SparkUsdConvertToUsd" else "SparkUsdConvertToBtc")
                        )
                    }
            }
        } else {
            BodyS(stringResource(R.string.usdt__bridge_note_spark), color = Colors.White64)
        }
        VerticalSpacer(16.dp)
        UsdtAmountHeader(state.amountInput.ifEmpty { "0" }, modifier = Modifier.testTag("SparkUsdConvertAmount"))
        FillHeight(min = 12.dp)
        state.error?.let {
            BodyS(stringResource(it), color = Colors.Brand, modifier = Modifier.testTag("SparkUsdError"))
            VerticalSpacer(16.dp)
        }
        HorizontalDivider(color = Colors.White10)
        NumberPad(
            type = NumberPadType.DECIMAL,
            availableHeight = 360.dp,
            onDeleteLongPress = { onAmountChange("") },
            onPress = { key ->
                onAmountChange(
                    NumberPadInputHandler.handleInput(key, state.amountInput, maxLength = 21, maxDecimals = 6)
                )
            },
        )
        PrimaryButton(
            text = stringResource(R.string.common__continue),
            onClick = onQuote,
            enabled = state.amountInput.isNotEmpty() && !state.busy,
            isLoading = state.busy,
            modifier = Modifier.testTag("SparkUsdQuote")
        )
        return
    }
    val bitcoinIn = quote.kind == SparkUsdQuote.Kind.TO_USD
    val bitcoinOut = quote.kind == SparkUsdQuote.Kind.TO_BTC
    SparkUsdQuoteRow(R.string.usdt__convert_pay_spark, quote.pay.display(bitcoinIn))
    SparkUsdQuoteRow(R.string.usdt__convert_receive_spark, quote.receive.display(bitcoinOut))
    SparkUsdQuoteRow(R.string.usdt__convert_fee_spark, quote.fee.display(bitcoinIn))
    FillHeight()
    state.error?.let {
        BodyS(stringResource(it), color = Colors.Brand, modifier = Modifier.testTag("SparkUsdError"))
        VerticalSpacer(16.dp)
    }
    if (state.completed) {
        PrimaryButton(
            text = stringResource(R.string.common__close),
            onClick = onDone,
            modifier = Modifier.testTag("SparkUsdDone")
        )
    } else {
        SwipeToConfirm(
            text = stringResource(R.string.other__swipe),
            color = Colors.Usdt,
            onConfirm = onConfirmQuote,
            enabled = !state.busy,
            loading = state.busy,
            confirmed = state.busy,
            textStyle = AppTextStyles.BodySSB.copy(letterSpacing = 0.sp),
            modifier = Modifier.fillMaxWidth().testTag("SparkUsdConfirmQuote")
        )
    }
}

@Composable
private fun SparkUsdQuoteRow(label: Int, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Caption13Up(stringResource(label), color = Colors.White64, modifier = Modifier.weight(1f))
        BodySSB(value)
    }
    HorizontalDivider(color = Colors.White10)
}

private fun ULong.display(isSats: Boolean): String =
    if (isSats) "₿ " + formatToModernDisplay() else "$ " + usdtOverviewAmount(this)

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        SparkUsdWalletContent(
            wallet = SparkUsdState(
                ticker = "USDT",
                balance = 12_340_000uL,
                transfers = persistentListOf(
                    SparkUsdTransfer("1", true, PaymentStatus.COMPLETED, 5_000_000uL, 0uL, 1_793_098_272uL, "ab"),
                ),
            ),
            hideBalance = false,
            onReceive = {},
            onSend = {},
            onBack = {},
        )
    }
}

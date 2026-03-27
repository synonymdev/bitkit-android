package to.bitkit.ui.screens.wallets.send

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synonym.bitkitcore.LightningInvoice
import com.synonym.bitkitcore.LnurlPayData
import com.synonym.bitkitcore.NetworkType
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ext.commentAllowed
import to.bitkit.ext.formatInvoiceExpiryRelative
import to.bitkit.models.FeeRate
import to.bitkit.models.TransactionSpeed
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.components.BiometricsView
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.NumberPadActionButton
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SendSectionView
import to.bitkit.ui.components.SwipeToConfirm
import to.bitkit.ui.components.SyncNodeView
import to.bitkit.ui.components.TagButton
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.rememberMoneyText
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.settingsViewModel
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.rememberBiometricAuthSupported
import to.bitkit.ui.utils.withAccent
import to.bitkit.viewmodels.LnurlParams
import to.bitkit.viewmodels.SanityWarning
import to.bitkit.viewmodels.SendEvent
import to.bitkit.viewmodels.SendFee
import to.bitkit.viewmodels.SendMethod
import to.bitkit.viewmodels.SendUiState

private const val EXPIRY_REFRESH_INTERVAL = 60_000L

@Suppress("MagicNumber")
@Composable
fun SendConfirmScreen(
    savedStateHandle: SavedStateHandle,
    uiState: SendUiState,
    isNodeRunning: Boolean,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onEvent: (SendEvent) -> Unit,
    onClickAddTag: () -> Unit,
    onClickTag: (String) -> Unit,
    onNavigateToPin: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var isLoading by rememberSaveable { mutableStateOf(false) }
    var showBiometrics by remember { mutableStateOf(false) }
    val currentOnEvent by rememberUpdatedState(onEvent)

    val settings = settingsViewModel ?: return
    val isPinEnabled by settings.isPinEnabled.collectAsStateWithLifecycle()
    val pinForPayments by settings.isPinForPaymentsEnabled.collectAsStateWithLifecycle()
    val isBiometricEnabled by settings.isBiometricEnabled.collectAsStateWithLifecycle()
    val isBiometrySupported = rememberBiometricAuthSupported()

    // Handle result from PinCheckScreen
    LaunchedEffect(savedStateHandle) {
        savedStateHandle.getStateFlow<Boolean?>(PIN_CHECK_RESULT_KEY, null)
            .filterNotNull()
            .collect { isSuccess ->
                isLoading = isSuccess
                savedStateHandle.remove<Boolean>(PIN_CHECK_RESULT_KEY)
            }
    }

    // Confirm with pin or bio if required
    LaunchedEffect(uiState.shouldConfirmPay) {
        if (!uiState.shouldConfirmPay) return@LaunchedEffect
        if (isPinEnabled && pinForPayments) {
            currentOnEvent(SendEvent.ClearPayConfirmation)
            if (isBiometricEnabled && isBiometrySupported) {
                showBiometrics = true
            } else {
                onNavigateToPin()
            }
        } else {
            currentOnEvent(SendEvent.PayConfirmed)
        }
    }

    Content(
        uiState = uiState,
        isNodeRunning = isNodeRunning,
        isLoading = isLoading,
        showBiometrics = showBiometrics,
        canGoBack = canGoBack,
        onBack = onBack,
        onEvent = onEvent,
        onClickAddTag = onClickAddTag,
        onClickTag = onClickTag,
        onSwipeToConfirm = {
            scope.launch {
                isLoading = true
                delay(300)
                onEvent(SendEvent.SwipeToPay)
            }
        },
        onBiometricsSuccess = {
            isLoading = true
            showBiometrics = false
            onEvent(SendEvent.PayConfirmed)
        },
        onBiometricsFailure = {
            isLoading = false
            showBiometrics = false
            onNavigateToPin()
        },
    )
}

@Composable
private fun Content(
    uiState: SendUiState,
    isNodeRunning: Boolean,
    isLoading: Boolean,
    showBiometrics: Boolean,
    modifier: Modifier = Modifier,
    canGoBack: Boolean = true,
    initialShowDetails: Boolean = false,
    onBack: () -> Unit = {},
    onEvent: (SendEvent) -> Unit = {},
    onClickAddTag: () -> Unit = {},
    onClickTag: (String) -> Unit = {},
    onSwipeToConfirm: () -> Unit = {},
    onBiometricsSuccess: () -> Unit = {},
    onBiometricsFailure: () -> Unit = {},
) {
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .gradientBackground()
                .navigationBarsPadding()
        ) {
            val isLnurlPay = uiState.lnurl is LnurlParams.LnurlPay

            SheetTopBar(
                titleText = when {
                    isLnurlPay -> stringResource(R.string.wallet__lnurl_p_title)
                    else -> stringResource(R.string.wallet__send_review)
                },
                onBack = onBack.takeIf { canGoBack },
            )

            Spacer(Modifier.height(16.dp))

            if (isNodeRunning) {
                ContentRunning(
                    uiState = uiState,
                    isLoading = isLoading,
                    initialShowDetails = initialShowDetails,
                    onEvent = onEvent,
                    onClickAddTag = onClickAddTag,
                    onClickTag = onClickTag,
                    onSwipeToConfirm = onSwipeToConfirm,
                )
            } else {
                SyncNodeView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("sync_node_view")
                )
            }
        }

        if (showBiometrics) {
            BiometricsView(
                onSuccess = onBiometricsSuccess,
                onFailure = onBiometricsFailure,
            )
        }

        uiState.showSanityWarningDialog?.let { dialog ->
            AppAlertDialog(
                title = stringResource(R.string.common__are_you_sure),
                text = stringResource(dialog.message),
                confirmText = stringResource(R.string.wallet__send_yes),
                dismissText = stringResource(R.string.common__cancel),
                onConfirm = { onEvent(SendEvent.ConfirmAmountWarning(dialog)) },
                onDismiss = {
                    onEvent(SendEvent.DismissAmountWarning)
                    onBack()
                },
                modifier = Modifier
                    .semantics { testTagsAsResourceId = true }
                    .testTag(dialog.testTag),
            )
        }
    }
}

@Suppress("MagicNumber")
@Composable
fun ContentRunning(
    uiState: SendUiState,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    initialShowDetails: Boolean = false,
    onEvent: (SendEvent) -> Unit = {},
    onClickAddTag: () -> Unit = {},
    onClickTag: (String) -> Unit = {},
    onSwipeToConfirm: () -> Unit = {},
) {
    var showDetails by rememberSaveable { mutableStateOf(initialShowDetails) }
    val swipeProgress = remember { mutableFloatStateOf(0f) }

    val accentColor = when (uiState.payMethod) {
        SendMethod.ONCHAIN -> Colors.Brand
        SendMethod.LIGHTNING -> Colors.Purple
    }

    Column(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        BalanceHeaderView(
            sats = uiState.amount.toLong(),
            useSwipeToHide = false,
            onClick = { onEvent(SendEvent.BackToAmount) },
            testTag = "ReviewAmount",
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ReviewAmount")
        )

        VerticalSpacer(44.dp)

        if (showDetails) {
            when (uiState.payMethod) {
                SendMethod.ONCHAIN -> OnChainDetails(uiState = uiState, onEvent = onEvent)
                SendMethod.LIGHTNING -> LightningDetails(uiState = uiState, onEvent = onEvent)
            }

            if (uiState.lnurl is LnurlParams.LnurlPay) {
                if (uiState.lnurl.data.commentAllowed()) {
                    LnurlCommentSection(uiState, onEvent)
                }
            } else {
                TagsSection(uiState, onClickTag, onClickAddTag)
            }
        } else {
            Image(
                painter = painterResource(R.drawable.coin_stack_4),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 16.dp)
                    .graphicsLayer { rotationZ = swipeProgress.floatValue * 14f }
            )
        }

        VerticalSpacer(16.dp)

        PrimaryButton(
            text = stringResource(
                if (showDetails) R.string.common__hide_details else R.string.common__show_details
            ),
            size = ButtonSize.Small,
            onClick = { showDetails = !showDetails },
            icon = {
                Icon(
                    painter = painterResource(
                        if (showDetails) {
                            R.drawable.ic_eye_slash
                        } else {
                            when (uiState.payMethod) {
                                SendMethod.ONCHAIN -> R.drawable.ic_speed_normal
                                SendMethod.LIGHTNING -> R.drawable.ic_lightning
                            }
                        }
                    ),
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp)
                )
            },
            fullWidth = false,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .testTag("SendConfirmToggleDetails")
        )

        FillHeight(min = 16.dp)

        SwipeToConfirm(
            text = stringResource(R.string.wallet__send_swipe),
            color = accentColor,
            loading = isLoading,
            confirmed = isLoading,
            progress = swipeProgress,
            onConfirm = onSwipeToConfirm,
        )
        VerticalSpacer(16.dp)
    }
}

@Composable
private fun LnurlCommentSection(
    uiState: SendUiState,
    onEvent: (SendEvent) -> Unit,
) {
    Spacer(modifier = Modifier.height(16.dp))
    Caption13Up(stringResource(R.string.wallet__lnurl_pay_confirm__comment), color = Colors.White64)
    Spacer(modifier = Modifier.height(8.dp))

    TextInput(
        value = uiState.comment,
        placeholder = stringResource(R.string.wallet__lnurl_pay_confirm__comment_placeholder),
        onValueChange = { onEvent(SendEvent.CommentChange(it)) },
        minLines = 3,
        maxLines = 3,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("CommentInput")
    )
}

@Composable
private fun TagsSection(
    uiState: SendUiState,
    onClickTag: (String) -> Unit,
    onClickAddTag: () -> Unit,
) {
    Spacer(modifier = Modifier.height(16.dp))
    Caption13Up(text = stringResource(R.string.wallet__tags), color = Colors.White64)
    Spacer(modifier = Modifier.height(8.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
    ) {
        uiState.selectedTags.map { tagText ->
            TagButton(
                text = tagText,
                displayIconClose = true,
                onClick = { onClickTag(tagText) },
            )
        }
    }
    PrimaryButton(
        text = stringResource(R.string.wallet__tags_add),
        size = ButtonSize.Small,
        onClick = onClickAddTag,
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_tag),
                contentDescription = stringResource(R.string.wallet__tags_add),
                tint = Colors.Brand,
            )
        },
        fullWidth = false,
        modifier = Modifier.testTag("TagsAddSend")
    )
    HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
}

@Composable
private fun OnChainDetails(
    uiState: SendUiState,
    onEvent: (SendEvent) -> Unit,
) {
    val fee = remember(uiState.speed) { FeeRate.fromSpeed(uiState.speed) }
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.height(IntrinsicSize.Min)
        ) {
            SendSectionView(
                caption = stringResource(R.string.wallet__send_from),
                modifier = Modifier.weight(1f)
            ) {
                NumberPadActionButton(
                    text = stringResource(R.string.wallet__savings__title),
                    color = Colors.Brand,
                    enabled = uiState.canSwitchWallet,
                    icon = R.drawable.ic_transfer.takeIf { uiState.canSwitchWallet },
                    onClick = { onEvent(SendEvent.PaymentMethodSwitch) },
                    modifier = Modifier.testTag("SendConfirmAssetButton")
                )
            }
            SendSectionView(
                caption = stringResource(R.string.wallet__send_to),
                modifier = Modifier.weight(1f)
            ) {
                BodySSB(
                    text = uiState.address,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier
                        .height(28.dp)
                        .clickableAlpha { onEvent(SendEvent.NavToAddress) }
                        .testTag("ReviewUri")
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.height(IntrinsicSize.Min)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickableAlpha { onEvent(SendEvent.SpeedAndFee) }
            ) {
                SendSectionView(caption = stringResource(R.string.wallet__send_fee_and_speed)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            painterResource(fee.icon),
                            contentDescription = null,
                            tint = fee.color,
                            modifier = Modifier.size(16.dp)
                        )
                        (uiState.fee as? SendFee.OnChain)?.value
                            ?.takeIf { it > 0 }
                            ?.let { feeSat ->
                                val feeText = let {
                                    val prefix = stringResource(fee.title)
                                    val value = rememberMoneyText(feeSat, showSymbol = true)
                                    "$prefix ($value)"
                                }
                                BodySSB(
                                    text = feeText.withAccent(accentColor = Colors.White),
                                    maxLines = 1,
                                    overflow = TextOverflow.MiddleEllipsis,
                                )
                            }
                            ?: CircularProgressIndicator(Modifier.size(14.dp), Colors.White64, 2.dp)
                        Icon(
                            painterResource(R.drawable.ic_pencil_simple),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            SendSectionView(
                caption = stringResource(R.string.wallet__send_confirming_in),
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_clock),
                        contentDescription = null,
                        tint = Colors.Brand,
                        modifier = Modifier.size(16.dp)
                    )
                    BodySSB(stringResource(fee.description))
                }
            }
        }
    }
}

@Suppress("CyclomaticComplexMethod")
@Composable
private fun LightningDetails(
    uiState: SendUiState,
    onEvent: (SendEvent) -> Unit,
) {
    val isLnurlPay = uiState.lnurl is LnurlParams.LnurlPay
    val expirySeconds = uiState.decodedInvoice?.expirySeconds
    val description = uiState.decodedInvoice?.description
    val destination = when {
        isLnurlPay -> (uiState.lnurl as LnurlParams.LnurlPay).data.uri
        else -> uiState.decodedInvoice?.bolt11.orEmpty()
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.height(IntrinsicSize.Min)
        ) {
            SendSectionView(
                caption = stringResource(R.string.wallet__send_from),
                modifier = Modifier.weight(1f)
            ) {
                NumberPadActionButton(
                    text = stringResource(R.string.wallet__spending__title),
                    color = Colors.Purple,
                    enabled = uiState.canSwitchWallet,
                    icon = R.drawable.ic_transfer.takeIf { uiState.canSwitchWallet },
                    onClick = { onEvent(SendEvent.PaymentMethodSwitch) },
                    modifier = Modifier.testTag("SendConfirmAssetButton")
                )
            }
            SendSectionView(
                caption = stringResource(R.string.wallet__send_to),
                modifier = Modifier.weight(1f)
            ) {
                BodySSB(
                    text = destination,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier
                        .height(28.dp)
                        .clickableAlpha { onEvent(SendEvent.NavToAddress) }
                        .testTag("ReviewUri")
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.height(IntrinsicSize.Min)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .let { if (uiState.canSwitchWallet) it.clickableAlpha { onEvent(SendEvent.SpeedAndFee) } else it }
            ) {
                SendSectionView(caption = stringResource(R.string.wallet__send_fee_and_speed)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_lightning),
                            contentDescription = null,
                            tint = Colors.Purple,
                            modifier = Modifier.size(16.dp)
                        )
                        (uiState.fee as? SendFee.Lightning)?.value
                            ?.takeIf { it > 0 }
                            ?.let { feeSat ->
                                val feeText = let {
                                    val prefix = stringResource(R.string.fee__instant__title)
                                    val value = rememberMoneyText(feeSat, showSymbol = true)
                                    "$prefix (± $value)"
                                }
                                BodySSB(
                                    text = feeText.withAccent(accentColor = Colors.White),
                                    maxLines = 1,
                                    overflow = TextOverflow.MiddleEllipsis,
                                )
                            } ?: BodySSB(text = stringResource(R.string.fee__instant__title))
                        if (uiState.canSwitchWallet) {
                            Icon(
                                painterResource(R.drawable.ic_pencil_simple),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
            if (!isLnurlPay && expirySeconds != null) {
                SendSectionView(
                    caption = stringResource(R.string.wallet__send_invoice_expiration),
                    modifier = Modifier.weight(1f),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_timer),
                            contentDescription = null,
                            tint = Colors.Purple,
                            modifier = Modifier.size(16.dp)
                        )
                        val timestampSeconds = uiState.decodedInvoice?.timestampSeconds ?: 0uL
                        val invoiceExpiryText by produceState("", timestampSeconds, expirySeconds) {
                            val expiryMoment = timestampSeconds + expirySeconds
                            while (true) {
                                val now = System.currentTimeMillis() / 1000
                                val remaining = (expiryMoment.toLong() - now).coerceAtLeast(0)
                                value = formatInvoiceExpiryRelative(remaining.toULong())
                                delay(EXPIRY_REFRESH_INTERVAL)
                            }
                        }
                        BodySSB(text = invoiceExpiryText)
                    }
                }
            }
        }

        if (!isLnurlPay && description != null) {
            SendSectionView(caption = stringResource(R.string.wallet__note)) {
                BodySSB(text = description, maxLines = 1)
            }
        }
    }
}

@Suppress("SpellCheckingInspection")
private fun sendUiState() = SendUiState(
    amount = 2_345u,
    address = "bcrt1qkgfgyxyqhvkdqh04sklnzxphmcds6vft6y7h0r",
    decodedInvoice = LightningInvoice(
        bolt11 = "lnbcrt1p5frwyedq2gf5hg6mfwsnp4qdkkcte90vc7c5z6z72uu5p2schwkmlx9j704tuwm2z59wfgku46xpp56yfmwmfxtl",
        paymentHash = ByteArray(0),
        amountSatoshis = 6_543u,
        timestampSeconds = 0u,
        expirySeconds = 3600u,
        isExpired = false,
        networkType = NetworkType.REGTEST,
        payeeNodeId = null,
        description = "Some invoice description",
    ),
)

@Suppress("MagicNumber")
@Preview(showSystemUi = true, group = "onchain")
@Composable
private fun PreviewOnChain() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                uiState = sendUiState().copy(
                    selectedTags = persistentListOf("car", "house", "uber"),
                    fee = SendFee.OnChain(1_234),
                    speed = TransactionSpeed.Medium,
                ),
                isNodeRunning = true,
                isLoading = false,
                showBiometrics = false,
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}

@Suppress("MagicNumber")
@Preview(showSystemUi = true, group = "onchain details")
@Composable
private fun PreviewOnChainDetails() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                uiState = sendUiState().copy(
                    selectedTags = persistentListOf("car", "house", "uber"),
                    fee = SendFee.OnChain(1_234),
                    speed = TransactionSpeed.Medium,
                ),
                isNodeRunning = true,
                isLoading = false,
                showBiometrics = false,
                initialShowDetails = true,
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}

@Suppress("MagicNumber")
@Preview(showSystemUi = true, group = "lightning details")
@Composable
private fun PreviewLightningDetails() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                uiState = sendUiState().copy(
                    amount = 6_543u,
                    payMethod = SendMethod.LIGHTNING,
                    selectedTags = persistentListOf("coffee"),
                    fee = SendFee.Lightning(43),
                ),
                isNodeRunning = true,
                isLoading = false,
                showBiometrics = false,
                initialShowDetails = true,
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}

@Suppress("MagicNumber")
@Preview(showSystemUi = true, group = "onchain", device = Devices.NEXUS_5)
@Composable
private fun PreviewOnChainLongFeeSmallScreen() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                uiState = sendUiState().copy(
                    amount = 2_345_678u,
                    selectedTags = persistentListOf("car", "house", "uber"),
                    fee = SendFee.OnChain(654_321),
                    speed = TransactionSpeed.Custom(12_345u),
                ),
                isNodeRunning = true,
                isLoading = false,
                showBiometrics = false,
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}

@Preview(showSystemUi = true, group = "onchain")
@Composable
private fun PreviewOnChainFeeLoading() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                uiState = sendUiState().copy(
                    selectedTags = persistentListOf("car", "house", "uber"),
                    fee = null,
                ),
                isNodeRunning = true,
                isLoading = false,
                showBiometrics = false,
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}

@Suppress("MagicNumber")
@Preview(showSystemUi = true)
@Composable
private fun PreviewLightning() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                uiState = sendUiState().copy(
                    amount = 6_543u,
                    payMethod = SendMethod.LIGHTNING,
                    selectedTags = persistentListOf(),
                    fee = SendFee.Lightning(43),
                ),
                isNodeRunning = true,
                isLoading = false,
                showBiometrics = false,
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewLnurl() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                uiState = sendUiState().copy(
                    payMethod = SendMethod.LIGHTNING,
                    lnurl = LnurlParams.LnurlPay(
                        data = LnurlPayData(
                            uri = "veryLongLnurlPayUri12345677890123456789012345678901234567890",
                            callback = "",
                            metadataStr = "",
                            commentAllowed = 255u,
                            minSendable = 1000u,
                            maxSendable = 1000_000u,
                            allowsNostr = false,
                            nostrPubkey = null,
                        ),
                    ),
                ),
                isNodeRunning = true,
                isLoading = false,
                showBiometrics = false,
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewBio() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                uiState = sendUiState(),
                isNodeRunning = true,
                isLoading = false,
                showBiometrics = true,
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewDialog() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                uiState = sendUiState().copy(
                    showSanityWarningDialog = SanityWarning.VALUE_OVER_100_USD,
                ),
                isNodeRunning = true,
                isLoading = false,
                showBiometrics = true,
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewNodeNotRunning() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                uiState = sendUiState().copy(
                    payMethod = SendMethod.LIGHTNING,
                    lnurl = LnurlParams.LnurlPay(
                        data = LnurlPayData(
                            uri = "veryLongLnurlPayUri12345677890123456789012345678901234567890",
                            callback = "",
                            metadataStr = "",
                            commentAllowed = 255u,
                            minSendable = 1000u,
                            maxSendable = 1000_000u,
                            allowsNostr = false,
                            nostrPubkey = null,
                        ),
                    ),
                ),
                isNodeRunning = false,
                isLoading = false,
                showBiometrics = false,
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}

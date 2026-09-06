package to.bitkit.ui.screens.wallets.send

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import to.bitkit.models.PubkyProfile
import to.bitkit.models.TransactionSpeed
import to.bitkit.ui.components.AddTagButton
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.components.BiometricsView
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.GradientCircularProgressIndicator
import to.bitkit.ui.components.NumberPadActionButton
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.PubkyContactAvatar
import to.bitkit.ui.components.SendCell
import to.bitkit.ui.components.SwipeToConfirm
import to.bitkit.ui.components.SyncNodeView
import to.bitkit.ui.components.TagButton
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.rememberMoneyText
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.settingsViewModel
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.rememberBiometricAuthSupported
import to.bitkit.ui.utils.withAccent
import to.bitkit.viewmodels.LnurlParams
import to.bitkit.viewmodels.OnchainFeeUi
import to.bitkit.viewmodels.SanityWarning
import to.bitkit.viewmodels.SendEvent
import to.bitkit.viewmodels.SendMethod
import to.bitkit.viewmodels.SendUiState
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val EXPIRY_REFRESH_INTERVAL = 60.seconds
private const val SWIPE_ROTATION_DEGREES = 14f
private const val IMAGE_FILL_PERCENTAGE = 0.8f
const val HARDWARE_SIGN_CANCELLED_RESULT_KEY = "HARDWARE_SIGN_CANCELLED_RESULT_KEY"

@Suppress("MagicNumber")
@Composable
fun SendConfirmScreen(
    savedStateHandle: SavedStateHandle,
    uiState: SendUiState,
    isNodeRunning: Boolean,
    canAutoStart: Boolean,
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
                if (!isSuccess && uiState.isInitialSubscriptionPayment) {
                    currentOnEvent(SendEvent.CancelInitialSubscriptionPayment)
                }
            }
    }

    LaunchedEffect(savedStateHandle) {
        savedStateHandle.getStateFlow(HARDWARE_SIGN_CANCELLED_RESULT_KEY, false)
            .collect {
                if (!it) return@collect
                isLoading = false
                savedStateHandle.remove<Boolean>(HARDWARE_SIGN_CANCELLED_RESULT_KEY)
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

    LaunchedEffect(uiState.initialSubscriptionPaymentAutoStartPending, canAutoStart) {
        if (!uiState.initialSubscriptionPaymentAutoStartPending || !canAutoStart) return@LaunchedEffect
        isLoading = uiState.shouldAutomaticallyPay
        currentOnEvent(SendEvent.StartInitialSubscriptionPayment)
    }

    SendConfirmContent(
        uiState = uiState,
        isNodeRunning = isNodeRunning,
        isLoading = isLoading,
        showBiometrics = showBiometrics,
        canGoBack = canGoBack,
        initialShowDetails = uiState.isInitialSubscriptionPayment && !uiState.shouldAutomaticallyPay,
        onBack = onBack,
        onEvent = onEvent,
        onClickAddTag = onClickAddTag,
        onClickTag = onClickTag,
        onSwipeToConfirm = {
            scope.launch {
                isLoading = true
                delay(300.milliseconds)
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
internal fun SendConfirmContent(
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

            SendContactTopBar(
                titleText = when {
                    uiState.isInitialSubscriptionPayment -> stringResource(R.string.subscriptions__review_and_subscribe)
                    uiState.isSubscriptionPayment -> stringResource(R.string.subscriptions__subscription)
                    uiState.isPaymentRequest -> stringResource(R.string.wallet__payment_request)
                    isLnurlPay -> stringResource(R.string.wallet__lnurl_p_title)
                    else -> stringResource(R.string.wallet__send_review)
                },
                contact = uiState.contactPaymentProfile,
                onBack = onBack.takeIf { canGoBack },
            )

            Spacer(Modifier.height(16.dp))

            if (uiState.shouldAutomaticallyPay && (uiState.initialSubscriptionPaymentAutoStartPending || isLoading)) {
                FillHeight()
                GradientCircularProgressIndicator(modifier = Modifier.size(32.dp).align(Alignment.CenterHorizontally))
                FillHeight()
            } else if (isNodeRunning) {
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
                    if (uiState.isInitialSubscriptionPayment) {
                        onEvent(SendEvent.CancelInitialSubscriptionPayment)
                    } else {
                        onBack()
                    }
                },
                modifier = Modifier
                    .semantics { testTagsAsResourceId = true }
                    .testTag(dialog.testTag),
            )
        }
    }
}

@Composable
@Suppress("CyclomaticComplexMethod")
private fun ContentRunning(
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
    val isLnurlPay = uiState.lnurl is LnurlParams.LnurlPay
    val isHardwareFeeLoading = uiState.hardwareWalletId != null && uiState.onchainFeeUi.isLoading

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

        if (isLnurlPay) {
            LnurlPayDetails(uiState = uiState, onEvent = onEvent)
        } else if (showDetails) {
            when (uiState.payMethod) {
                SendMethod.ONCHAIN -> {
                    OnChainDetails(
                        uiState = uiState,
                        interactionsEnabled = !isHardwareFeeLoading,
                        onEvent = onEvent,
                    )
                    VerticalSpacer(16.dp)
                    TagsSection(uiState, onClickTag, onClickAddTag)
                }

                SendMethod.LIGHTNING -> {
                    LightningDetails(
                        uiState = uiState,
                        onEvent = onEvent,
                        onClickTag = onClickTag,
                        onClickAddTag = onClickAddTag,
                    )
                }
            }
        } else {
            Image(
                painter = painterResource(R.drawable.coin_stack_4),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth(IMAGE_FILL_PERCENTAGE)
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 16.dp)
                    .graphicsLayer { rotationZ = swipeProgress.floatValue * SWIPE_ROTATION_DEGREES }
            )
        }

        if (!isLnurlPay) {
            FillHeight(min = 16.dp)

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
                color = Colors.Gray65,
                enableGradient = false,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .testTag("SendConfirmToggleDetails")
            )

            VerticalSpacer(62.dp)
        } else {
            FillHeight(min = 16.dp)
        }

        SwipeToConfirm(
            text = stringResource(
                if (uiState.isInitialSubscriptionPayment) {
                    R.string.subscriptions__swipe_to_subscribe_and_pay
                } else {
                    R.string.wallet__send_swipe
                }
            ),
            color = accentColor,
            enabled = uiState.isAmountInputValid &&
                !uiState.isFundingSourceLoading &&
                !isHardwareFeeLoading,
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
    modifier: Modifier = Modifier,
) {
    SendCell(
        caption = stringResource(R.string.wallet__tags),
        modifier = modifier
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            uiState.selectedTags.forEach { tagText ->
                TagButton(
                    text = tagText,
                    displayIconClose = true,
                    onClick = { onClickTag(tagText) },
                )
            }
            AddTagButton(
                onClick = onClickAddTag,
                modifier = Modifier.testTag("TagsAddSend")
            )
        }
    }
}

@Composable
private fun OnChainDetails(
    uiState: SendUiState,
    interactionsEnabled: Boolean,
    onEvent: (SendEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val feeUi = uiState.onchainFeeUi
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.height(IntrinsicSize.Min)
        ) {
            SendCell(
                caption = stringResource(R.string.wallet__send_from),
                modifier = Modifier.weight(1f)
            ) {
                NumberPadActionButton(
                    text = if (uiState.hardwareWalletId != null) {
                        uiState.hardwareWalletName ?: stringResource(R.string.hardware__device_model_trezor)
                    } else {
                        stringResource(R.string.wallet__savings__title)
                    },
                    color = if (uiState.hardwareWalletId != null) Colors.Blue else Colors.Brand,
                    enabled = uiState.canSwitchFundingSource,
                    isLoading = uiState.isFundingSourceLoading,
                    clickable = interactionsEnabled,
                    icon = R.drawable.ic_transfer.takeIf { uiState.canSwitchFundingSource },
                    onClick = { onEvent(SendEvent.PaymentMethodSwitch) },
                    modifier = Modifier.testTag("SendConfirmAssetButton")
                )
            }
            SendCell(
                caption = stringResource(R.string.wallet__send_to),
                modifier = Modifier.weight(1f)
            ) {
                if (uiState.contactPaymentProfile != null) {
                    ContactRecipient(profile = uiState.contactPaymentProfile)
                } else {
                    BodySSB(
                        text = uiState.address,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        modifier = Modifier
                            .height(28.dp)
                            .wrapContentHeight(Alignment.CenterVertically)
                            .clickableAlpha { onEvent(SendEvent.NavToAddress) }
                            .testTag("ReviewUri")
                    )
                }
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
                    .clickableAlpha(enabled = interactionsEnabled) { onEvent(SendEvent.SpeedAndFee) }
            ) {
                SendCell(caption = stringResource(R.string.wallet__send_fee_and_speed)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (feeUi.isLoading) {
                            GradientCircularProgressIndicator(
                                tint = feeUi.rate.color,
                                modifier = Modifier
                                    .size(16.dp)
                                    .padding(3.dp),
                            )
                        } else {
                            Icon(
                                painterResource(feeUi.rate.icon),
                                contentDescription = null,
                                tint = feeUi.rate.color,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        val feeTextModifier = Modifier.animateContentSize(animationSpec = tween(200))
                        if (feeUi.sats == null) {
                            BodySSB(
                                text = stringResource(feeUi.rate.title),
                                modifier = feeTextModifier
                            )
                        } else {
                            val feeText = let {
                                val prefix = stringResource(feeUi.rate.title)
                                val value = rememberMoneyText(feeUi.sats, showSymbol = true)
                                "$prefix ($value)"
                            }
                            BodySSB(
                                text = feeText.withAccent(accentColor = Colors.White),
                                maxLines = 1,
                                overflow = TextOverflow.MiddleEllipsis,
                                modifier = feeTextModifier
                            )
                        }
                        Icon(
                            painterResource(R.drawable.ic_pencil_simple),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            SendCell(
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
                    BodySSB(stringResource(feeUi.rate.description))
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
    onClickTag: (String) -> Unit,
    onClickAddTag: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isLnurlPay = uiState.lnurl is LnurlParams.LnurlPay
    val expirySeconds = uiState.decodedInvoice?.expirySeconds
    val description = uiState.decodedInvoice?.description
    val destination = when (val lnurl = uiState.lnurl) {
        is LnurlParams.LnurlPay -> lnurl.data.uri
        else -> uiState.decodedInvoice?.bolt11.orEmpty()
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.height(IntrinsicSize.Min)
        ) {
            SendCell(
                caption = stringResource(R.string.wallet__send_from),
                modifier = Modifier.weight(1f)
            ) {
                NumberPadActionButton(
                    text = stringResource(R.string.wallet__spending__title),
                    color = Colors.Purple,
                    enabled = uiState.canSwitchFundingSource,
                    isLoading = uiState.isFundingSourceLoading,
                    icon = R.drawable.ic_transfer.takeIf { uiState.canSwitchFundingSource },
                    onClick = { onEvent(SendEvent.PaymentMethodSwitch) },
                    modifier = Modifier.testTag("SendConfirmAssetButton")
                )
            }
            SendCell(
                caption = stringResource(R.string.wallet__send_to),
                modifier = Modifier.weight(1f)
            ) {
                if (uiState.contactPaymentProfile != null) {
                    ContactRecipient(profile = uiState.contactPaymentProfile)
                } else {
                    BodySSB(
                        text = destination,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        modifier = Modifier
                            .height(28.dp)
                            .wrapContentHeight(Alignment.CenterVertically)
                            .clickableAlpha { onEvent(SendEvent.NavToAddress) }
                            .testTag("ReviewUri")
                    )
                }
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
                SendCell(caption = stringResource(R.string.wallet__send_fee_and_speed)) {
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
                        uiState.lightningFeeSats
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
                SendCell(
                    caption = stringResource(R.string.wallet__send_invoice_expiration),
                    modifier = Modifier.weight(1f)
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
                        val timestampSeconds = uiState.decodedInvoice.timestampSeconds
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

        if (!isLnurlPay && !description.isNullOrEmpty()) {
            SendCell(caption = stringResource(R.string.wallet__note)) {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    BodySSB(text = description, maxLines = 1)
                }
            }
        }

        if (!isLnurlPay) {
            TagsSection(
                uiState = uiState,
                onClickTag = onClickTag,
                onClickAddTag = onClickAddTag,
            )
        }
    }
}

@Composable
private fun ContactRecipient(
    profile: PubkyProfile,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .padding(vertical = 2.dp)
            .testTag("ReviewContactRecipient")
    ) {
        PubkyContactAvatar(profile = profile, size = 24.dp)
        BodySSB(
            text = profile.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LnurlPayDetails(
    uiState: SendUiState,
    onEvent: (SendEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lnurlPay = uiState.lnurl as? LnurlParams.LnurlPay ?: return
    Column(modifier = modifier.fillMaxWidth()) {
        SendCell(caption = stringResource(R.string.wallet__send_invoice)) {
            BodySSB(
                text = lnurlPay.data.uri,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier
                    .height(28.dp)
                    .wrapContentHeight(Alignment.CenterVertically)
                    .clickableAlpha { onEvent(SendEvent.NavToAddress) }
                    .testTag("ReviewUri")
            )
        }

        VerticalSpacer(16.dp)

        SendCell(caption = stringResource(R.string.wallet__send_fee_and_speed)) {
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
                uiState.lightningFeeSats
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
            }
        }

        if (lnurlPay.data.commentAllowed()) {
            LnurlCommentSection(uiState, onEvent)
        }
    }
}

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
            SendConfirmContent(
                uiState = sendUiState().copy(
                    selectedTags = persistentListOf("car", "house", "uber"),
                    speed = TransactionSpeed.Medium,
                    onchainFeeUi = OnchainFeeUi(
                        rate = FeeRate.NORMAL,
                        sats = 1_234,
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

@Suppress("MagicNumber")
@Preview(showSystemUi = true, group = "onchain details")
@Composable
private fun PreviewOnChainDetails() {
    AppThemeSurface {
        BottomSheetPreview {
            SendConfirmContent(
                uiState = sendUiState().copy(
                    selectedTags = persistentListOf("car", "house", "uber"),
                    speed = TransactionSpeed.Medium,
                    onchainFeeUi = OnchainFeeUi(
                        rate = FeeRate.NORMAL,
                        sats = 1_234,
                    ),
                ),
                isNodeRunning = true,
                isLoading = false,
                showBiometrics = false,
                initialShowDetails = true,
                modifier = Modifier.sheetHeight()
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
            SendConfirmContent(
                uiState = sendUiState().copy(
                    amount = 6_543u,
                    payMethod = SendMethod.LIGHTNING,
                    selectedTags = persistentListOf("coffee"),
                    lightningFeeSats = 43,
                ),
                isNodeRunning = true,
                isLoading = false,
                showBiometrics = false,
                initialShowDetails = true,
                modifier = Modifier.sheetHeight()
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
            SendConfirmContent(
                uiState = sendUiState().copy(
                    amount = 2_345_678u,
                    selectedTags = persistentListOf("car", "house", "uber"),
                    speed = TransactionSpeed.Custom(12_345u),
                    onchainFeeUi = OnchainFeeUi(
                        rate = FeeRate.CUSTOM,
                        sats = 654_321,
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

@Preview(showSystemUi = true, group = "onchain")
@Composable
private fun PreviewOnChainFeeLoading() {
    AppThemeSurface {
        BottomSheetPreview {
            SendConfirmContent(
                uiState = sendUiState().copy(
                    selectedTags = persistentListOf("car", "house", "uber"),
                    onchainFeeUi = OnchainFeeUi(isLoading = true),
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
            SendConfirmContent(
                uiState = sendUiState().copy(
                    amount = 6_543u,
                    payMethod = SendMethod.LIGHTNING,
                    selectedTags = persistentListOf(),
                    lightningFeeSats = 43,
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
            SendConfirmContent(
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

@Suppress("MagicNumber")
@Preview(showSystemUi = true, group = "lnurl details")
@Composable
private fun PreviewLnurlDetails() {
    AppThemeSurface {
        BottomSheetPreview {
            SendConfirmContent(
                uiState = sendUiState().copy(
                    amount = 5_000u,
                    payMethod = SendMethod.LIGHTNING,
                    lightningFeeSats = 12,
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
                    comment = "Thanks for the coffee!",
                ),
                isNodeRunning = true,
                isLoading = false,
                showBiometrics = false,
                modifier = Modifier.sheetHeight()
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewBio() {
    AppThemeSurface {
        BottomSheetPreview {
            SendConfirmContent(
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
            SendConfirmContent(
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
            SendConfirmContent(
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

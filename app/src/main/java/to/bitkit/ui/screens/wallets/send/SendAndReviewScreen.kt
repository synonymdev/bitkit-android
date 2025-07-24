package to.bitkit.ui.screens.wallets.send

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synonym.bitkitcore.LightningInvoice
import com.synonym.bitkitcore.LnurlPayData
import com.synonym.bitkitcore.NetworkType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ext.DatePattern
import to.bitkit.ext.commentAllowed
import to.bitkit.ext.formatted
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.components.BiometricsView
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SwipeToConfirm
import to.bitkit.ui.components.TagButton
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.settingsViewModel
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.rememberBiometricAuthSupported
import to.bitkit.viewmodels.AmountWarning
import to.bitkit.viewmodels.LnUrlParameters
import to.bitkit.viewmodels.SendEvent
import to.bitkit.viewmodels.SendMethod
import to.bitkit.viewmodels.SendUiState
import java.time.Instant

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SendAndReviewScreen(
    savedStateHandle: SavedStateHandle,
    uiState: SendUiState,
    onBack: () -> Unit,
    onEvent: (SendEvent) -> Unit,
    onClickAddTag: () -> Unit,
    onClickTag: (String) -> Unit,
    onNavigateToPin: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // TODO handle loading via uiState?
    var isLoading by rememberSaveable { mutableStateOf(false) }
    var showBiometrics by remember { mutableStateOf(false) }

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

    // Handle pay confirm with auth check if needed
    LaunchedEffect(uiState.shouldConfirmPay) {
        if (uiState.shouldConfirmPay) {
            if (isPinEnabled && pinForPayments) {
                if (isBiometricEnabled && isBiometrySupported) {
                    showBiometrics = true
                } else {
                    onNavigateToPin()
                }
            } else {
                onEvent(SendEvent.PayConfirmed)
            }
        }
    }

    SendAndReviewContent(
        uiState = uiState,
        isLoading = isLoading,
        showBiometrics = showBiometrics,
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
private fun SendAndReviewContent(
    uiState: SendUiState,
    isLoading: Boolean,
    showBiometrics: Boolean,
    onBack: () -> Unit,
    onEvent: (SendEvent) -> Unit,
    onClickAddTag: () -> Unit,
    onClickTag: (String) -> Unit,
    onSwipeToConfirm: () -> Unit,
    onBiometricsSuccess: () -> Unit,
    onBiometricsFailure: () -> Unit,
) {
    Box {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .gradientBackground()
                .navigationBarsPadding()
        ) {
            val isLnurlPay = uiState.lnUrlParameters is LnUrlParameters.LnUrlPay

            SheetTopBar(
                titleText = when {
                    isLnurlPay -> stringResource(R.string.wallet__lnurl_p_title)
                    else -> stringResource(R.string.wallet__send_review)
                },
                onBack = onBack,
            )

            Spacer(Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                BalanceHeaderView(sats = uiState.amount.toLong(), modifier = Modifier.fillMaxWidth())

                Spacer(modifier = Modifier.height(16.dp))

                when (uiState.payMethod) {
                    SendMethod.ONCHAIN -> OnChainDescription(uiState = uiState, onEvent = onEvent)
                    SendMethod.LIGHTNING -> LightningDescription(uiState = uiState)
                }

                if (isLnurlPay) {
                    if (uiState.lnUrlParameters.data.commentAllowed()) {
                        LnurlCommentSection(uiState, onEvent)
                    }
                } else {
                    TagsSection(uiState, onClickTag, onClickAddTag)
                }

                FillHeight()
                VerticalSpacer(16.dp)

                SwipeToConfirm(
                    text = stringResource(R.string.wallet__send_swipe),
                    loading = isLoading,
                    confirmed = isLoading,
                    onConfirm = onSwipeToConfirm,
                )
                VerticalSpacer(16.dp)
            }
        }

        if (showBiometrics) {
            BiometricsView(
                onSuccess = onBiometricsSuccess,
                onFailure = onBiometricsFailure,
            )
        }

        uiState.showAmountWarningDialog?.let { dialog ->
            AppAlertDialog(
                title = stringResource(R.string.common__are_you_sure),
                text = stringResource(dialog.message),
                confirmText = stringResource(R.string.wallet__send_yes),
                dismissText = stringResource(R.string.common__cancel),
                onConfirm = { onEvent(SendEvent.ConfirmAmountWarning) },
                onDismiss = {
                    onEvent(SendEvent.DismissAmountWarning)
                    onBack()
                },
            )
        }
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
        modifier = Modifier.fillMaxWidth()
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
    )
    HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
}

@Composable
private fun OnChainDescription(
    uiState: SendUiState,
    onEvent: (SendEvent) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Caption13Up(
            text = stringResource(R.string.wallet__send_to),
            color = Colors.White64,
        )
        Spacer(modifier = Modifier.height(8.dp))
        BodySSB(text = uiState.address, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        HorizontalDivider(modifier = Modifier.padding(top = 16.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.height(IntrinsicSize.Min)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    .clickable { onEvent(SendEvent.SpeedAndFee) }
                    .padding(top = 16.dp)
            ) {
                Caption13Up(text = stringResource(R.string.wallet__send_fee_and_speed), color = Colors.White64)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_speed_normal),
                        contentDescription = null,
                        tint = Colors.Brand,
                        modifier = Modifier.size(16.dp)
                    )
                    BodySSB(text = "Normal (₿ 210)") // TODO GET FROM STATE
                    Icon(
                        painterResource(R.drawable.ic_pencil_simple),
                        contentDescription = null,
                        tint = Colors.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
            }
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    .clickableAlpha { onEvent(SendEvent.SpeedAndFee) }
                    .padding(top = 16.dp)
            ) {
                Caption13Up(text = stringResource(R.string.wallet__send_confirming_in), color = Colors.White64)
                Spacer(modifier = Modifier.height(8.dp))
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
                    BodySSB(text = "± 20-60 minutes") // TODO GET FROM STATE
                }
                Spacer(modifier = Modifier.weight(1f))
                HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
            }

        }
    }
}

@Composable
private fun LightningDescription(
    uiState: SendUiState,
) {
    val isLnurlPay = uiState.lnUrlParameters is LnUrlParameters.LnUrlPay
    val expirySeconds = uiState.decodedInvoice?.expirySeconds
    val description = uiState.decodedInvoice?.description

    Column(modifier = Modifier.fillMaxWidth()) {
        Caption13Up(
            text = stringResource(R.string.wallet__send_invoice),
            color = Colors.White64,
        )
        val destination = when {
            isLnurlPay -> uiState.lnUrlParameters.data.uri
            else -> uiState.decodedInvoice?.bolt11.orEmpty()
        }

        Spacer(modifier = Modifier.height(8.dp))
        BodySSB(text = destination, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        HorizontalDivider(modifier = Modifier.padding(top = 16.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.height(IntrinsicSize.Min)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    .padding(top = 16.dp)
            ) {
                Caption13Up(text = stringResource(R.string.wallet__send_fee_and_speed), color = Colors.White64)
                Spacer(modifier = Modifier.height(8.dp))
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
                    BodySSB(text = "Instant (±$0.01)") // TODO GET FROM STATE
                }
                Spacer(modifier = Modifier.weight(1f))
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            }
            if (!isLnurlPay && expirySeconds != null) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .padding(top = 16.dp)
                ) {
                    Caption13Up(
                        text = stringResource(R.string.wallet__send_invoice_expiration),
                        color = Colors.White64,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
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
                        val invoiceExpiryTimestamp = Instant.now().plusSeconds(expirySeconds.toLong())
                            .formatted(DatePattern.INVOICE_EXPIRY)

                        BodySSB(text = invoiceExpiryTimestamp)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                }
            }
        }

        if (!isLnurlPay && description != null) {
            Column {
                Caption13Up(text = stringResource(R.string.wallet__note), color = Colors.White64)
                Spacer(modifier = Modifier.height(8.dp))
                BodySSB(text = description)
                HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
            }
        }
    }
}

@Preview(name = "Lightning", showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        SendAndReviewContent(
            uiState = SendUiState(
                amount = 1234u,
                address = "",
                payMethod = SendMethod.LIGHTNING,
                decodedInvoice = LightningInvoice(
                    bolt11 = "bolt11_invoice_string",
                    paymentHash = ByteArray(0),
                    amountSatoshis = 100_000u,
                    timestampSeconds = 0u,
                    expirySeconds = 3600u,
                    isExpired = false,
                    networkType = NetworkType.REGTEST,
                    payeeNodeId = null,
                    description = "Some invoice description",
                ),
            ),
            isLoading = false,
            showBiometrics = false,
            onBack = {},
            onEvent = {},
            onClickAddTag = {},
            onClickTag = {},
            onSwipeToConfirm = {},
            onBiometricsSuccess = {},
            onBiometricsFailure = {},
        )
    }
}

@Suppress("SpellCheckingInspection")
@Preview(name = "LnurlPay", showSystemUi = true)
@Composable
private fun PreviewLnurl() {
    AppThemeSurface {
        SendAndReviewContent(
            uiState = SendUiState(
                amount = 1234u,
                address = "bcrt1qkgfgyxyqhvkdqh04sklnzxphmcds6vft6y7h0r",
                payMethod = SendMethod.LIGHTNING,
                lnUrlParameters = LnUrlParameters.LnUrlPay(
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
                decodedInvoice = LightningInvoice(
                    bolt11 = "bcrt123",
                    paymentHash = ByteArray(0),
                    amountSatoshis = 100_000u,
                    timestampSeconds = 0u,
                    expirySeconds = 3600u,
                    isExpired = false,
                    networkType = NetworkType.REGTEST,
                    payeeNodeId = null,
                    description = "Some invoice description",
                ),
            ),
            isLoading = false,
            showBiometrics = false,
            onBack = {},
            onEvent = {},
            onClickAddTag = {},
            onClickTag = {},
            onSwipeToConfirm = {},
            onBiometricsSuccess = {},
            onBiometricsFailure = {},
        )
    }
}

@Suppress("SpellCheckingInspection")
@Preview(name = "OnChain", showSystemUi = true)
@Composable
private fun PreviewOnChain() {
    AppThemeSurface {
        SendAndReviewContent(
            uiState = SendUiState(
                amount = 1234u,
                address = "bcrt1qkgfgyxyqhvkdqh04sklnzxphmcds6vft6y7h0r",
                payMethod = SendMethod.ONCHAIN,
                selectedTags = listOf("car", "house", "uber"),
                decodedInvoice = null,
            ),
            isLoading = false,
            showBiometrics = false,
            onBack = {},
            onEvent = {},
            onClickAddTag = {},
            onClickTag = {},
            onSwipeToConfirm = {},
            onBiometricsSuccess = {},
            onBiometricsFailure = {},
        )
    }
}

@Suppress("SpellCheckingInspection")
@Preview(showSystemUi = true)
@Composable
private fun PreviewBio() {
    AppThemeSurface {
        SendAndReviewContent(
            uiState = SendUiState(
                amount = 1234u,
                address = "bcrt1qkgfgyxyqhvkdqh04sklnzxphmcds6vft6y7h0r",
                payMethod = SendMethod.ONCHAIN,
                selectedTags = listOf("car", "house", "uber"),
                decodedInvoice = null,
            ),
            isLoading = false,
            showBiometrics = true,
            onBack = {},
            onEvent = {},
            onClickAddTag = {},
            onClickTag = {},
            onSwipeToConfirm = {},
            onBiometricsSuccess = {},
            onBiometricsFailure = {},
        )
    }
}

@Suppress("SpellCheckingInspection")
@Preview(showSystemUi = true)
@Composable
private fun PreviewDialog() {
    AppThemeSurface {
        SendAndReviewContent(
            uiState = SendUiState(
                amount = 1234u,
                address = "bcrt1qkgfgyxyqhvkdqh04sklnzxphmcds6vft6y7h0r",
                payMethod = SendMethod.ONCHAIN,
                selectedTags = listOf("car", "house", "uber"),
                decodedInvoice = null,
                showAmountWarningDialog = AmountWarning.VALUE_OVER_100_USD,
            ),
            isLoading = false,
            showBiometrics = true,
            onBack = {},
            onEvent = {},
            onClickAddTag = {},
            onClickTag = {},
            onSwipeToConfirm = {},
            onBiometricsSuccess = {},
            onBiometricsFailure = {},
        )
    }
}

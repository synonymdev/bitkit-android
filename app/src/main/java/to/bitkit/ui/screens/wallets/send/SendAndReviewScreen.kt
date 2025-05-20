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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ext.DatePattern
import to.bitkit.ext.ellipsisMiddle
import to.bitkit.ext.formatted
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.components.BiometricsView
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SwipeToConfirm
import to.bitkit.ui.components.TagButton
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.rememberBiometricAuthSupported
import to.bitkit.viewmodels.SendEvent
import to.bitkit.viewmodels.SendMethod
import to.bitkit.viewmodels.SendUiState
import uniffi.bitkitcore.LightningInvoice
import uniffi.bitkitcore.NetworkType
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

    val app = appViewModel ?: return
    val isPinEnabled by app.isPinEnabled.collectAsStateWithLifecycle()
    val pinForPayments by app.isPinForPaymentsEnabled.collectAsStateWithLifecycle()
    val isBiometricEnabled by app.isBiometricEnabled.collectAsStateWithLifecycle()
    val isBiometrySupported = rememberBiometricAuthSupported()

    LaunchedEffect(savedStateHandle) {
        savedStateHandle.getStateFlow<Boolean?>(PIN_CHECK_RESULT_KEY, null)
            .filterNotNull()
            .collect {
                isLoading = it
                savedStateHandle.remove<Boolean>(PIN_CHECK_RESULT_KEY)
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
                if (isPinEnabled && pinForPayments) {
                    if (isBiometricEnabled && isBiometrySupported) {
                        showBiometrics = true
                    } else {
                        onNavigateToPin()
                    }
                } else {
                    onEvent(SendEvent.SwipeToPay)
                }
            }
        },
        onBiometricsSuccess = {
            isLoading = true
            showBiometrics = false
            onEvent(SendEvent.SwipeToPay)
        },
        onBiometricsFailure = {
            isLoading = false
            showBiometrics = false
            onNavigateToPin()
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
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
        Column(modifier = Modifier.fillMaxSize()) {
            SheetTopBar(stringResource(R.string.wallet__send_review)) {
                onBack()
            }

            Spacer(Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
            ) {
                BalanceHeaderView(sats = uiState.amount.toLong(), modifier = Modifier.fillMaxWidth())

                Spacer(modifier = Modifier.height(16.dp))

                when (uiState.payMethod) {
                    SendMethod.ONCHAIN -> OnChainDescription(uiState = uiState, onEvent = onEvent)
                    SendMethod.LIGHTNING -> LightningDescription(uiState = uiState)
                }

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
                            isSelected = false,
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
                            contentDescription = null,
                            tint = Colors.Brand
                        )
                    },
                    fullWidth = false
                )
                HorizontalDivider(modifier = Modifier.padding(top = 16.dp))

                Spacer(modifier = Modifier.weight(1f))
                SwipeToConfirm(
                    text = stringResource(R.string.wallet__send_swipe),
                    loading = isLoading,
                    confirmed = isLoading,
                    onConfirm = onSwipeToConfirm,
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        if (showBiometrics) {
            BiometricsView(
                onSuccess = onBiometricsSuccess,
                onFailure = onBiometricsFailure,
            )
        }
    }
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
        val destination = uiState.address.ellipsisMiddle(25)
        Spacer(modifier = Modifier.height(8.dp))
        BodySSB(text = destination)
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
                    BodySSB(text = "Normal (₿ 210)") //TODO GET FROM STATE
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
                    .clickable { onEvent(SendEvent.SpeedAndFee) }
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
                    BodySSB(text = "± 20-60 minutes") //TODO GET FROM STATE
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
    Column(modifier = Modifier.fillMaxWidth()) {
        Caption13Up(
            text = stringResource(R.string.wallet__send_invoice),
            color = Colors.White64,
        )
        val destination = uiState.bolt11?.ellipsisMiddle(25).orEmpty()
        Spacer(modifier = Modifier.height(8.dp))
        BodySSB(text = destination)
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
                    BodySSB(text = "Instant (±$0.01)") //TODO GET FROM STATE
                }
                Spacer(modifier = Modifier.weight(1f))
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            }
            uiState.decodedInvoice?.expirySeconds?.let { expirySeconds ->
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .padding(top = 16.dp)
                ) {
                    Caption13Up(
                        text = stringResource(R.string.wallet__send_invoice_expiration),
                        color = Colors.White64
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
                        val invoiceExpiryTimestamp = expirySeconds.let {
                            Instant.now().plusSeconds(it.toLong()).formatted(DatePattern.INVOICE_EXPIRY)
                        }
                        BodySSB(text = invoiceExpiryTimestamp)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                }
            }
        }

        uiState.decodedInvoice?.description?.let { description ->
            Column {
                Caption13Up(text = stringResource(R.string.wallet__note), color = Colors.White64)
                Spacer(modifier = Modifier.height(8.dp))
                BodySSB(text = description)
                HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
            }
        }
    }
}

@Suppress("SpellCheckingInspection")
@Preview(name = "Lightning")
@Composable
private fun Preview() {
    AppThemeSurface {
        SendAndReviewContent(
            uiState = SendUiState(
                amount = 1234uL,
                address = "bcrt1qkgfgyxyqhvkdqh04sklnzxphmcds6vft6y7h0r",
                bolt11 = "lnbcrt1…",
                payMethod = SendMethod.LIGHTNING,
                decodedInvoice = LightningInvoice(
                    bolt11 = "bcrt123",
                    paymentHash = ByteArray(0),
                    amountSatoshis = 100000uL,
                    timestampSeconds = 0uL,
                    expirySeconds = 3600uL,
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
@Preview(name = "OnChain")
@Composable
private fun PreviewOnChain() {
    AppThemeSurface {
        SendAndReviewContent(
            uiState = SendUiState(
                amount = 1234uL,
                address = "bcrt1qkgfgyxyqhvkdqh04sklnzxphmcds6vft6y7h0r",
                bolt11 = "lnbcrt1…",
                payMethod = SendMethod.ONCHAIN,
                selectedTags = listOf("car", "house", "uber"),
                decodedInvoice = LightningInvoice(
                    bolt11 = "bcrt123",
                    paymentHash = ByteArray(0),
                    amountSatoshis = 10000uL,
                    timestampSeconds = 0uL,
                    expirySeconds = 3600uL,
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
@Preview
@Composable
private fun PreviewBio() {
    AppThemeSurface {
        SendAndReviewContent(
            uiState = SendUiState(
                amount = 1234uL,
                address = "bcrt1qkgfgyxyqhvkdqh04sklnzxphmcds6vft6y7h0r",
                bolt11 = "lnbcrt1…",
                payMethod = SendMethod.ONCHAIN,
                selectedTags = listOf("car", "house", "uber"),
                decodedInvoice = LightningInvoice(
                    bolt11 = "bcrt123",
                    paymentHash = ByteArray(0),
                    amountSatoshis = 10000uL,
                    timestampSeconds = 0uL,
                    expirySeconds = 3600uL,
                    isExpired = false,
                    networkType = NetworkType.REGTEST,
                    payeeNodeId = null,
                    description = "Some invoice description",
                ),
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

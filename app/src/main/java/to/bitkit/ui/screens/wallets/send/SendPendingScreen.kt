package to.bitkit.ui.screens.wallets.send

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.filterNotNull
import to.bitkit.R
import to.bitkit.models.WalletScope
import to.bitkit.repositories.OnchainRecoveryFeeRate
import to.bitkit.repositories.OnchainSendAttempt
import to.bitkit.repositories.OnchainSendOutcome
import to.bitkit.repositories.PendingPaymentResolution
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.components.BiometricsView
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.settingsViewModel
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.rememberBiometricAuthSupported

const val RECOVERY_PIN_CHECK_RESULT_KEY = "RECOVERY_PIN_CHECK_RESULT_KEY"

@Composable
fun SendPendingScreen(
    paymentHash: String,
    amount: Long,
    observeResolution: Boolean = true,
    isOnchain: Boolean = false,
    onPaymentSuccess: (String, Long) -> Unit,
    onPaymentError: (PendingPaymentResolution.Failure) -> Unit,
    onClose: () -> Unit,
    onViewDetails: (String) -> Unit,
    viewModel: SendPendingViewModel,
    walletId: String = WalletScope.default,
    refusalReason: String? = null,
    retryOriginal: suspend (OnchainSendAttempt, ULong) -> Result<OnchainSendOutcome>,
    onRecovered: (String, Long) -> Unit,
    savedStateHandle: SavedStateHandle,
    onNavigateToPin: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val txid = paymentHash.takeIf { isOnchain && it.matches(Regex("[0-9a-fA-F]{64}")) }
    LaunchedEffect(Unit) {
        if (isOnchain) {
            viewModel.initOnchain(txid, amount, walletId)
        } else if (observeResolution) {
            viewModel.init(paymentHash, amount)
        }
    }

    uiState.resolution?.takeIf { observeResolution }?.let { resolution ->
        LaunchedEffect(resolution) {
            when (resolution) {
                is PendingPaymentResolution.Success -> onPaymentSuccess(
                    resolution.paymentHash,
                    resolution.amountWithFeeSats ?: amount,
                )
                is PendingPaymentResolution.Failure -> onPaymentError(resolution)
            }
            viewModel.onResolutionHandled()
        }
    }

    uiState.recoveredTxid?.let { winner ->
        LaunchedEffect(winner) { onRecovered(winner, uiState.amount) }
    }
    RecoveryAuthorization(
        uiState,
        savedStateHandle,
        onNavigateToPin,
        { viewModel.retryOriginal(it, retryOriginal) },
    ) { onRetry ->
        SendPendingContent(
            amount = if (observeResolution || isOnchain) uiState.amount else amount,
            isOnchain = isOnchain,
            activityId = uiState.activityId,
            txid = uiState.currentTxid ?: txid,
            refusalReason = refusalReason,
            onClose = onClose,
            onViewDetails = onViewDetails,
            canRetry = uiState.recoveryAttempt != null,
            isRecovering = uiState.isRecovering,
            recoveryError = uiState.recoveryErrorMessage(),
            onRetry = onRetry,
        )
    }
}

@Composable
private fun SendPendingUiState.recoveryErrorMessage(): String? = recoveryError?.let {
    stringResource(
        if (invalidFeeRate) {
            R.string.wallet__send_pending__retry_invalid_fee
        } else {
            R.string.wallet__send_pending__retry_error
        }
    )
}

@Composable
private fun RecoveryAuthorization(
    uiState: SendPendingUiState,
    savedStateHandle: SavedStateHandle,
    onNavigateToPin: () -> Unit,
    retryOriginal: (ULong) -> Unit,
    content: @Composable (() -> Unit) -> Unit,
) {
    var showFeeApproval by remember { mutableStateOf(false) }
    var feeInput by rememberSaveable { mutableStateOf("") }
    var approvedRate by rememberSaveable { mutableStateOf<String?>(null) }
    var showBiometrics by remember { mutableStateOf(false) }
    val settings = settingsViewModel ?: return
    val isPinEnabled by settings.isPinEnabled.collectAsStateWithLifecycle()
    val pinForPayments by settings.isPinForPaymentsEnabled.collectAsStateWithLifecycle()
    val isBiometricEnabled by settings.isBiometricEnabled.collectAsStateWithLifecycle()
    val isBiometrySupported = rememberBiometricAuthSupported()
    fun submitAuthorizedRetry() {
        val rate = approvedRate?.let(OnchainRecoveryFeeRate::parse) ?: return
        approvedRate = null
        retryOriginal(rate)
    }
    LaunchedEffect(savedStateHandle) {
        savedStateHandle.getStateFlow<Boolean?>(RECOVERY_PIN_CHECK_RESULT_KEY, null)
            .filterNotNull().collect { successful ->
                savedStateHandle.remove<Boolean>(RECOVERY_PIN_CHECK_RESULT_KEY)
                if (successful) submitAuthorizedRetry() else approvedRate = null
            }
    }
    if (showBiometrics) {
        BiometricsView(
            onSuccess = {
                showBiometrics = false
                submitAuthorizedRetry()
            },
            onFailure = {
                showBiometrics = false
                onNavigateToPin()
            },
        )
    }
    if (showFeeApproval) {
        uiState.recoveryAttempt?.let { original ->
            RecoveryFeeDialog(original, feeInput, { feeInput = it }, { showFeeApproval = false }) { rate ->
                showFeeApproval = false
                approvedRate = rate.toString()
                if (isPinEnabled && pinForPayments) {
                    if (isBiometricEnabled && isBiometrySupported) showBiometrics = true else onNavigateToPin()
                } else {
                    submitAuthorizedRetry()
                }
            }
        }
    }

    content {
        feeInput = uiState.recoveryAttempt?.feeRateSatsPerVByte?.toString().orEmpty()
        showFeeApproval = true
    }
}

@Composable
private fun RecoveryFeeDialog(
    original: OnchainSendAttempt,
    feeInput: String,
    onFeeChange: (String) -> Unit,
    onClose: () -> Unit,
    onAuthorize: (ULong) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { BodyM(stringResource(R.string.wallet__send_pending__retry_title)) },
        text = {
            Column {
                BodyM(stringResource(R.string.wallet__send_pending__retry_description))
                original.let { original ->
                    VerticalSpacer(16.dp)
                    SelectionContainer { BodyM(original.address, color = Colors.White64) }
                    BalanceHeaderView(sats = original.amountSats.toLong())
                }
                VerticalSpacer(16.dp)
                TextInput(
                    value = feeInput,
                    onValueChange = onFeeChange,
                    placeholder = stringResource(R.string.common__sat_vbyte),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = feeInput.isNotBlank() && OnchainRecoveryFeeRate.parse(feeInput) == null,
                    supportingText = {
                        if (feeInput.isNotBlank() && OnchainRecoveryFeeRate.parse(feeInput) == null) {
                            BodyM(stringResource(R.string.wallet__send_pending__retry_invalid_fee))
                        }
                    },
                )
            }
        },
        confirmButton = {
            PrimaryButton(
                text = stringResource(R.string.wallet__send_pending__retry_authorize),
                enabled = OnchainRecoveryFeeRate.parse(feeInput) != null,
                onClick = {
                    val rate = OnchainRecoveryFeeRate.parse(feeInput) ?: return@PrimaryButton
                    onAuthorize(rate)
                },
            )
        },
        dismissButton = {
            SecondaryButton(
                text = stringResource(R.string.common__cancel),
                onClick = onClose,
            )
        },
    )
}

@Composable
internal fun SendPendingContent(
    amount: Long,
    isOnchain: Boolean,
    activityId: String?,
    onClose: () -> Unit,
    onViewDetails: (String) -> Unit,
    modifier: Modifier = Modifier,
    txid: String? = null,
    refusalReason: String? = null,
    canRetry: Boolean = false,
    isRecovering: Boolean = false,
    recoveryError: String? = null,
    onRetry: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
    ) {
        SheetTopBar(stringResource(R.string.wallet__send_pending__nav_title))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                VerticalSpacer(16.dp)
                BalanceHeaderView(sats = amount, modifier = Modifier.fillMaxWidth())
                recoveryError?.let {
                    VerticalSpacer(16.dp)
                    BodyM(it, color = Colors.White64)
                }

                VerticalSpacer(32.dp)
                BodyM(
                    stringResource(
                        if (isOnchain) {
                            R.string.wallet__send_pending__onchain_description
                        } else {
                            R.string.wallet__send_pending__description
                        },
                    ),
                    color = Colors.White64,
                )

                if (isOnchain) {
                    refusalReason?.let {
                        VerticalSpacer(16.dp)
                        BodyM(stringResource(R.string.wallet__send_pending__refusal, it), color = Colors.White64)
                    }
                    txid?.let {
                        VerticalSpacer(16.dp)
                        SelectionContainer {
                            BodyM(stringResource(R.string.wallet__send_pending__txid, it), color = Colors.White64)
                        }
                    }
                }

                VerticalSpacer(32.dp)
                HourglassAnimation(modifier = Modifier.align(Alignment.CenterHorizontally))
                VerticalSpacer(16.dp)
            }
            VerticalSpacer(16.dp)
            if (canRetry) {
                SecondaryButton(
                    text = stringResource(R.string.wallet__send_pending__retry_title),
                    enabled = !isRecovering,
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                )
                VerticalSpacer(16.dp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SecondaryButton(
                    text = stringResource(R.string.wallet__send_details),
                    enabled = activityId != null,
                    onClick = { activityId?.let(onViewDetails) },
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton(
                    text = stringResource(R.string.common__close),
                    onClick = onClose,
                    modifier = Modifier.weight(1f),
                )
            }
            VerticalSpacer(16.dp)
        }
    }
}

@Composable
private fun HourglassAnimation(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "hourglass")
    val rotation by infiniteTransition.animateFloat(
        initialValue = -16f,
        targetValue = 16f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "hourglassRotation",
    )
    Image(
        painter = painterResource(R.drawable.hourglass),
        contentDescription = null,
        modifier = modifier
            .size(256.dp)
            .rotate(rotation),
    )
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            SendPendingContent(
                amount = 50_000L,
                isOnchain = false,
                activityId = null,
                onClose = {},
                onViewDetails = {},
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}

package to.bitkit.ui.screens.wallets.send

import android.content.Context
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.ActivityFilter
import com.synonym.bitkitcore.PaymentType
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ext.rawId
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.PendingPaymentResolution
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.screens.wallets.send.SendPendingUiState.Resolution
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class SendPendingViewModel @Inject constructor(
    private val lightningRepo: LightningRepo,
    private val activityRepo: ActivityRepo,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    companion object {
        private const val TAG = "SendPendingViewModel"
    }

    private val _uiState = MutableStateFlow(SendPendingUiState())
    val uiState = _uiState.asStateFlow()

    private var isInitialized = false

    fun init(paymentHash: String, amount: Long) {
        if (isInitialized) return
        isInitialized = true
        _uiState.update { it.copy(amount = amount) }
        findActivity(paymentHash)
        observeResolution(paymentHash, amount)
    }

    fun onResolutionHandled() = _uiState.update { it.copy(resolution = null) }

    private fun findActivity(paymentHash: String) {
        viewModelScope.launch {
            activityRepo.findActivityByPaymentId(
                paymentHashOrTxId = paymentHash,
                type = ActivityFilter.LIGHTNING,
                txType = PaymentType.SENT,
                retry = true,
            ).onSuccess {
                _uiState.update { state -> state.copy(activityId = it.rawId()) }
            }.onFailure {
                Logger.error("Failed to find activity", context = TAG)
            }
        }
    }

    private fun observeResolution(paymentHash: String, amount: Long) {
        viewModelScope.launch {
            lightningRepo.pendingPaymentResolution
                .filter {
                    when (it) {
                        is PendingPaymentResolution.Success -> it.paymentHash == paymentHash
                        is PendingPaymentResolution.Failure -> it.paymentHash == paymentHash
                    }
                }
                .collect {
                    _uiState.update { state ->
                        state.copy(
                            resolution = when (it) {
                                is PendingPaymentResolution.Success -> Resolution.Success(
                                    NewTransactionSheetDetails(
                                        type = NewTransactionSheetType.LIGHTNING,
                                        direction = NewTransactionSheetDirection.SENT,
                                        paymentHashOrTxId = it.paymentHash,
                                        sats = amount,
                                    )
                                )

                                is PendingPaymentResolution.Failure -> Resolution.Error(
                                    it.reason ?: context.getString(R.string.wallet__toast_payment_failed_title)
                                )
                            }
                        )
                    }
                }
        }
    }
}

data class SendPendingUiState(
    val amount: Long = 0L,
    val activityId: String? = null,
    val resolution: Resolution? = null,
) {
    sealed interface Resolution {
        data class Success(val details: NewTransactionSheetDetails) : Resolution
        data class Error(val message: String) : Resolution
    }
}

@Composable
fun SendPendingScreen(
    paymentHash: String,
    amount: Long,
    onPaymentSuccess: (NewTransactionSheetDetails) -> Unit,
    onPaymentError: (String) -> Unit,
    onClose: () -> Unit,
    onViewDetails: (String) -> Unit,
    viewModel: SendPendingViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.init(paymentHash, amount) }

    uiState.resolution?.let { resolution ->
        LaunchedEffect(resolution) {
            runCatching {
                when (resolution) {
                    is Resolution.Success -> onPaymentSuccess(resolution.details)
                    is Resolution.Error -> onPaymentError(resolution.message)
                }
            }
            viewModel.onResolutionHandled()
        }
    }

    Content(
        amount = uiState.amount,
        activityId = uiState.activityId,
        onClose = onClose,
        onViewDetails = onViewDetails,
    )
}

@Composable
private fun Content(
    amount: Long,
    activityId: String?,
    onClose: () -> Unit,
    onViewDetails: (String) -> Unit,
    modifier: Modifier = Modifier,
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
            VerticalSpacer(16.dp)
            BalanceHeaderView(sats = amount, modifier = Modifier.fillMaxWidth())

            VerticalSpacer(32.dp)
            BodyM(stringResource(R.string.wallet__send_pending__description))

            FillHeight()
            HourglassAnimation(modifier = Modifier.align(Alignment.CenterHorizontally))
            FillHeight()

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
            Content(
                amount = 50_000L,
                activityId = null,
                onClose = {},
                onViewDetails = {},
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}

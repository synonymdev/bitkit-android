package to.bitkit.ui.sheets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.LightningActivity
import com.synonym.bitkitcore.PaymentState
import com.synonym.bitkitcore.PaymentType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.bitkit.di.BgDispatcher
import to.bitkit.ext.create
import to.bitkit.ext.nowTimestamp
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.GiftClaimResult
import to.bitkit.utils.Logger
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

@HiltViewModel
class GiftViewModel @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val blocktankRepo: BlocktankRepo,
    private val activityRepo: ActivityRepo,
) : ViewModel() {

    private val _navigationEvent = MutableSharedFlow<GiftRoute>(extraBufferCapacity = 1)
    val navigationEvent = _navigationEvent.asSharedFlow()

    private val _successEvent = MutableSharedFlow<NewTransactionSheetDetails>(extraBufferCapacity = 1)
    val successEvent = _successEvent.asSharedFlow()

    private var code: String = ""
    var amount: ULong = 0uL
        private set

    @Volatile
    private var isClaiming: Boolean = false

    fun initialize(code: String, amount: ULong) {
        if (!isClaiming) {
            viewModelScope.launch {
                _navigationEvent.emit(GiftRoute.Loading)
            }
        }
        this.code = code
        this.amount = amount
        viewModelScope.launch(bgDispatcher) {
            claimGift()
        }
    }

    private suspend fun claimGift() = withContext(bgDispatcher) {
        if (isClaiming) return@withContext
        isClaiming = true

        try {
            blocktankRepo.claimGiftCode(
                code = code,
                amount = amount,
                waitTimeout = NODE_STARTUP_TIMEOUT_MS.milliseconds,
            ).fold(
                onSuccess = { result ->
                    when (result) {
                        is GiftClaimResult.SuccessWithLiquidity -> {
                            _navigationEvent.emit(GiftRoute.Success)
                        }
                        is GiftClaimResult.SuccessWithoutLiquidity -> {
                            insertGiftActivity(result)
                            _successEvent.emit(
                                NewTransactionSheetDetails(
                                    type = NewTransactionSheetType.LIGHTNING,
                                    direction = NewTransactionSheetDirection.RECEIVED,
                                    paymentHashOrTxId = result.paymentHashOrTxId,
                                    sats = result.sats,
                                )
                            )
                            _navigationEvent.emit(GiftRoute.Success)
                        }
                    }
                },
                onFailure = { error ->
                    handleGiftClaimError(error)
                }
            )
        } finally {
            isClaiming = false
        }
    }

    private suspend fun insertGiftActivity(result: GiftClaimResult.SuccessWithoutLiquidity) {
        val nowTimestamp = nowTimestamp().epochSecond.toULong()

        val lightningActivity = LightningActivity.create(
            id = result.paymentHashOrTxId,
            txType = PaymentType.RECEIVED,
            status = PaymentState.SUCCEEDED,
            value = result.sats.toULong(),
            invoice = result.invoice,
            timestamp = nowTimestamp,
            message = result.code,
        )

        activityRepo.insertActivity(Activity.Lightning(lightningActivity)).getOrThrow()
    }

    private suspend fun handleGiftClaimError(error: Throwable) {
        Logger.error("Gift claim failed: $error", error, context = TAG)

        val route = when {
            errorContains(error, "GIFT_CODE_ALREADY_USED") -> GiftRoute.Used
            errorContains(error, "GIFT_CODE_USED_UP") -> GiftRoute.UsedUp
            else -> GiftRoute.Error
        }

        _navigationEvent.emit(route)
    }

    private fun errorContains(error: Throwable, text: String): Boolean {
        var currentError: Throwable? = error
        while (currentError != null) {
            val errorText = currentError.toString() + (currentError.message ?: "")
            if (errorText.contains(text, ignoreCase = true)) {
                return true
            }
            currentError = currentError.cause
        }
        return false
    }

    companion object {
        private const val TAG = "GiftViewModel"
        private const val NODE_STARTUP_TIMEOUT_MS = 30_000L
    }
}

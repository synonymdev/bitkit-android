package to.bitkit.ui.screens.wallets.receive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.models.ReceiveAdditionalLiquidityAction
import to.bitkit.models.ReceiveAdditionalLiquidityParams
import to.bitkit.models.ReceiveLiquidityDecision
import to.bitkit.models.ReceiveLiquiditySource
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class EditInvoiceVM @Inject constructor(
    private val walletRepo: WalletRepo,
    private val blocktankRepo: BlocktankRepo,
) : ViewModel() {

    private val _editInvoiceEffect = MutableSharedFlow<EditInvoiceScreenEffects>(extraBufferCapacity = 1)
    val editInvoiceEffect = _editInvoiceEffect.asSharedFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private fun editInvoiceEffect(effect: EditInvoiceScreenEffects) = viewModelScope.launch {
        _editInvoiceEffect.emit(
            effect
        )
    }

    fun onClickContinue(
        source: ReceiveLiquiditySource,
        amountSats: ULong,
        isGeoBlocked: Boolean,
    ) {
        viewModelScope.launch {
            _isLoading.update { true }
            val maxCjitAmountSats = maxCjitAmountSats(source, amountSats, isGeoBlocked)
            val action = ReceiveLiquidityDecision.additionalLiquidityAction(
                ReceiveAdditionalLiquidityParams(
                    source = source,
                    invoiceAmountSats = amountSats,
                    inboundCapacitySats = walletRepo.inboundLiquiditySats(),
                    minCjitSats = blocktankRepo.blocktankState.value.minCjitSats?.toULong(),
                    maxCjitAmountSats = maxCjitAmountSats,
                    isGeoBlocked = isGeoBlocked,
                )
            )
            editInvoiceEffect(EditInvoiceScreenEffects.ApplyReceiveLiquidityAction(action))
            _isLoading.update { false }
        }
    }

    private suspend fun maxCjitAmountSats(
        source: ReceiveLiquiditySource,
        amountSats: ULong,
        isGeoBlocked: Boolean,
    ): ULong? {
        if (!ReceiveLiquidityDecision.needsCjitLimitsForAdditionalLiquidity(
                source = source,
                invoiceAmountSats = amountSats,
                inboundCapacitySats = walletRepo.inboundLiquiditySats(),
                isGeoBlocked = isGeoBlocked,
            )
        ) {
            return null
        }

        blocktankRepo.refreshMinCjitSats()
        return blocktankRepo.maxCjitAmountSats().getOrElse {
            Logger.warn("Failed to calculate max CJIT amount", it, context = TAG)
            null
        }
    }

    sealed interface EditInvoiceScreenEffects {
        data class ApplyReceiveLiquidityAction(
            val action: ReceiveAdditionalLiquidityAction,
        ) : EditInvoiceScreenEffects
    }

    companion object {
        const val TAG = "EditInvoiceVM"
    }
}

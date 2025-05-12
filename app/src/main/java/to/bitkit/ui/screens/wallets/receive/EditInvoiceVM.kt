package to.bitkit.ui.screens.wallets.receive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import to.bitkit.repositories.WalletRepo
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class EditInvoiceVM @Inject constructor(
    val walletRepo: WalletRepo
): ViewModel() {

    private val _editInvoiceEffect = MutableSharedFlow<EditInvoiceScreenEffects>(extraBufferCapacity = 1)
    val editInvoiceEffect = _editInvoiceEffect.asSharedFlow()
    private fun editInvoiceEffect(effect: EditInvoiceScreenEffects) = viewModelScope.launch { _editInvoiceEffect.emit(effect) }

    fun onClickContinue() {
        viewModelScope.launch {
            walletRepo.shouldRequestAdditionalLiquidity().onSuccess { shouldRequest ->
                if (shouldRequest) {
                    editInvoiceEffect(EditInvoiceScreenEffects.NavigateAddLiquidity)
                } else {
                    editInvoiceEffect(EditInvoiceScreenEffects.UpdateInvoice)
                }
            }.onFailure {
                Logger.warn("Error checking for liquidity, navigating back to QR Screen", context = TAG)
                editInvoiceEffect(EditInvoiceScreenEffects.UpdateInvoice)
            }
        }
    }

    sealed interface EditInvoiceScreenEffects {
        data object UpdateInvoice : EditInvoiceScreenEffects
        data object NavigateAddLiquidity : EditInvoiceScreenEffects
    }

    companion object {
        const val TAG = "EditInvoiceVM"
    }
}

package to.bitkit.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import to.bitkit.utils.AddressChecker
import to.bitkit.utils.Logger
import to.bitkit.utils.TxDetails
import javax.inject.Inject

@HiltViewModel
class ActivityDetailViewModel @Inject constructor(
    private val addressChecker: AddressChecker,
) : ViewModel() {
    private val _txDetails = MutableStateFlow<TxDetails?>(null)
    val txDetails = _txDetails.asStateFlow()

    fun fetchTransactionDetails(txid: String) {
        viewModelScope.launch {
            try {
                // TODO replace with bitkit-core method when available
                _txDetails.value = addressChecker.getTransaction(txid)
            } catch (e: Throwable) {
                Logger.error("fetchTransactionDetails error", e, context = TAG)
                _txDetails.value = null
            }
        }
    }

    fun clearTransactionDetails() {
        _txDetails.value = null
    }

    private companion object {
        const val TAG = "ActivityDetailViewModel"
    }
}

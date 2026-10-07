package to.bitkit.ui.screens.wallets.usdt

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.UsdtDeposit
import com.synonym.bitkitcore.UsdtDepositAddress
import com.synonym.bitkitcore.UsdtDepositDetail
import com.synonym.bitkitcore.UsdtDepositNetwork
import com.synonym.bitkitcore.UsdtException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.repositories.UsdtDepositRepo
import javax.inject.Inject

@HiltViewModel
class UsdtDepositViewModel @Inject constructor(private val repo: UsdtDepositRepo) : ViewModel() {
    private val _state = MutableStateFlow(DepositUiState())
    val state = _state.asStateFlow()
    private val pageOffsets = mutableMapOf<String, UInt>()

    init {
        viewModelScope.launch {
            repo.depositNetworks().onSuccess { networks -> _state.update { it.copy(networks = networks) } }
                .onFailure { _state.update { it.copy(error = R.string.usdt__deposit_unavailable) } }
        }
    }

    fun selectNetwork(network: UsdtDepositNetwork?) {
        if (!_state.value.busy) {
            _state.update { it.copy(network = network, address = null, error = null, amountLimits = null) }
        }
    }

    fun clearAmountError() {
        _state.update { it.copy(error = null, amountLimits = null) }
    }

    fun prepare(amount: String) = viewModelScope.launch {
        val network = _state.value.network ?: return@launch
        operation {
            repo.prepareDeposit(network, amount).onSuccess { address -> _state.update { it.copy(address = address) } }
        }
    }

    fun history(offset: UInt = 0u) = viewModelScope.launch {
        operation {
            repo.depositHistory(offset).onSuccess { page ->
                if (offset == 0u) pageOffsets.clear()
                page.deposits.forEach { pageOffsets[it.id] = offset }
                _state.update {
                    it.copy(
                        history = true,
                        detail = null,
                        deposits = (
                            if (offset == 0u) {
                                page.deposits
                            } else {
                                val updatedIds = page.deposits.map { deposit -> deposit.id }.toSet()
                                it.deposits.filterNot { deposit -> deposit.id in updatedIds } + page.deposits
                            }
                            ).toImmutableList(),
                        offset = offset,
                        nextOffset = page.nextOffset,
                        message = null
                    )
                }
            }
        }
    }

    suspend fun detail(id: String) {
        operation {
            repo.depositDetail(
                id,
                pageOffsets[id] ?: _state.value.offset
            ).onSuccess { detail -> _state.update { it.copy(detail = detail, offset = pageOffsets[id] ?: it.offset) } }
        }
    }

    fun back(): Boolean {
        if (_state.value.busy) return true
        return when {
            _state.value.detail != null -> {
                _state.update { it.copy(detail = null, message = null) }
                true
            }
            _state.value.history -> {
                _state.update { it.copy(history = false, error = null, amountLimits = null) }
                true
            }
            else -> false
        }
    }

    fun refund(id: String, offset: UInt, address: String, network: UsdtDepositNetwork) = viewModelScope.launch {
        val detail = _state.value.detail ?: return@launch
        if (detail.deposit.id != id || (detail.order?.status ?: detail.deposit.status) in
            listOf("completed", "refunded", "refunding", "refund_requested")
        ) {
            return@launch
        }
        operation {
            repo.refundDeposit(id, offset, address, network).onSuccess {
                _state.update {
                    it.copy(
                        detail = it.detail?.let { detail ->
                            detail.copy(
                                deposit = detail.deposit.copy(status = "refund_requested", code = null),
                                order = detail.order?.copy(status = "refund_requested", code = null),
                            )
                        },
                        message = R.string.usdt__deposit_refund_requested,
                    )
                }
            }
        }
    }

    private suspend fun operation(block: suspend () -> Result<*>) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, amountLimits = null) }
        try {
            block().onFailure { error ->
                _state.update {
                    it.copy(
                        error = error.messageResource(),
                        amountLimits = error as? UsdtException.DepositAmountOutOfRange,
                    )
                }
            }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }
}

@Stable
data class DepositUiState(
    val networks: ImmutableList<UsdtDepositNetwork> = persistentListOf(),
    val network: UsdtDepositNetwork? = null,
    val address: UsdtDepositAddress? = null,
    val history: Boolean = false,
    val deposits: ImmutableList<UsdtDeposit> = persistentListOf(),
    val offset: UInt = 0u,
    val nextOffset: UInt? = null,
    val detail: UsdtDepositDetail? = null,
    val busy: Boolean = false,
    val error: Int? = null,
    val amountLimits: UsdtException.DepositAmountOutOfRange? = null,
    val message: Int? = null,
)

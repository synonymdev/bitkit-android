package to.bitkit.ui.settings.advanced

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.bitkit.di.BgDispatcher
import to.bitkit.models.AddressModel
import to.bitkit.repositories.WalletRepo
import to.bitkit.utils.AddressChecker
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class AddressViewerViewModel @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val addressChecker: AddressChecker,
    private val walletRepo: WalletRepo,
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadAddresses()
    }

    fun loadAddresses() {
        viewModelScope.launch(bgDispatcher) {
            runCatching {
                _uiState.update { it.copy(isLoading = true) }

                delay(300) // wait for screen transition

                val addresses = walletRepo.getAddresses(
                    isChange = !_uiState.value.showReceiveAddresses,
                ).getOrThrow()

                _uiState.update { currentState ->
                    currentState.copy(
                        addresses = addresses,
                        selectedAddress = addresses.firstOrNull(),
                    )
                }
                loadBalancesForAddresses(addresses)
            }

            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun loadMoreAddresses() {
        if (_uiState.value.isLoading) return

        viewModelScope.launch(bgDispatcher) {
            _uiState.update { it.copy(isLoading = true) }

            runCatching {
                val currentState = _uiState.value
                val nextStartIndex = currentState.addresses.size

                val newAddresses = walletRepo.getAddresses(
                    startIndex = nextStartIndex,
                    isChange = !currentState.showReceiveAddresses,
                ).getOrThrow()

                _uiState.update { currentState ->
                    currentState.copy(addresses = currentState.addresses + newAddresses)
                }
                loadBalancesForAddresses(newAddresses)
            }

            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun refreshBalances() {
        if (_uiState.value.isLoadingBalances) return

        viewModelScope.launch(bgDispatcher) {
            _uiState.update { it.copy(isLoadingBalances = true) }

            runCatching {
                val addresses = _uiState.value.addresses.map { it.address }
                val balances = getBalanceForAddresses(addresses)

                _uiState.update { it.copy(balances = balances) }
            }

            _uiState.update { it.copy(isLoadingBalances = false) }
        }
    }

    fun switchAddressType(isReceiving: Boolean) {
        if (_uiState.value.showReceiveAddresses == isReceiving) return

        viewModelScope.launch(bgDispatcher) {
            _uiState.update { it.copy(showReceiveAddresses = isReceiving, isLoading = true) }

            runCatching {
                val addresses = walletRepo.getAddresses(isChange = !isReceiving)
                    .getOrThrow()

                _uiState.update { currentState ->
                    currentState.copy(
                        addresses = addresses,
                        selectedAddress = addresses.firstOrNull(),
                        balances = emptyMap(), // Clear balances for new address type
                    )
                }

                loadBalancesForAddresses(addresses)
            }

            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun updateSearchText(text: String) = _uiState.update { it.copy(searchText = text) }

    fun selectAddress(address: AddressModel) = _uiState.update { it.copy(selectedAddress = address) }

    private fun loadBalancesForAddresses(newAddresses: List<AddressModel>) {
        if (_uiState.value.isLoadingBalances) return

        viewModelScope.launch(bgDispatcher) {
            val addressStrings = newAddresses.map { it.address }
            val newBalances = getBalanceForAddresses(addressStrings)

            _uiState.update { currentState ->
                val updatedBalances = currentState.balances.toMutableMap()

                newBalances.forEach { (address, balance) ->
                    updatedBalances[address] = balance
                }

                currentState.copy(balances = updatedBalances)
            }
        }
    }

    private suspend fun getBalanceForAddresses(addresses: List<String>): Map<String, Long> {
        return withContext(bgDispatcher) {
            return@withContext coroutineScope {
                addresses
                    .map { address ->
                        async {
                            getBalanceForAddress(address).map { balance ->
                                if (balance > 0) address to balance else null
                            }.getOrNull()
                        }
                    }.awaitAll()
                    .filterNotNull()
                    .toMap()
            }
        }
    }

    suspend fun getBalanceForAddress(address: String): Result<Long> = withContext(bgDispatcher) {
        return@withContext runCatching {
            val utxos = addressChecker.getUtxosForAddress(address)
            val balance = utxos.sumOf { it.value }
            return@runCatching balance
        }.onFailure { e ->
            Logger.error("Error getting balance for address $address", e)
        }
    }
}

data class UiState(
    val addresses: List<AddressModel> = emptyList(),
    val balances: Map<String, Long> = emptyMap(),
    val searchText: String = "",
    val selectedAddress: AddressModel? = null,
    val isLoading: Boolean = false,
    val isLoadingBalances: Boolean = false,
    val showReceiveAddresses: Boolean = true,
)

package to.bitkit.ui.settings.advanced

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.AddressType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.bitkit.data.SettingsStore
import to.bitkit.di.BgDispatcher
import to.bitkit.models.AddressModel
import to.bitkit.models.toAddressType
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.WalletRepo
import javax.inject.Inject

@HiltViewModel
class AddressViewerViewModel @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val settingsStore: SettingsStore,
    private val lightningRepo: LightningRepo,
    private val walletRepo: WalletRepo,
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(bgDispatcher) {
            val selected = settingsStore.data.first().selectedAddressType.toAddressType() ?: AddressType.P2WPKH
            _uiState.update { it.copy(selectedAddressType = selected) }
            loadAddresses()
        }
    }

    fun loadAddresses() {
        viewModelScope.launch(bgDispatcher) {
            _uiState.update {
                it.copy(
                    addresses = persistentListOf(),
                    selectedAddress = null,
                    balances = persistentMapOf(),
                    isLoading = true,
                    loadError = false,
                )
            }

            delay(300) // wait for screen transition

            walletRepo.getAddresses(
                isChange = !_uiState.value.showReceiveAddresses,
                addressType = _uiState.value.selectedAddressType,
            ).onSuccess { addresses ->
                _uiState.update { currentState ->
                    currentState.copy(
                        addresses = addresses.toImmutableList(),
                        selectedAddress = addresses.firstOrNull(),
                        loadError = false,
                    )
                }
                loadBalancesForAddresses(addresses)
            }.onFailure {
                _uiState.update { it.copy(loadError = true) }
            }

            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun loadMoreAddresses() {
        if (_uiState.value.isLoading) return

        viewModelScope.launch(bgDispatcher) {
            _uiState.update { it.copy(isLoading = true, loadError = false) }

            val currentState = _uiState.value
            val nextStartIndex = currentState.addresses.size

            walletRepo.getAddresses(
                startIndex = nextStartIndex,
                isChange = !currentState.showReceiveAddresses,
                addressType = currentState.selectedAddressType,
            ).onSuccess { newAddresses ->
                _uiState.update { currentState ->
                    currentState.copy(
                        addresses = (currentState.addresses + newAddresses).toImmutableList(),
                        loadError = false,
                    )
                }
                loadBalancesForAddresses(newAddresses)
            }.onFailure {
                _uiState.update { it.copy(loadError = true) }
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

                _uiState.update { it.copy(balances = balances.toImmutableMap()) }
            }

            _uiState.update { it.copy(isLoadingBalances = false) }
        }
    }

    fun switchAddressType(isReceiving: Boolean) {
        if (_uiState.value.showReceiveAddresses == isReceiving) return

        viewModelScope.launch(bgDispatcher) {
            _uiState.update {
                it.copy(
                    showReceiveAddresses = isReceiving,
                    addresses = persistentListOf(),
                    selectedAddress = null,
                    balances = persistentMapOf(),
                    isLoading = true,
                    loadError = false,
                )
            }

            walletRepo.getAddresses(
                isChange = !isReceiving,
                addressType = _uiState.value.selectedAddressType,
            ).onSuccess { addresses ->
                _uiState.update { currentState ->
                    currentState.copy(
                        addresses = addresses.toImmutableList(),
                        selectedAddress = addresses.firstOrNull(),
                        balances = persistentMapOf(),
                        loadError = false,
                    )
                }

                loadBalancesForAddresses(addresses)
            }.onFailure {
                _uiState.update { it.copy(loadError = true) }
            }

            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun updateSearchText(text: String) = _uiState.update { it.copy(searchText = text) }

    fun selectAddress(address: AddressModel) = _uiState.update { it.copy(selectedAddress = address) }

    fun selectAddressType(addressType: AddressType) {
        if (_uiState.value.selectedAddressType == addressType) return

        viewModelScope.launch(bgDispatcher) {
            _uiState.update {
                it.copy(
                    selectedAddressType = addressType,
                    addresses = persistentListOf(),
                    selectedAddress = null,
                    balances = persistentMapOf(),
                    isLoading = true,
                    loadError = false,
                )
            }

            walletRepo.getAddresses(
                isChange = !_uiState.value.showReceiveAddresses,
                addressType = addressType,
            ).onSuccess { addresses ->
                _uiState.update { currentState ->
                    currentState.copy(
                        addresses = addresses.toImmutableList(),
                        selectedAddress = addresses.firstOrNull(),
                        balances = persistentMapOf(),
                        loadError = false,
                    )
                }
                loadBalancesForAddresses(addresses)
            }.onFailure {
                _uiState.update { it.copy(loadError = true) }
            }

            _uiState.update { it.copy(isLoading = false) }
        }
    }

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

                currentState.copy(balances = updatedBalances.toImmutableMap())
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

    suspend fun getBalanceForAddress(address: String): Result<Long> =
        lightningRepo.getAddressBalance(address).map { it.toLong() }
}

@Immutable
data class UiState(
    val addresses: ImmutableList<AddressModel> = persistentListOf(),
    val balances: ImmutableMap<String, Long> = persistentMapOf(),
    val searchText: String = "",
    val selectedAddress: AddressModel? = null,
    val isLoading: Boolean = false,
    val isLoadingBalances: Boolean = false,
    val loadError: Boolean = false,
    val showReceiveAddresses: Boolean = true,
    val selectedAddressType: AddressType = AddressType.P2WPKH,
)

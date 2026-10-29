package to.bitkit.ui.screens.wallets.receive

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.di.BgDispatcher
import to.bitkit.repositories.PrimaryNodeRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.ui.utils.primaryErrorMessageRes
import javax.inject.Inject

@HiltViewModel
class PrimaryReceiveViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val primaryNodeRepo: PrimaryNodeRepo,
    private val walletRepo: WalletRepo,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PrimaryReceiveUiState())
    val uiState: StateFlow<PrimaryReceiveUiState> = _uiState.asStateFlow()

    val hasPrimaryNode: Boolean get() = primaryNodeRepo.hasPrimaryNode

    private val _created = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val created = _created.asSharedFlow()

    fun requestQuote(amountSats: ULong) {
        if (amountSats == 0uL) return
        _uiState.update { PrimaryReceiveUiState(amountSats = amountSats, isLoading = true) }
        viewModelScope.launch(bgDispatcher) {
            primaryNodeRepo.quoteReceive(amountSats)
                .onSuccess { quote ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            maxFeeMsat = quote.feeMsat,
                            channelFundingSats = quote.fundingSats,
                            error = null,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            maxFeeMsat = null,
                            error = context.getString(error.primaryErrorMessageRes())
                        )
                    }
                }
        }
    }

    fun retry() = requestQuote(_uiState.value.amountSats)

    fun approve() {
        val state = _uiState.value
        val maxFeeMsat = state.maxFeeMsat ?: return
        if (state.isLoading) return
        _uiState.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch(bgDispatcher) {
            val description = walletRepo.walletState.value.bip21Description
            primaryNodeRepo.createInvoice(state.amountSats, description, maxFeeMsat)
                .mapCatching { walletRepo.setPrimaryReceiveInvoice(it, state.amountSats, description).getOrThrow() }
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false) }
                    _created.emit(Unit)
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            maxFeeMsat = null,
                            error = context.getString(error.primaryErrorMessageRes())
                        )
                    }
                }
        }
    }
}

@Immutable
data class PrimaryReceiveUiState(
    val amountSats: ULong = 0uL,
    val maxFeeMsat: ULong? = null,
    val channelFundingSats: ULong = 0uL,
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    val maxFeeSats: ULong get() = ((maxFeeMsat ?: 0uL) + 999uL) / 1000uL
    val minimumReceivedSats: ULong get() = amountSats - minOf(amountSats, maxFeeSats)
}

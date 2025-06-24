package to.bitkit.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.ChannelDetails
import to.bitkit.repositories.LightningRepo
import to.bitkit.services.filterOpen
import javax.inject.Inject

@HiltViewModel
class LightningConnectionsViewModel @Inject constructor(
    private val lightningRepo: LightningRepo,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LightningConnectionsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        collectState()
    }

    fun collectState() {
        viewModelScope.launch {
            val lightningState = lightningRepo.lightningState.value
            if (!lightningState.nodeLifecycleState.isRunning()) {
                _uiState.update { it.copy(isNodeRunning = false) }
                return@launch
            }

            val balances = lightningRepo.getBalances()
            val channels = lightningRepo.getChannels().orEmpty()

            _uiState.update {
                it.copy(
                    isNodeRunning = true,
                    balances = balances,
                    allChannels = channels,
                    openChannels = channels.filterOpen(),
                    localBalance = calculateLocalBalance(channels),
                    remoteBalance = calculateRemoteBalance(channels),
                )
            }
        }
    }

    private fun calculateLocalBalance(channels: List<ChannelDetails>?): ULong {
        return channels
            ?.filterOpen()
            ?.sumOf { it.outboundCapacityMsat / 1000u }
            ?: 0u
    }

    private fun calculateRemoteBalance(channels: List<ChannelDetails>?): ULong {
        return channels
            ?.filterOpen()
            ?.sumOf { it.inboundCapacityMsat / 1000u }
            ?: 0u
    }
}

data class LightningConnectionsUiState(
    val isNodeRunning: Boolean = false,
    val balances: BalanceDetails? = null,
    val allChannels: List<ChannelDetails> = emptyList(),
    val openChannels: List<ChannelDetails> = emptyList(),
    val localBalance: ULong = 0uL,
    val remoteBalance: ULong = 0uL,
)

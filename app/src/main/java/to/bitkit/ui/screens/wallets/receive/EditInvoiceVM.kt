package to.bitkit.ui.screens.wallets.receive

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.ext.nowMillis
import to.bitkit.models.ReceiveAdditionalLiquidityAction
import to.bitkit.models.ReceiveAdditionalLiquidityParams
import to.bitkit.models.ReceiveLiquidityDecision
import to.bitkit.models.ReceiveLiquiditySource
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.OfflineReceiveRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.services.OfflineReceiveRequest
import to.bitkit.services.OfflineReceiveUnavailable
import to.bitkit.services.PreparedOfflineInvoice
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class EditInvoiceVM @Inject constructor(
    private val walletRepo: WalletRepo,
    private val blocktankRepo: BlocktankRepo,
    private val offlineReceiveRepo: OfflineReceiveRepo,
) : ViewModel() {

    private val _editInvoiceEffect = MutableSharedFlow<EditInvoiceScreenEffects>(extraBufferCapacity = 1)
    val editInvoiceEffect = _editInvoiceEffect.asSharedFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _offlineReceive = MutableStateFlow(OfflineReceiveUiState())
    val offlineReceive = _offlineReceive.asStateFlow()
    private var eligibilityJob: Job? = null
    private var eligibilityRevision = 0L
    private var eligibilitySource: ReceiveLiquiditySource? = null
    private var offlineRequest: OfflineReceiveRequest? = null
    private var preparedInvoice: PreparedOfflineInvoice? = null

    fun refreshOfflineReceive(
        source: ReceiveLiquiditySource,
        amountSats: ULong,
        initialInvoice: PreparedOfflineInvoice? = null,
    ) {
        if (_isLoading.value && eligibilitySource == source && _offlineReceive.value.amountSats == amountSats) return
        eligibilityJob?.cancel()
        val isFirstCheck = eligibilityRevision == 0L
        if (isFirstCheck) preparedInvoice = initialInvoice
        val revision = ++eligibilityRevision
        val previous = _offlineReceive.value
        val keepSelected = eligibilitySource == source && previous.amountSats == amountSats && previous.isSelected
        eligibilitySource = source
        if (offlineRequest?.amountSats != amountSats) offlineRequest = null
        _offlineReceive.update { OfflineReceiveUiState(amountSats = amountSats, isSelected = keepSelected) }
        if (source == ReceiveLiquiditySource.SAVINGS || amountSats == 0uL) return
        val restorePrepared = isFirstCheck && initialInvoice?.amountSats == amountSats
        if (restorePrepared || canReuseOfflineRequest(keepSelected, amountSats)) {
            _offlineReceive.update { it.copy(isAvailable = true, isSelected = true) }
            return
        }
        _offlineReceive.update { it.copy(isChecking = true) }
        eligibilityJob = viewModelScope.launch {
            val available = offlineReceiveRepo.canReceive(amountSats).getOrDefault(false)
            if (revision != eligibilityRevision) return@launch
            _offlineReceive.update {
                it.copy(
                    isAvailable = available,
                    isSelected = keepSelected,
                    isChecking = false,
                )
            }
        }
    }

    private fun canReuseOfflineRequest(keepSelected: Boolean, amountSats: ULong): Boolean {
        if (!keepSelected) return false
        return preparedInvoice?.amountSats == amountSats || offlineRequest?.amountSats == amountSats
    }

    fun selectOfflineReceive(selected: Boolean) {
        _offlineReceive.update { it.copy(isSelected = selected && it.isAvailable) }
    }

    private fun editInvoiceEffect(effect: EditInvoiceScreenEffects) = viewModelScope.launch {
        _editInvoiceEffect.emit(
            effect
        )
    }

    fun onClickContinue(
        source: ReceiveLiquiditySource,
        amountSats: ULong,
        isGeoBlocked: Boolean,
        description: String = "",
    ) {
        if (_isLoading.value || _offlineReceive.value.isChecking) return
        if (_offlineReceive.value.isSelected) {
            prepareOfflineInvoice(source, amountSats, description)
            return
        }
        viewModelScope.launch {
            _isLoading.update { true }
            val inboundCapacitySats = walletRepo.inboundLiquiditySats()
            val maxCjitAmountSats = maxCjitAmountSats(source, amountSats, inboundCapacitySats, isGeoBlocked)
            val action = ReceiveLiquidityDecision.additionalLiquidityAction(
                ReceiveAdditionalLiquidityParams(
                    source = source,
                    invoiceAmountSats = amountSats,
                    inboundCapacitySats = inboundCapacitySats,
                    minCjitSats = blocktankRepo.blocktankState.value.minCjitSats?.toULong(),
                    maxCjitAmountSats = maxCjitAmountSats,
                    isGeoBlocked = isGeoBlocked,
                )
            )
            editInvoiceEffect(EditInvoiceScreenEffects.ApplyReceiveLiquidityAction(action))
            _isLoading.update { false }
        }
    }

    private fun prepareOfflineInvoice(source: ReceiveLiquiditySource, amountSats: ULong, description: String) {
        val revision = eligibilityRevision
        _isLoading.update { true }
        viewModelScope.launch {
            try {
                if (source == ReceiveLiquiditySource.SAVINGS || amountSats != _offlineReceive.value.amountSats) {
                    _editInvoiceEffect.emit(EditInvoiceScreenEffects.OfflineInvoiceFailed(OfflineReceiveUnavailable()))
                    return@launch
                }
                val existing = preparedInvoice?.takeIf {
                    it.amountSats == amountSats && it.description == description && it.expiresAtMillis > nowMillis()
                }
                if (existing != null) {
                    showOfflineInvoice(existing, revision)
                    return@launch
                }
                val request = offlineRequest?.takeIf { it.amountSats == amountSats && it.description == description }
                    ?: OfflineReceiveRequest(UUID.randomUUID().toString(), amountSats, description)
                        .also { offlineRequest = it }
                offlineReceiveRepo.prepareInvoice(request).onSuccess {
                    if (revision == eligibilityRevision) {
                        preparedInvoice = it
                        showOfflineInvoice(it, revision)
                    }
                }.onFailure {
                    if (revision == eligibilityRevision) {
                        _editInvoiceEffect.emit(EditInvoiceScreenEffects.OfflineInvoiceFailed(it))
                    }
                }
            } finally {
                _isLoading.update { false }
            }
        }
    }

    private suspend fun showOfflineInvoice(invoice: PreparedOfflineInvoice, revision: Long) {
        val result = offlineReceiveRepo.showInvoice(invoice)
        if (revision != eligibilityRevision) return
        val effect = result.fold(
            onSuccess = { EditInvoiceScreenEffects.OfflineInvoicePrepared(invoice) },
            onFailure = { EditInvoiceScreenEffects.OfflineInvoiceFailed(it) },
        )
        _editInvoiceEffect.emit(effect)
    }

    private suspend fun maxCjitAmountSats(
        source: ReceiveLiquiditySource,
        amountSats: ULong,
        inboundCapacitySats: ULong,
        isGeoBlocked: Boolean,
    ): ULong? {
        if (!ReceiveLiquidityDecision.needsCjitLimitsForAdditionalLiquidity(
                source = source,
                invoiceAmountSats = amountSats,
                inboundCapacitySats = inboundCapacitySats,
                isGeoBlocked = isGeoBlocked,
            )
        ) {
            return null
        }

        blocktankRepo.refreshMinCjitSats()
        return blocktankRepo.maxCjitAmountSats().getOrNull()
    }

    sealed interface EditInvoiceScreenEffects {
        data class OfflineInvoicePrepared(val invoice: PreparedOfflineInvoice) : EditInvoiceScreenEffects
        data class OfflineInvoiceFailed(val error: Throwable) : EditInvoiceScreenEffects

        data class ApplyReceiveLiquidityAction(
            val action: ReceiveAdditionalLiquidityAction,
        ) : EditInvoiceScreenEffects
    }
}

@Immutable
data class OfflineReceiveUiState(
    val amountSats: ULong = 0uL,
    val isAvailable: Boolean = false,
    val isSelected: Boolean = false,
    val isChecking: Boolean = false,
)

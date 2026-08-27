package to.bitkit.ui.screens.wallets.receive

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import to.bitkit.R
import to.bitkit.ext.isTrezorDeviceBusy
import to.bitkit.ext.isTrezorFirmwareError
import to.bitkit.ext.isTrezorUserCancellation
import to.bitkit.models.HwReceiveAddress
import to.bitkit.models.Toast
import to.bitkit.repositories.HwPassphraseMismatchError
import to.bitkit.repositories.HwPassphraseRequiredError
import to.bitkit.repositories.HwReceiveAddressMismatchError
import to.bitkit.repositories.HwWalletRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

@HiltViewModel
class HwReceiveViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hwWalletRepo: HwWalletRepo,
) : ViewModel() {
    private companion object {
        val VERIFY_TIMEOUT = 120.seconds
    }

    val wallets = hwWalletRepo.wallets

    private val _uiState = MutableStateFlow(HwReceiveUiState())
    val uiState = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var addressUpdatesJob: Job? = null
    private var verifyJob: Job? = null
    private var passphraseJob: Job? = null

    fun loadAddress(walletId: String) {
        val state = _uiState.value
        if (state.walletId == walletId && (state.address != null || state.isLoadingAddress)) return
        loadJob?.cancel()
        addressUpdatesJob?.cancel()
        _uiState.update { HwReceiveUiState(walletId = walletId, isLoadingAddress = true) }
        addressUpdatesJob = viewModelScope.launch {
            hwWalletRepo.observeReceiveAddress(walletId).collect { address ->
                if (address != null && _uiState.value.walletId == walletId) {
                    if (_uiState.value.address != null && _uiState.value.address != address) {
                        invalidateVerification()
                    }
                    _uiState.update {
                        it.copy(address = address, isLoadingAddress = false, addressLoadFailed = false)
                    }
                }
            }
        }
        loadJob = viewModelScope.launch {
            hwWalletRepo.getReceiveAddress(walletId)
                .onSuccess { address ->
                    if (_uiState.value.walletId == walletId) {
                        _uiState.update { it.copy(address = address, isLoadingAddress = false) }
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    if (_uiState.value.walletId == walletId) {
                        _uiState.update { it.copy(isLoadingAddress = false, addressLoadFailed = true) }
                    }
                }
        }
    }

    fun retryAddress() {
        val walletId = _uiState.value.walletId ?: return
        _uiState.update { it.copy(address = null, addressLoadFailed = false) }
        loadAddress(walletId)
    }

    fun verifyAddress() {
        val state = _uiState.value
        val walletId = state.walletId ?: return
        val address = state.address ?: return
        if (state.isVerifyingAddress || verifyJob?.isActive == true) return

        _uiState.update { it.copy(isVerifyingAddress = true) }
        verifyJob = viewModelScope.launch {
            try {
                if (hwWalletRepo.needsPassphrase(walletId)) {
                    _uiState.update { it.copy(isPassphraseRequired = true) }
                    return@launch
                }
                runCatching {
                    withTimeout(VERIFY_TIMEOUT) {
                        hwWalletRepo.verifyReceiveAddress(walletId, address).getOrThrow()
                    }
                }.onFailure {
                    if (it is CancellationException && it !is TimeoutCancellationException) throw it
                    handleVerifyFailure(it)
                }
            } finally {
                _uiState.update { it.copy(isVerifyingAddress = false) }
                verifyJob = null
            }
        }
    }

    fun submitPassphrase(passphrase: String) {
        val state = _uiState.value
        val walletId = state.walletId ?: return
        if (passphrase.isEmpty() || !state.isPassphraseRequired || state.isVerifyingPassphrase) return

        _uiState.update { it.copy(isVerifyingPassphrase = true) }
        passphraseJob = viewModelScope.launch {
            try {
                hwWalletRepo.reconnectWithPassphrase(walletId, passphrase)
                    .onSuccess {
                        _uiState.update { it.copy(isPassphraseRequired = false) }
                        verifyAddress()
                    }
                    .onFailure { error ->
                        if (error is HwPassphraseMismatchError) {
                            ToastEventBus.send(
                                type = Toast.ToastType.ERROR,
                                title = context.getString(R.string.common__error),
                                description = context.getString(R.string.hardware__passphrase_mismatch),
                            )
                        } else {
                            handleVerifyFailure(error)
                        }
                    }
            } finally {
                _uiState.update { it.copy(isVerifyingPassphrase = false) }
                passphraseJob = null
            }
        }
    }

    fun dismissPassphrase() {
        passphraseJob?.cancel()
        passphraseJob = null
        _uiState.update { it.copy(isPassphraseRequired = false, isVerifyingPassphrase = false) }
    }

    fun cancel() {
        loadJob?.cancel()
        addressUpdatesJob?.cancel()
        verifyJob?.cancel()
        passphraseJob?.cancel()
        loadJob = null
        addressUpdatesJob = null
        verifyJob = null
        passphraseJob = null
        _uiState.update { HwReceiveUiState() }
    }

    private fun invalidateVerification() {
        verifyJob?.cancel()
        passphraseJob?.cancel()
        _uiState.update { it.copy(isPassphraseRequired = false) }
    }

    private suspend fun handleVerifyFailure(error: Throwable) {
        when {
            error.isTrezorUserCancellation() -> Unit
            generateSequence(error) { it.cause }.any { it is HwPassphraseRequiredError } -> {
                _uiState.update { it.copy(isPassphraseRequired = true) }
            }
            error.isTrezorDeviceBusy() -> ToastEventBus.send(
                type = Toast.ToastType.INFO,
                title = context.getString(R.string.hardware__device_busy),
            )
            error.isTrezorFirmwareError() -> ToastEventBus.send(
                type = Toast.ToastType.ERROR,
                title = context.getString(R.string.common__error),
                description = context.getString(R.string.hardware__connect_error),
            )
            error is TimeoutCancellationException -> ToastEventBus.send(
                type = Toast.ToastType.ERROR,
                title = context.getString(R.string.common__error),
                description = context.getString(R.string.wallet__payment_timeout),
            )
            error is HwReceiveAddressMismatchError -> ToastEventBus.send(
                type = Toast.ToastType.ERROR,
                title = context.getString(R.string.common__error),
                description = context.getString(R.string.hardware__verify_address_error),
            )
            else -> ToastEventBus.send(error)
        }
    }
}

@Immutable
data class HwReceiveUiState(
    val walletId: String? = null,
    val address: HwReceiveAddress? = null,
    val isLoadingAddress: Boolean = false,
    val addressLoadFailed: Boolean = false,
    val isVerifyingAddress: Boolean = false,
    val isPassphraseRequired: Boolean = false,
    val isVerifyingPassphrase: Boolean = false,
)

package to.bitkit.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.AccountInfoResult
import com.synonym.bitkitcore.SingleAddressInfoResult
import com.synonym.bitkitcore.TrezorCoinType
import com.synonym.bitkitcore.TrezorScriptType
import com.synonym.bitkitcore.TrezorTxInput
import com.synonym.bitkitcore.TrezorTxOutput
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.models.Toast
import to.bitkit.models.toTrezorCoinType
import to.bitkit.repositories.KnownDevice
import to.bitkit.repositories.TrezorRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import javax.inject.Inject

data class TrezorUiState(
    val selectedNetwork: TrezorCoinType = Env.network.toTrezorCoinType(),
    val addressIndex: Int = 0,
    val derivationPath: String =
        "m/84'/${if (Env.network.toTrezorCoinType() == TrezorCoinType.BITCOIN) "0" else "1"}'/0'/0/0",
    val messageToSign: String = "Hello, Trezor!",
    val lastSignature: String? = null,
    val lastSigningAddress: String? = null,
    val isSigningMessage: Boolean = false,
    val isGettingAddress: Boolean = false,
    val isGettingPublicKey: Boolean = false,
    val isVerifyingMessage: Boolean = false,
    val lookupInput: String = "",
    val isLookingUp: Boolean = false,
    val accountInfoResult: AccountInfoResult? = null,
    val addressInfoResult: SingleAddressInfoResult? = null,
)

@Suppress("TooManyFunctions")
@HiltViewModel
class TrezorViewModel @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val trezorRepo: TrezorRepo,
) : ViewModel() {

    init {
        trezorRepo.observeExternalDisconnects(viewModelScope)
    }

    val trezorState = trezorRepo.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), trezorRepo.state.value)

    /**
     * Flow indicating when a pairing code is needed.
     * UI should show a dialog when this is true.
     */
    val needsPairingCode = trezorRepo.needsPairingCode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _uiState = MutableStateFlow(TrezorUiState())
    val uiState = _uiState.asStateFlow()

    fun hasKnownDevices(): Boolean = trezorRepo.hasKnownDevices()

    fun autoReconnect() {
        viewModelScope.launch(bgDispatcher) {
            trezorRepo.autoReconnect()
                .onSuccess {
                    val label = it.label ?: it.model ?: "Trezor"
                    ToastEventBus.send(type = Toast.ToastType.INFO, title = "Reconnected to $label")
                }
        }
    }

    fun initialize() {
        viewModelScope.launch(bgDispatcher) {
            trezorRepo.initialize()
                .onSuccess {
                    ToastEventBus.send(type = Toast.ToastType.INFO, title = "Trezor initialized")
                }
                .onFailure { ToastEventBus.send(it) }
        }
    }

    fun scan() {
        viewModelScope.launch(bgDispatcher) {
            trezorRepo.scan()
                .onSuccess { devices ->
                    val count = devices.size
                    ToastEventBus.send(
                        type = Toast.ToastType.INFO,
                        title = "Found $count device${if (count != 1) "s" else ""}"
                    )
                }
                .onFailure { ToastEventBus.send(it) }
        }
    }

    fun connect(deviceId: String) {
        viewModelScope.launch(bgDispatcher) {
            trezorRepo.connect(deviceId)
                .onSuccess { features ->
                    val label = features.label ?: features.model ?: "Trezor"
                    ToastEventBus.send(type = Toast.ToastType.INFO, title = "Connected to $label")
                }
                .onFailure { ToastEventBus.send(it) }
        }
    }

    fun connectKnownDevice(deviceId: String) {
        viewModelScope.launch(bgDispatcher) {
            trezorRepo.connectKnownDevice(deviceId)
                .onSuccess { features ->
                    val label = features.label ?: features.model ?: "Trezor"
                    ToastEventBus.send(type = Toast.ToastType.INFO, title = "Connected to $label")
                }
                .onFailure { ToastEventBus.send(it) }
        }
    }

    fun forgetDevice(device: KnownDevice) {
        viewModelScope.launch(bgDispatcher) {
            val name = device.label ?: device.name ?: "device"
            trezorRepo.forgetDevice(device.id)
                .onSuccess {
                    ToastEventBus.send(type = Toast.ToastType.INFO, title = "Forgot $name")
                }
                .onFailure { ToastEventBus.send(it) }
        }
    }

    fun getAddress(showOnTrezor: Boolean = false) {
        viewModelScope.launch(bgDispatcher) {
            _uiState.update { it.copy(isGettingAddress = true) }
            val state = _uiState.value
            trezorRepo.getAddress(
                path = state.derivationPath,
                showOnTrezor = showOnTrezor,
                scriptType = TrezorScriptType.SPEND_WITNESS,
                coin = state.selectedNetwork,
            )
                .onSuccess {
                    _uiState.update { it.copy(isGettingAddress = false) }
                    ToastEventBus.send(type = Toast.ToastType.INFO, title = "Address generated")
                }
                .onFailure {
                    _uiState.update { it.copy(isGettingAddress = false) }
                    ToastEventBus.send(it)
                }
        }
    }

    fun getPublicKey(showOnTrezor: Boolean = false) {
        viewModelScope.launch(bgDispatcher) {
            _uiState.update { it.copy(isGettingPublicKey = true) }
            val state = _uiState.value
            val accountPath = state.derivationPath.split("/").take(4).joinToString("/")
            trezorRepo.getPublicKey(
                path = accountPath,
                showOnTrezor = showOnTrezor,
                coin = state.selectedNetwork,
            )
                .onSuccess {
                    _uiState.update { it.copy(isGettingPublicKey = false) }
                    ToastEventBus.send(type = Toast.ToastType.INFO, title = "Public key retrieved")
                }
                .onFailure {
                    _uiState.update { it.copy(isGettingPublicKey = false) }
                    ToastEventBus.send(it)
                }
        }
    }

    fun setDerivationPath(path: String) {
        _uiState.update { it.copy(derivationPath = path) }
    }

    fun setSelectedNetwork(network: TrezorCoinType) {
        val coinType = if (network == TrezorCoinType.BITCOIN) "0" else "1"
        _uiState.update {
            it.copy(
                selectedNetwork = network,
                addressIndex = 0,
                derivationPath = "m/84'/$coinType'/0'/0/0",
            )
        }
    }

    fun incrementAddressIndex() {
        _uiState.update { state ->
            val newIndex = state.addressIndex + 1
            val coinType = if (state.selectedNetwork == TrezorCoinType.BITCOIN) "0" else "1"
            state.copy(
                addressIndex = newIndex,
                derivationPath = "m/84'/$coinType'/0'/0/$newIndex",
            )
        }
    }

    fun disconnect() {
        viewModelScope.launch(bgDispatcher) {
            trezorRepo.disconnect()
                .onSuccess {
                    ToastEventBus.send(type = Toast.ToastType.INFO, title = "Disconnected")
                }
                .onFailure { ToastEventBus.send(it) }
        }
    }

    fun setMessageToSign(message: String) {
        _uiState.update { it.copy(messageToSign = message) }
    }

    fun setLookupInput(input: String) {
        _uiState.update { it.copy(lookupInput = input) }
    }

    fun lookupBalanceInfo() {
        viewModelScope.launch(bgDispatcher) {
            val input = _uiState.value.lookupInput.trim()
            if (input.isBlank()) {
                ToastEventBus.send(type = Toast.ToastType.ERROR, title = "Enter an address or xpub")
                return@launch
            }
            _uiState.update { it.copy(isLookingUp = true, accountInfoResult = null, addressInfoResult = null) }

            val network = _uiState.value.selectedNetwork
            if (isExtendedKey(input)) {
                trezorRepo.getAccountInfo(extendedKey = input, network = network)
                    .onSuccess { result ->
                        _uiState.update { it.copy(isLookingUp = false, accountInfoResult = result) }
                        ToastEventBus.send(type = Toast.ToastType.INFO, title = "Account info retrieved")
                    }
                    .onFailure {
                        _uiState.update { it.copy(isLookingUp = false) }
                        ToastEventBus.send(it)
                    }
            } else {
                trezorRepo.getAddressInfo(address = input, network = network)
                    .onSuccess { result ->
                        _uiState.update { it.copy(isLookingUp = false, addressInfoResult = result) }
                        ToastEventBus.send(type = Toast.ToastType.INFO, title = "Address info retrieved")
                    }
                    .onFailure {
                        _uiState.update { it.copy(isLookingUp = false) }
                        ToastEventBus.send(it)
                    }
            }
        }
    }

    private fun isExtendedKey(input: String): Boolean {
        val prefixes = listOf("xpub", "ypub", "zpub", "tpub", "upub", "vpub")
        return prefixes.any { input.lowercase().startsWith(it) }
    }

    fun signMessage() {
        viewModelScope.launch(bgDispatcher) {
            val message = _uiState.value.messageToSign
            if (message.isBlank()) {
                ToastEventBus.send(type = Toast.ToastType.ERROR, title = "Message cannot be empty")
                return@launch
            }

            _uiState.update { it.copy(isSigningMessage = true) }
            val state = _uiState.value
            trezorRepo.signMessage(path = state.derivationPath, message = message, coin = state.selectedNetwork)
                .onSuccess { response ->
                    _uiState.update {
                        it.copy(
                            lastSignature = response.signature,
                            lastSigningAddress = response.address,
                            isSigningMessage = false
                        )
                    }
                    ToastEventBus.send(type = Toast.ToastType.INFO, title = "Message signed!")
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isSigningMessage = false) }
                    ToastEventBus.send(e)
                }
        }
    }

    fun verifyMessage() {
        viewModelScope.launch(bgDispatcher) {
            val signature = _uiState.value.lastSignature
            val message = _uiState.value.messageToSign
            val address = _uiState.value.lastSigningAddress

            if (signature == null || address == null) {
                ToastEventBus.send(type = Toast.ToastType.ERROR, title = "Sign a message first")
                return@launch
            }

            _uiState.update { it.copy(isVerifyingMessage = true) }
            trezorRepo.verifyMessage(
                address = address,
                signature = signature,
                message = message,
                coin = _uiState.value.selectedNetwork,
            )
                .onSuccess { isValid ->
                    _uiState.update { it.copy(isVerifyingMessage = false) }
                    val msg = if (isValid) "Signature is valid!" else "Signature is invalid"
                    val type = if (isValid) Toast.ToastType.SUCCESS else Toast.ToastType.ERROR
                    ToastEventBus.send(type = type, title = msg)
                }
                .onFailure {
                    _uiState.update { it.copy(isVerifyingMessage = false) }
                    ToastEventBus.send(it)
                }
        }
    }

    fun clearError() {
        trezorRepo.clearError()
    }

    /**
     * Submit the pairing code entered by the user.
     */
    fun submitPairingCode(code: String) {
        trezorRepo.submitPairingCode(code)
    }

    /**
     * Cancel pairing code entry.
     */
    fun cancelPairingCode() {
        trezorRepo.cancelPairingCode()
    }

    /**
     * Sign a Bitcoin transaction.
     */
    fun signTx(
        inputs: List<TrezorTxInput>,
        outputs: List<TrezorTxOutput>,
        coin: TrezorCoinType = TrezorCoinType.BITCOIN,
        lockTime: UInt? = null,
        version: UInt? = null,
    ) {
        viewModelScope.launch(bgDispatcher) {
            trezorRepo.signTx(inputs, outputs, coin, lockTime, version)
                .onSuccess { signedTx ->
                    ToastEventBus.send(
                        type = Toast.ToastType.SUCCESS,
                        title = "Transaction signed (${signedTx.signatures.size} inputs)"
                    )
                }
                .onFailure { ToastEventBus.send(it) }
        }
    }

    /**
     * Clear stored pairing credentials for a device.
     */
    fun clearCredentials(deviceId: String) {
        viewModelScope.launch(bgDispatcher) {
            trezorRepo.clearCredentials(deviceId)
                .onSuccess {
                    ToastEventBus.send(type = Toast.ToastType.INFO, title = "Credentials cleared")
                }
                .onFailure { ToastEventBus.send(it) }
        }
    }
}

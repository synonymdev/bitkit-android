package to.bitkit.ui.screens.trezor

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.AccountInfoResult
import com.synonym.bitkitcore.CoinSelection
import com.synonym.bitkitcore.ComposeOutput
import com.synonym.bitkitcore.ComposeResult
import com.synonym.bitkitcore.SingleAddressInfoResult
import com.synonym.bitkitcore.TransactionHistoryResult
import com.synonym.bitkitcore.TrezorScriptType
import com.synonym.bitkitcore.TrezorSignedTx
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
import to.bitkit.models.toCoreNetwork
import to.bitkit.models.toTrezorCoinType
import to.bitkit.repositories.KnownDevice
import to.bitkit.repositories.TrezorRepo
import to.bitkit.services.TrezorDebugLog
import to.bitkit.ui.shared.toast.ToastEventBus
import javax.inject.Inject
import com.synonym.bitkitcore.Network as BitkitCoreNetwork

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
                .onFailure { ToastEventBus.send(it) }
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
            _uiState.update { it.copy(network = it.network.copy(isGettingAddress = true)) }
            val state = _uiState.value
            trezorRepo.getAddress(
                path = state.derivationPath,
                showOnTrezor = showOnTrezor,
                scriptType = TrezorScriptType.SPEND_WITNESS,
                coin = state.selectedNetwork.toTrezorCoinType(),
            )
                .onSuccess {
                    _uiState.update { it.copy(network = it.network.copy(isGettingAddress = false)) }
                    ToastEventBus.send(type = Toast.ToastType.INFO, title = "Address generated")
                }
                .onFailure {
                    _uiState.update { it.copy(network = it.network.copy(isGettingAddress = false)) }
                    ToastEventBus.send(it)
                }
        }
    }

    fun getPublicKey(showOnTrezor: Boolean = false) {
        viewModelScope.launch(bgDispatcher) {
            _uiState.update { it.copy(network = it.network.copy(isGettingPublicKey = true)) }
            val state = _uiState.value
            trezorRepo.getPublicKey(
                path = accountPath(state.derivationPath),
                showOnTrezor = showOnTrezor,
                coin = state.selectedNetwork.toTrezorCoinType(),
            )
                .onSuccess {
                    _uiState.update { it.copy(network = it.network.copy(isGettingPublicKey = false)) }
                    ToastEventBus.send(type = Toast.ToastType.INFO, title = "Public key retrieved")
                }
                .onFailure {
                    _uiState.update { it.copy(network = it.network.copy(isGettingPublicKey = false)) }
                    ToastEventBus.send(it)
                }
        }
    }

    fun setDerivationPath(path: String) {
        _uiState.update { it.copy(network = it.network.copy(derivationPath = path)) }
    }

    fun setSelectedNetwork(network: BitkitCoreNetwork) {
        _uiState.update {
            it.copy(
                network = it.network.copy(
                    selectedNetwork = network,
                    addressIndex = 0,
                    derivationPath = derivationPath(network = network, index = 0),
                )
            )
        }
    }

    fun incrementAddressIndex() {
        _uiState.update { state ->
            val newIndex = state.addressIndex + 1
            state.copy(
                network = state.network.copy(
                    addressIndex = newIndex,
                    derivationPath = derivationPath(network = state.selectedNetwork, index = newIndex),
                )
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
        _uiState.update { it.copy(message = it.message.copy(messageToSign = message)) }
    }

    fun setLookupInput(input: String) {
        _uiState.update { it.copy(lookup = it.lookup.copy(input = input)) }
    }

    fun lookupBalanceInfo() {
        viewModelScope.launch(bgDispatcher) {
            val input = _uiState.value.lookupInput.trim()
            if (input.isBlank()) {
                ToastEventBus.send(type = Toast.ToastType.ERROR, title = "Enter an address or xpub")
                return@launch
            }
            _uiState.update {
                it.copy(
                    lookup = it.lookup.copy(
                        isLookingUp = true,
                        accountInfoResult = null,
                        addressInfoResult = null,
                    ),
                    send = TrezorSendState(),
                )
            }

            val network = _uiState.value.selectedNetwork
            if (isExtendedKey(input)) {
                trezorRepo.getAccountInfo(extendedKey = input, network = network)
                    .onSuccess { result ->
                        _uiState.update {
                            it.copy(
                                lookup = it.lookup.copy(
                                    isLookingUp = false,
                                    accountInfoResult = result,
                                )
                            )
                        }
                        ToastEventBus.send(type = Toast.ToastType.INFO, title = "Account info retrieved")
                    }
                    .onFailure {
                        _uiState.update { it.copy(lookup = it.lookup.copy(isLookingUp = false)) }
                        ToastEventBus.send(it)
                    }
            } else {
                trezorRepo.getAddressInfo(address = input, network = network)
                    .onSuccess { result ->
                        _uiState.update {
                            it.copy(
                                lookup = it.lookup.copy(
                                    isLookingUp = false,
                                    addressInfoResult = result,
                                )
                            )
                        }
                        ToastEventBus.send(type = Toast.ToastType.INFO, title = "Address info retrieved")
                    }
                    .onFailure {
                        _uiState.update { it.copy(lookup = it.lookup.copy(isLookingUp = false)) }
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

            _uiState.update { it.copy(message = it.message.copy(isSigningMessage = true)) }
            val state = _uiState.value
            trezorRepo.signMessage(
                path = state.derivationPath,
                message = message,
                coin = state.selectedNetwork.toTrezorCoinType()
            )
                .onSuccess { response ->
                    _uiState.update {
                        it.copy(
                            message = it.message.copy(
                                lastSignature = response.signature,
                                lastSigningAddress = response.address,
                                isSigningMessage = false,
                            )
                        )
                    }
                    ToastEventBus.send(type = Toast.ToastType.INFO, title = "Message signed!")
                }
                .onFailure { e ->
                    _uiState.update { it.copy(message = it.message.copy(isSigningMessage = false)) }
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

            _uiState.update { it.copy(message = it.message.copy(isVerifyingMessage = true)) }
            trezorRepo.verifyMessage(
                address = address,
                signature = signature,
                message = message,
                coin = _uiState.value.selectedNetwork.toTrezorCoinType(),
            )
                .onSuccess { isValid ->
                    _uiState.update { it.copy(message = it.message.copy(isVerifyingMessage = false)) }
                    val msg = if (isValid) "Signature is valid!" else "Signature is invalid"
                    val type = if (isValid) Toast.ToastType.SUCCESS else Toast.ToastType.ERROR
                    ToastEventBus.send(type = type, title = msg)
                }
                .onFailure {
                    _uiState.update { it.copy(message = it.message.copy(isVerifyingMessage = false)) }
                    ToastEventBus.send(it)
                }
        }
    }

    fun setSendAddress(address: String) {
        _uiState.update { it.copy(send = it.send.copy(address = address)) }
    }

    fun setSendAmount(amount: String) {
        _uiState.update { it.copy(send = it.send.copy(amountSats = amount)) }
    }

    fun setSendFeeRate(feeRate: String) {
        _uiState.update { it.copy(send = it.send.copy(feeRate = feeRate)) }
    }

    fun toggleSendMax() {
        _uiState.update { it.copy(send = it.send.copy(isMax = !it.isSendMax)) }
    }

    fun setCoinSelection(selection: CoinSelection) {
        _uiState.update { it.copy(send = it.send.copy(coinSelection = selection)) }
    }

    fun broadcastSignedTx() {
        viewModelScope.launch(bgDispatcher) {
            val state = _uiState.value
            val signedStep = state.sendStep as? SendStep.Signed ?: return@launch
            val rawTx = signedStep.signedTx.serializedTx
            _uiState.update { it.copy(send = it.send.copy(isBroadcasting = true)) }
            trezorRepo.broadcastRawTx(serializedTx = rawTx, network = state.selectedNetwork)
                .onSuccess { txid ->
                    TrezorDebugLog.log("BROADCAST", "SUCCESS txid=$txid")
                    _uiState.update {
                        it.copy(
                            send = it.send.copy(
                                isBroadcasting = false,
                                step = signedStep.copy(broadcastTxid = txid),
                            )
                        )
                    }
                    ToastEventBus.send(type = Toast.ToastType.SUCCESS, title = "Transaction broadcast")
                }
                .onFailure {
                    TrezorDebugLog.log("BROADCAST", "FAILED: ${it.message}")
                    _uiState.update { it.copy(send = it.send.copy(isBroadcasting = false)) }
                    ToastEventBus.send(it)
                }
        }
    }

    fun resetSendFlow() {
        _uiState.update { it.copy(send = TrezorSendState()) }
    }

    fun backToComposeForm() {
        _uiState.update {
            it.copy(
                send = it.send.copy(
                    step = SendStep.Form,
                    isSigning = false,
                    isBroadcasting = false,
                )
            )
        }
    }

    fun composeTx() {
        viewModelScope.launch(bgDispatcher) {
            val state = _uiState.value
            val accountInfo = state.accountInfoResult ?: return@launch
            if (!validateComposeInputs(state)) return@launch

            val feeRate = state.sendFeeRate.toFloatOrNull() ?: return@launch
            _uiState.update { it.copy(send = it.send.copy(isComposing = true)) }
            TrezorDebugLog.log("COMPOSE", "=== composeTx START ===")
            TrezorDebugLog.log("COMPOSE", "address=${state.sendAddress}")
            TrezorDebugLog.log("COMPOSE", "amount=${state.sendAmountSats}, sendMax=${state.isSendMax}")
            TrezorDebugLog.log("COMPOSE", "feeRate=$feeRate sat/vB, network=${state.selectedNetwork}")
            TrezorDebugLog.log("COMPOSE", "coinSelection=${state.coinSelection}")
            TrezorDebugLog.log("COMPOSE", "balance=${accountInfo.balance}")

            val output = if (state.isSendMax) {
                ComposeOutput.SendMax(address = state.sendAddress)
            } else {
                val amountSats = state.sendAmountSats.toULongOrNull()
                if (amountSats == null) {
                    _uiState.update { it.copy(send = it.send.copy(isComposing = false)) }
                    ToastEventBus.send(type = Toast.ToastType.ERROR, title = "Enter a valid amount")
                    return@launch
                }
                ComposeOutput.Payment(address = state.sendAddress, amountSats = amountSats)
            }

            trezorRepo.composeTransaction(
                extendedKey = state.lookupInput.trim(),
                outputs = listOf(output),
                feeRates = listOf(feeRate),
                network = state.selectedNetwork,
                accountType = accountInfo.accountType,
                coinSelection = state.coinSelection,
            )
                .onSuccess { handleComposeResults(it) }
                .onFailure {
                    TrezorDebugLog.log("COMPOSE", "FAILED: ${it.message}")
                    _uiState.update { it.copy(send = it.send.copy(isComposing = false)) }
                    ToastEventBus.send(it)
                }
        }
    }

    private suspend fun validateComposeInputs(state: TrezorUiState): Boolean {
        if (state.sendAddress.isBlank()) {
            ToastEventBus.send(type = Toast.ToastType.ERROR, title = "Enter a destination address")
            return false
        }
        if (!state.isSendMax && state.sendAmountSats.isBlank()) {
            ToastEventBus.send(type = Toast.ToastType.ERROR, title = "Enter an amount")
            return false
        }
        val feeRate = state.sendFeeRate.toFloatOrNull()
        if (feeRate == null || feeRate <= 0f) {
            ToastEventBus.send(type = Toast.ToastType.ERROR, title = "Enter a valid fee rate")
            return false
        }
        return true
    }

    private suspend fun handleComposeResults(results: List<ComposeResult>) {
        TrezorDebugLog.log("COMPOSE", "Got ${results.size} result(s)")
        results.forEachIndexed { i, r ->
            when (r) {
                is ComposeResult.Success -> TrezorDebugLog.log(
                    "COMPOSE",
                    "[$i] Success: fee=${r.fee}, totalSpent=${r.totalSpent}, " +
                        "feeRate=${r.feeRate}"
                )
                is ComposeResult.Error -> TrezorDebugLog.log(
                    "COMPOSE",
                    "[$i] Error: ${r.error}"
                )
            }
        }
        val successResult = results.filterIsInstance<ComposeResult.Success>().firstOrNull()
        val errorResult = results.filterIsInstance<ComposeResult.Error>().firstOrNull()
        if (successResult != null) {
            TrezorDebugLog.log("COMPOSE", "=== composeTx SUCCESS ===")
            _uiState.update {
                it.copy(
                    send = it.send.copy(
                        isComposing = false,
                        step = SendStep.Review(successResult),
                    )
                )
            }
            ToastEventBus.send(type = Toast.ToastType.INFO, title = "Transaction composed")
        } else if (errorResult != null) {
            TrezorDebugLog.log("COMPOSE", "=== composeTx FAILED (compose error) ===")
            _uiState.update { it.copy(send = it.send.copy(isComposing = false)) }
            ToastEventBus.send(type = Toast.ToastType.ERROR, title = errorResult.error)
        } else {
            TrezorDebugLog.log("COMPOSE", "=== composeTx FAILED (no valid result) ===")
            _uiState.update { it.copy(send = it.send.copy(isComposing = false)) }
            ToastEventBus.send(type = Toast.ToastType.ERROR, title = "No valid composition returned")
        }
    }

    fun signComposedTx() {
        viewModelScope.launch(bgDispatcher) {
            val state = _uiState.value
            val result = (state.sendStep as? SendStep.Review)?.composeResult ?: return@launch

            TrezorDebugLog.log("SIGN", "=== signComposedTx START ===")
            TrezorDebugLog.log("SIGN", "network=${state.selectedNetwork}")
            TrezorDebugLog.log("SIGN", "psbt length=${result.psbt.length}")

            _uiState.update { it.copy(send = it.send.copy(isSigning = true)) }

            trezorRepo.signTxFromPsbt(
                psbtBase64 = result.psbt,
                network = state.selectedNetwork.toTrezorCoinType(),
            )
                .onSuccess { signedTx ->
                    TrezorDebugLog.log("SIGN", "=== signComposedTx SUCCESS ===")
                    TrezorDebugLog.log(
                        "SIGN",
                        "signatures=${signedTx.signatures.size}, " +
                            "txid=${signedTx.txid}, rawTxLen=${signedTx.serializedTx.length}"
                    )
                    _uiState.update {
                        it.copy(
                            send = it.send.copy(
                                isSigning = false,
                                step = SendStep.Signed(signedTx = signedTx),
                            )
                        )
                    }
                    ToastEventBus.send(
                        type = Toast.ToastType.SUCCESS,
                        title = "Transaction signed (${signedTx.signatures.size} inputs)"
                    )
                }
                .onFailure {
                    TrezorDebugLog.log("SIGN", "signTxFromPsbt FAILED: ${it.message}")
                    _uiState.update { it.copy(send = it.send.copy(isSigning = false)) }
                    ToastEventBus.send(it)
                }
        }
    }

    fun setTxHistoryInput(input: String) {
        _uiState.update { it.copy(txHistory = it.txHistory.copy(input = input)) }
    }

    fun lookupTransactionHistory() {
        viewModelScope.launch(bgDispatcher) {
            val input = _uiState.value.txHistoryInput.trim()
            if (input.isBlank()) {
                ToastEventBus.send(type = Toast.ToastType.ERROR, title = "Enter an xpub")
                return@launch
            }
            _uiState.update {
                it.copy(txHistory = it.txHistory.copy(isLoading = true, result = null))
            }

            val network = _uiState.value.selectedNetwork
            trezorRepo.getTransactionHistory(extendedKey = input, network = network)
                .onSuccess { result ->
                    _uiState.update {
                        it.copy(txHistory = it.txHistory.copy(isLoading = false, result = result))
                    }
                    ToastEventBus.send(
                        type = Toast.ToastType.INFO,
                        title = "Found ${result.txCount} transaction${if (result.txCount != 1u) "s" else ""}"
                    )
                }
                .onFailure {
                    _uiState.update { it.copy(txHistory = it.txHistory.copy(isLoading = false)) }
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

@Stable
data class TrezorUiState(
    val network: TrezorNetworkState = TrezorNetworkState(),
    val message: TrezorMessageState = TrezorMessageState(),
    val lookup: TrezorLookupState = TrezorLookupState(),
    val send: TrezorSendState = TrezorSendState(),
    val txHistory: TrezorTxHistoryState = TrezorTxHistoryState(),
) {
    val selectedNetwork: BitkitCoreNetwork
        get() = network.selectedNetwork

    val addressIndex: Int
        get() = network.addressIndex

    val derivationPath: String
        get() = network.derivationPath

    val messageToSign: String
        get() = message.messageToSign

    val lastSignature: String?
        get() = message.lastSignature

    val lastSigningAddress: String?
        get() = message.lastSigningAddress

    val isSigningMessage: Boolean
        get() = message.isSigningMessage

    val isGettingAddress: Boolean
        get() = network.isGettingAddress

    val isGettingPublicKey: Boolean
        get() = network.isGettingPublicKey

    val isVerifyingMessage: Boolean
        get() = message.isVerifyingMessage

    val lookupInput: String
        get() = lookup.input

    val isLookingUp: Boolean
        get() = lookup.isLookingUp

    val accountInfoResult: AccountInfoResult?
        get() = lookup.accountInfoResult

    val addressInfoResult: SingleAddressInfoResult?
        get() = lookup.addressInfoResult

    val sendAddress: String
        get() = send.address

    val sendAmountSats: String
        get() = send.amountSats

    val sendFeeRate: String
        get() = send.feeRate

    val isSendMax: Boolean
        get() = send.isMax

    val isComposing: Boolean
        get() = send.isComposing

    val isSigning: Boolean
        get() = send.isSigning

    val sendStep: SendStep
        get() = send.step

    val composeResult: ComposeResult.Success?
        get() = (send.step as? SendStep.Review)?.composeResult

    val signedTxResult: TrezorSignedTx?
        get() = (send.step as? SendStep.Signed)?.signedTx

    val coinSelection: CoinSelection
        get() = send.coinSelection

    val isBroadcasting: Boolean
        get() = send.isBroadcasting

    val broadcastTxid: String?
        get() = (send.step as? SendStep.Signed)?.broadcastTxid

    val txHistoryInput: String
        get() = txHistory.input

    val isLoadingTxHistory: Boolean
        get() = txHistory.isLoading

    val txHistoryResult: TransactionHistoryResult?
        get() = txHistory.result
}

@Stable
data class TrezorNetworkState(
    val selectedNetwork: BitkitCoreNetwork = Env.network.toCoreNetwork(),
    val addressIndex: Int = 0,
    val derivationPath: String = derivationPath(
        network = selectedNetwork,
        index = addressIndex,
    ),
    val isGettingAddress: Boolean = false,
    val isGettingPublicKey: Boolean = false,
)

@Stable
data class TrezorMessageState(
    val messageToSign: String = "Hello, Trezor!",
    val lastSignature: String? = null,
    val lastSigningAddress: String? = null,
    val isSigningMessage: Boolean = false,
    val isVerifyingMessage: Boolean = false,
)

@Stable
data class TrezorLookupState(
    val input: String = "",
    val isLookingUp: Boolean = false,
    val accountInfoResult: AccountInfoResult? = null,
    val addressInfoResult: SingleAddressInfoResult? = null,
)

@Stable
data class TrezorSendState(
    val address: String = "",
    val amountSats: String = "",
    val feeRate: String = "2",
    val isMax: Boolean = false,
    val isComposing: Boolean = false,
    val isSigning: Boolean = false,
    val step: SendStep = SendStep.Form,
    val coinSelection: CoinSelection = CoinSelection.BRANCH_AND_BOUND,
    val isBroadcasting: Boolean = false,
)

@Stable
data class TrezorTxHistoryState(
    val input: String = "",
    val isLoading: Boolean = false,
    val result: TransactionHistoryResult? = null,
)

sealed interface SendStep {
    data object Form : SendStep

    data class Review(val composeResult: ComposeResult.Success) : SendStep

    data class Signed(
        val signedTx: TrezorSignedTx,
        val broadcastTxid: String? = null,
    ) : SendStep
}

private fun derivationPath(network: BitkitCoreNetwork, index: Int): String {
    return "m/84'/${coinTypeFor(network)}'/0'/0/$index"
}

private fun accountPath(derivationPath: String): String {
    return derivationPath.split("/").take(4).joinToString("/")
}

private fun coinTypeFor(network: BitkitCoreNetwork): String {
    return if (network == BitkitCoreNetwork.BITCOIN) "0" else "1"
}

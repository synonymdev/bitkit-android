package to.bitkit.ui.screens.trezor

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.AccountInfoResult
import com.synonym.bitkitcore.AccountType
import com.synonym.bitkitcore.CoinSelection
import com.synonym.bitkitcore.ComposeOutput
import com.synonym.bitkitcore.ComposeResult
import com.synonym.bitkitcore.HistoryTransaction
import com.synonym.bitkitcore.SingleAddressInfoResult
import com.synonym.bitkitcore.TrezorScriptType
import com.synonym.bitkitcore.TrezorSignedTx
import com.synonym.bitkitcore.WalletBalance
import com.synonym.bitkitcore.WatcherEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
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
        observeWatcherEvents()
    }

    private fun observeWatcherEvents() {
        viewModelScope.launch(bgDispatcher) {
            trezorRepo.watcherEvents.collect { (watcherId, event) ->
                if (watcherId != _uiState.value.activeWatcherId) return@collect
                when (event) {
                    is WatcherEvent.TransactionsChanged -> _uiState.update {
                        it.copy(
                            watcherBalance = event.balance,
                            watcherTransactions = event.transactions.toImmutableList(),
                            watcherTransactionCount = event.txCount,
                            watcherBlockHeight = event.blockHeight,
                            watcherAccountType = event.accountType,
                            watcherConnectionStatus = WatcherConnectionStatus.CONNECTED,
                            watcherEvents = (it.watcherEvents +
                                "TX update: ${event.txCount} txs, balance=${event.balance.total} sats")
                                .takeLast(MAX_WATCHER_EVENT_LOG).toImmutableList(),
                        )
                    }

                    is WatcherEvent.Error -> {
                        _uiState.update {
                            it.copy(
                                watcherConnectionStatus = WatcherConnectionStatus.ERROR,
                                watcherEvents = (it.watcherEvents + "Error: ${event.message}")
                                    .takeLast(MAX_WATCHER_EVENT_LOG).toImmutableList(),
                            )
                        }
                        ToastEventBus.send(type = Toast.ToastType.ERROR, title = "Watcher error: ${event.message}")
                    }

                    is WatcherEvent.Disconnected -> _uiState.update {
                        it.copy(
                            watcherConnectionStatus = WatcherConnectionStatus.DISCONNECTED,
                            watcherEvents = (it.watcherEvents + "Disconnected: ${event.message}")
                                .takeLast(MAX_WATCHER_EVENT_LOG).toImmutableList(),
                        )
                    }

                    is WatcherEvent.Reconnected -> _uiState.update {
                        it.copy(
                            watcherConnectionStatus = WatcherConnectionStatus.CONNECTED,
                            watcherEvents = (it.watcherEvents + "Reconnected")
                                .takeLast(MAX_WATCHER_EVENT_LOG).toImmutableList(),
                        )
                    }
                }
            }
        }
    }

    val trezorState = trezorRepo.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), trezorRepo.state.value)

    /**
     * Flow indicating when a pairing code is needed.
     * UI should show a dialog when this is true.
     */
    val needsPairingCode = trezorRepo.needsPairingCode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val needsPinEntry = trezorRepo.needsPinEntry
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val needsPassphraseEntry = trezorRepo.needsPassphraseEntry
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
                coin = state.selectedNetwork.toTrezorCoinType(),
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
                coin = state.selectedNetwork.toTrezorCoinType(),
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

    fun setSelectedNetwork(network: BitkitCoreNetwork) {
        val coinType = if (network == BitkitCoreNetwork.BITCOIN) "0" else "1"
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
            val coinType = if (state.selectedNetwork == BitkitCoreNetwork.BITCOIN) "0" else "1"
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
            _uiState.update {
                it.copy(
                    isLookingUp = true,
                    accountInfoResult = null,
                    addressInfoResult = null,
                    sendAddress = "",
                    sendAmountSats = "",
                    sendFeeRate = "2",
                    isSendMax = false,
                    isComposing = false,
                    isSigning = false,
                    composeResult = null,
                    signedTxResult = null,
                    sendStep = SendStep.FORM,
                    coinSelection = CoinSelection.BRANCH_AND_BOUND,
                    isBroadcasting = false,
                    broadcastTxid = null,
                )
            }

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
            trezorRepo.signMessage(
                path = state.derivationPath,
                message = message,
                coin = state.selectedNetwork.toTrezorCoinType()
            )
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
                coin = _uiState.value.selectedNetwork.toTrezorCoinType(),
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

    fun setSendAddress(address: String) {
        _uiState.update { it.copy(sendAddress = address) }
    }

    fun setSendAmount(amount: String) {
        _uiState.update { it.copy(sendAmountSats = amount) }
    }

    fun setSendFeeRate(feeRate: String) {
        _uiState.update { it.copy(sendFeeRate = feeRate) }
    }

    fun toggleSendMax() {
        _uiState.update { it.copy(isSendMax = !it.isSendMax) }
    }

    fun setCoinSelection(selection: CoinSelection) {
        _uiState.update { it.copy(coinSelection = selection) }
    }

    fun broadcastSignedTx() {
        viewModelScope.launch(bgDispatcher) {
            val state = _uiState.value
            val rawTx = state.signedTxResult?.serializedTx ?: return@launch
            _uiState.update { it.copy(isBroadcasting = true) }
            trezorRepo.broadcastRawTx(serializedTx = rawTx, network = state.selectedNetwork)
                .onSuccess { txid ->
                    TrezorDebugLog.log("BROADCAST", "SUCCESS txid=$txid")
                    _uiState.update { it.copy(isBroadcasting = false, broadcastTxid = txid) }
                    ToastEventBus.send(type = Toast.ToastType.SUCCESS, title = "Transaction broadcast")
                }
                .onFailure {
                    TrezorDebugLog.log("BROADCAST", "FAILED: ${it.message}")
                    _uiState.update { it.copy(isBroadcasting = false) }
                    ToastEventBus.send(it)
                }
        }
    }

    fun resetSendFlow() {
        _uiState.update {
            it.copy(
                sendAddress = "",
                sendAmountSats = "",
                sendFeeRate = "2",
                isSendMax = false,
                isComposing = false,
                isSigning = false,
                composeResult = null,
                signedTxResult = null,
                sendStep = SendStep.FORM,
                coinSelection = CoinSelection.BRANCH_AND_BOUND,
                isBroadcasting = false,
                broadcastTxid = null,
            )
        }
    }

    fun backToComposeForm() {
        _uiState.update {
            it.copy(
                sendStep = SendStep.FORM,
                composeResult = null,
                signedTxResult = null,
            )
        }
    }

    fun composeTx() {
        viewModelScope.launch(bgDispatcher) {
            val state = _uiState.value
            val accountInfo = state.accountInfoResult ?: return@launch
            if (!validateComposeInputs(state)) return@launch

            _uiState.update { it.copy(isComposing = true) }

            val feeRate = state.sendFeeRate.toFloatOrNull() ?: return@launch
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
                    _uiState.update { it.copy(isComposing = false) }
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
                    _uiState.update { it.copy(isComposing = false) }
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
                it.copy(isComposing = false, composeResult = successResult, sendStep = SendStep.REVIEW)
            }
            ToastEventBus.send(type = Toast.ToastType.INFO, title = "Transaction composed")
        } else if (errorResult != null) {
            TrezorDebugLog.log("COMPOSE", "=== composeTx FAILED (compose error) ===")
            _uiState.update { it.copy(isComposing = false) }
            ToastEventBus.send(type = Toast.ToastType.ERROR, title = errorResult.error)
        } else {
            TrezorDebugLog.log("COMPOSE", "=== composeTx FAILED (no valid result) ===")
            _uiState.update { it.copy(isComposing = false) }
            ToastEventBus.send(type = Toast.ToastType.ERROR, title = "No valid composition returned")
        }
    }

    fun signComposedTx() {
        viewModelScope.launch(bgDispatcher) {
            val state = _uiState.value
            val result = state.composeResult ?: return@launch

            TrezorDebugLog.log("SIGN", "=== signComposedTx START ===")
            TrezorDebugLog.log("SIGN", "network=${state.selectedNetwork}")
            TrezorDebugLog.log("SIGN", "psbt length=${result.psbt.length}")

            _uiState.update { it.copy(isSigning = true) }

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
                        it.copy(isSigning = false, signedTxResult = signedTx, sendStep = SendStep.SIGNED)
                    }
                    ToastEventBus.send(
                        type = Toast.ToastType.SUCCESS,
                        title = "Transaction signed (${signedTx.signatures.size} inputs)"
                    )
                }
                .onFailure {
                    TrezorDebugLog.log("SIGN", "signTxFromPsbt FAILED: ${it.message}")
                    _uiState.update { s -> s.copy(isSigning = false) }
                    ToastEventBus.send(it)
                }
        }
    }

    fun setWatcherExtendedKey(key: String) {
        _uiState.update { it.copy(watcherExtendedKey = key) }
    }

    fun setWatcherGapLimit(limit: String) {
        _uiState.update { it.copy(watcherGapLimit = limit) }
    }

    fun populateWatcherFromXpub() {
        val xpub = trezorRepo.state.value.lastPublicKey?.xpub ?: return
        _uiState.update { it.copy(watcherExtendedKey = xpub) }
    }

    fun startWatcher() {
        viewModelScope.launch(bgDispatcher) {
            val state = _uiState.value
            val key = state.watcherExtendedKey.trim()
            if (key.isBlank()) {
                ToastEventBus.send(type = Toast.ToastType.ERROR, title = "Enter an extended key (xpub)")
                return@launch
            }
            val gapLimit = state.watcherGapLimit.toUIntOrNull() ?: 20u

            _uiState.update { it.copy(isStartingWatcher = true) }
            trezorRepo.startWatcher(
                extendedKey = key,
                network = state.selectedNetwork,
                gapLimit = gapLimit,
            )
                .onSuccess { watcherId ->
                    _uiState.update {
                        it.copy(
                            isStartingWatcher = false,
                            activeWatcherId = watcherId,
                            watcherConnectionStatus = WatcherConnectionStatus.CONNECTED,
                            watcherEvents = persistentListOf("Watcher started: $watcherId"),
                        )
                    }
                    ToastEventBus.send(type = Toast.ToastType.INFO, title = "Watcher started")
                }
                .onFailure {
                    _uiState.update { s -> s.copy(isStartingWatcher = false) }
                    ToastEventBus.send(it)
                }
        }
    }

    fun stopWatcher() {
        val watcherId = _uiState.value.activeWatcherId ?: return
        trezorRepo.stopWatcher(watcherId)
            .onSuccess {
                _uiState.update {
                    it.copy(
                        activeWatcherId = null,
                        watcherBalance = null,
                        watcherTransactions = persistentListOf(),
                        watcherTransactionCount = 0u,
                        watcherBlockHeight = 0u,
                        watcherAccountType = null,
                        watcherEvents = persistentListOf(),
                    )
                }
                viewModelScope.launch {
                    ToastEventBus.send(type = Toast.ToastType.INFO, title = "Watcher stopped")
                }
            }
            .onFailure { viewModelScope.launch { ToastEventBus.send(it) } }
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

    fun submitPin(pin: String) {
        trezorRepo.submitPin(pin)
    }

    fun cancelPin() {
        trezorRepo.cancelPin()
    }

    fun submitPassphrase(passphrase: String) {
        trezorRepo.submitPassphrase(passphrase)
    }

    fun cancelPassphrase() {
        trezorRepo.cancelPassphrase()
    }
}

@Stable
data class TrezorUiState(
    val selectedNetwork: BitkitCoreNetwork = Env.network.toCoreNetwork(),
    val addressIndex: Int = 0,
    val derivationPath: String =
        "m/84'/${if (Env.network.toCoreNetwork() == BitkitCoreNetwork.BITCOIN) "0" else "1"}'/0'/0/0",
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
    val sendAddress: String = "",
    val sendAmountSats: String = "",
    val sendFeeRate: String = "2",
    val isSendMax: Boolean = false,
    val isComposing: Boolean = false,
    val isSigning: Boolean = false,
    val composeResult: ComposeResult.Success? = null,
    val signedTxResult: TrezorSignedTx? = null,
    val sendStep: SendStep = SendStep.FORM,
    val coinSelection: CoinSelection = CoinSelection.BRANCH_AND_BOUND,
    val isBroadcasting: Boolean = false,
    val broadcastTxid: String? = null,
    val watcherExtendedKey: String = "",
    val watcherGapLimit: String = "20",
    val isStartingWatcher: Boolean = false,
    val activeWatcherId: String? = null,
    val watcherConnectionStatus: WatcherConnectionStatus = WatcherConnectionStatus.CONNECTED,
    val watcherBalance: WalletBalance? = null,
    val watcherTransactions: ImmutableList<HistoryTransaction> = persistentListOf(),
    val watcherTransactionCount: UInt = 0u,
    val watcherBlockHeight: UInt = 0u,
    val watcherAccountType: AccountType? = null,
    val watcherEvents: ImmutableList<String> = persistentListOf(),
)

private const val MAX_WATCHER_EVENT_LOG = 50

enum class SendStep { FORM, REVIEW, SIGNED }

enum class WatcherConnectionStatus { CONNECTED, DISCONNECTED, ERROR }

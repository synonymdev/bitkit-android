package to.bitkit.ui.screens.trezor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.AccountInfoResult
import com.synonym.bitkitcore.SingleAddressInfoResult
import com.synonym.bitkitcore.TrezorCoinType
import com.synonym.bitkitcore.TrezorFeeLevel
import com.synonym.bitkitcore.TrezorPrecomposeOutput
import com.synonym.bitkitcore.TrezorPrecomposeParams
import com.synonym.bitkitcore.TrezorPrecomposedOutput
import com.synonym.bitkitcore.TrezorPrecomposedResult
import com.synonym.bitkitcore.TrezorScriptType
import com.synonym.bitkitcore.TrezorSignTxParams
import com.synonym.bitkitcore.TrezorSignedTx
import com.synonym.bitkitcore.TrezorSortingStrategy
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
import to.bitkit.services.TrezorDebugLog
import to.bitkit.ui.shared.toast.ToastEventBus
import javax.inject.Inject

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
                    precomposedResult = null,
                    signedTxResult = null,
                    sendStep = SendStep.FORM,
                    sortingStrategy = TrezorSortingStrategy.BIP69,
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

    fun setSortingStrategy(strategy: TrezorSortingStrategy) {
        _uiState.update { it.copy(sortingStrategy = strategy) }
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
                precomposedResult = null,
                signedTxResult = null,
                sendStep = SendStep.FORM,
                sortingStrategy = TrezorSortingStrategy.BIP69,
                isBroadcasting = false,
                broadcastTxid = null,
            )
        }
    }

    fun backToComposeForm() {
        _uiState.update {
            it.copy(
                sendStep = SendStep.FORM,
                precomposedResult = null,
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

            val coinStr = trezorRepo.coinStringForNetwork(state.selectedNetwork)
            TrezorDebugLog.log("COMPOSE", "=== composeTx START ===")
            TrezorDebugLog.log("COMPOSE", "address=${state.sendAddress}")
            TrezorDebugLog.log("COMPOSE", "amount=${state.sendAmountSats}, sendMax=${state.isSendMax}")
            TrezorDebugLog.log("COMPOSE", "feeRate=${state.sendFeeRate} sat/vB, coin=$coinStr")
            TrezorDebugLog.log("COMPOSE", "account.path=${accountInfo.account.path}")
            TrezorDebugLog.log("COMPOSE", "utxos=${accountInfo.account.utxo.size}, balance=${accountInfo.balance}")

            val output = if (state.isSendMax) {
                TrezorPrecomposeOutput.SendMax(address = state.sendAddress)
            } else {
                TrezorPrecomposeOutput.Payment(address = state.sendAddress, amount = state.sendAmountSats)
            }

            val params = TrezorPrecomposeParams(
                outputs = listOf(output),
                coin = coinStr,
                account = accountInfo.account,
                feeLevels = listOf(
                    TrezorFeeLevel(feePerUnit = state.sendFeeRate, baseFee = null, floorBaseFee = null)
                ),
                sequence = null,
                sortingStrategy = state.sortingStrategy,
            )

            trezorRepo.precomposeTransaction(params)
                .onSuccess { handlePrecomposeResults(it) }
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
        val feeRate = state.sendFeeRate.toLongOrNull()
        if (feeRate == null || feeRate <= 0) {
            ToastEventBus.send(type = Toast.ToastType.ERROR, title = "Enter a valid fee rate")
            return false
        }
        return true
    }

    private suspend fun handlePrecomposeResults(results: List<TrezorPrecomposedResult>) {
        TrezorDebugLog.log("COMPOSE", "Got ${results.size} result(s)")
        results.forEachIndexed { i, r ->
            when (r) {
                is TrezorPrecomposedResult.Final -> TrezorDebugLog.log(
                    "COMPOSE",
                    "[$i] Final: fee=${r.fee}, totalSpent=${r.totalSpent}, " +
                        "feePerByte=${r.feePerByte}, bytes=${r.bytes}, " +
                        "inputs=${r.inputs.size}, outputs=${r.outputs.size}"
                )
                is TrezorPrecomposedResult.NonFinal -> TrezorDebugLog.log(
                    "COMPOSE",
                    "[$i] NonFinal: max=${r.max}, fee=${r.fee}"
                )
                is TrezorPrecomposedResult.Error -> TrezorDebugLog.log(
                    "COMPOSE",
                    "[$i] Error: ${r.error}"
                )
            }
        }
        val finalResult = results.filterIsInstance<TrezorPrecomposedResult.Final>().firstOrNull()
        val errorResult = results.filterIsInstance<TrezorPrecomposedResult.Error>().firstOrNull()
        if (finalResult != null) {
            finalResult.inputs.forEach {
                TrezorDebugLog.log(
                    "COMPOSE",
                    "  input: txid=${it.txid}, vout=${it.vout}, " +
                        "amount=${it.amount}, path=${it.path}, scriptType=${it.scriptType}"
                )
            }
            finalResult.outputs.forEach {
                when (it) {
                    is TrezorPrecomposedOutput.Payment ->
                        TrezorDebugLog.log("COMPOSE", "  output(payment): addr=${it.address}, amount=${it.amount}")
                    is TrezorPrecomposedOutput.Change ->
                        TrezorDebugLog.log(
                            "COMPOSE",
                            "  output(change): addr=${it.address}, " +
                                "amount=${it.amount}, path=${it.path}"
                        )
                    is TrezorPrecomposedOutput.OpReturn ->
                        TrezorDebugLog.log("COMPOSE", "  output(opreturn): ${it.dataHex}")
                }
            }
            TrezorDebugLog.log("COMPOSE", "=== composeTx SUCCESS ===")
            _uiState.update {
                it.copy(isComposing = false, precomposedResult = finalResult, sendStep = SendStep.REVIEW)
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
            val result = state.precomposedResult ?: return@launch

            TrezorDebugLog.log("SIGN", "=== signComposedTx START ===")
            TrezorDebugLog.log("SIGN", "inputs=${result.inputs.size}, outputs=${result.outputs.size}")
            TrezorDebugLog.log("SIGN", "network=${state.selectedNetwork}")
            result.inputs.forEach {
                TrezorDebugLog.log(
                    "SIGN",
                    "  input: txid=${it.txid}, vout=${it.vout}, " +
                        "amount=${it.amount}, scriptType=${it.scriptType}, path=${it.path}"
                )
            }

            _uiState.update { it.copy(isSigning = true) }

            TrezorDebugLog.log("SIGN", "Converting precomposed to sign params...")
            trezorRepo.convertToSignParams(
                inputs = result.inputs,
                outputs = result.outputs,
                coin = state.selectedNetwork,
            ).onSuccess { logAndSign(it) }
                .onFailure {
                    TrezorDebugLog.log("SIGN", "convertToSignParams FAILED: ${it.message}")
                    _uiState.update { s -> s.copy(isSigning = false) }
                    ToastEventBus.send(it)
                }
        }
    }

    private suspend fun logAndSign(signParams: TrezorSignTxParams) {
        val network = _uiState.value.selectedNetwork
        val txids = signParams.inputs.map { it.prevHash }.distinct()
        TrezorDebugLog.log(
            "SIGN",
            "Sign params: inputs=${signParams.inputs.size}, " +
                "outputs=${signParams.outputs.size}, coin=${signParams.coin}"
        )
        TrezorDebugLog.log("SIGN", "Fetching ${txids.size} prev tx(s) from Electrum...")
        trezorRepo.fetchPrevTxs(txids = txids, network = network)
            .onSuccess { prevTxs ->
                TrezorDebugLog.log("SIGN", "Fetched ${prevTxs.size} prev tx(s)")
                val completeParams = signParams.copy(prevTxs = prevTxs)
                TrezorDebugLog.log("SIGN", "Calling trezor signTx...")
                executeSign(completeParams)
            }
            .onFailure {
                TrezorDebugLog.log("SIGN", "fetchPrevTxs FAILED: ${it.message}")
                _uiState.update { s -> s.copy(isSigning = false) }
                ToastEventBus.send(it)
            }
    }

    private suspend fun executeSign(params: TrezorSignTxParams) {
        trezorRepo.signTxWithParams(params)
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
                TrezorDebugLog.log("SIGN", "signTx FAILED: ${it.message}")
                _uiState.update { s -> s.copy(isSigning = false) }
                ToastEventBus.send(it)
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
    val sendAddress: String = "",
    val sendAmountSats: String = "",
    val sendFeeRate: String = "2",
    val isSendMax: Boolean = false,
    val isComposing: Boolean = false,
    val isSigning: Boolean = false,
    val precomposedResult: TrezorPrecomposedResult.Final? = null,
    val signedTxResult: TrezorSignedTx? = null,
    val sendStep: SendStep = SendStep.FORM,
    val sortingStrategy: TrezorSortingStrategy = TrezorSortingStrategy.BIP69,
    val isBroadcasting: Boolean = false,
    val broadcastTxid: String? = null,
)

enum class SendStep { FORM, REVIEW, SIGNED }

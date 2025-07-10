package to.bitkit.ui.settings.advanced

import android.content.Context
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.Network
import to.bitkit.R
import to.bitkit.data.SettingsStore
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.models.ElectrumProtocol
import to.bitkit.models.ElectrumServer
import to.bitkit.models.ElectrumServerPeer
import to.bitkit.models.Toast
import to.bitkit.models.getDefaultPort
import to.bitkit.repositories.LightningRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import javax.inject.Inject

@HiltViewModel
class ElectrumConfigViewModel @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
    private val lightningRepo: LightningRepo,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ElectrumConfigUiState())
    val uiState: StateFlow<ElectrumConfigUiState> = _uiState.asStateFlow()

    val defaultElectrumPorts = listOf("51002", "50002", "51001", "50001")

    init {
        observeState()
    }

    private fun observeState() {
        viewModelScope.launch(bgDispatcher) {
            lightningRepo.lightningState.collect { lightningState ->
                _uiState.update { currentState ->
                    currentState.copy(
                        isConnected = lightningState.nodeStatus?.isRunning == true,
                    )
                }
            }
        }

        viewModelScope.launch(bgDispatcher) {
            settingsStore.data.map { it.electrumServer }.distinctUntilChanged()
                .collect { electrumServer ->
                    val connectedPeer = ElectrumServerPeer(
                        host = electrumServer.host,
                        port = electrumServer.getPort().toString(),
                        protocol = electrumServer.protocol,
                    )

                    _uiState.update {
                        val newState = it.copy(
                            connectedPeer = connectedPeer,
                            host = connectedPeer.host,
                            port = connectedPeer.port,
                            protocol = connectedPeer.protocol,
                        )
                        newState.copy(hasEdited = computeHasEdited(newState))
                    }
                }
        }
    }

    fun setHost(host: String) {
        _uiState.update {
            val newState = it.copy(host = host.trim())
            newState.copy(hasEdited = computeHasEdited(newState))
        }
    }

    fun setPort(port: String) {
        _uiState.update {
            val newState = it.copy(port = port.trim())
            newState.copy(hasEdited = computeHasEdited(newState))
        }
    }

    fun setProtocol(protocol: ElectrumProtocol) {
        _uiState.update {
            // Toggle the port if the protocol changes and the default ports are still used
            val newPort = if (it.port.isEmpty() || it.port in defaultElectrumPorts) {
                protocol.getDefaultPort().toString()
            } else {
                it.port
            }

            val newState = it.copy(
                protocol = protocol,
                port = newPort,
            )
            newState.copy(hasEdited = computeHasEdited(newState))
        }
    }

    fun resetToDefault() {
        val defaultServer = Env.defaultElectrumServer
        _uiState.update {
            it.copy(
                host = defaultServer.host,
                port = defaultServer.getPort().toString(),
                protocol = defaultServer.protocol,
                hasEdited = false,
            )
        }
    }

    fun connectToServer() {
        val currentState = _uiState.value
        val port = currentState.port.toIntOrNull()
        val protocol = currentState.protocol

        if (currentState.host.isBlank() || port == null || port <= 0 || protocol == null) {
            return
        }

        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch(bgDispatcher) {
            try {
                val electrumServer = ElectrumServer.fromUserInput(
                    host = currentState.host,
                    port = port,
                    protocol = protocol,
                )

                lightningRepo.restartWithElectrumServer(electrumServer)
                    .onSuccess {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                connectionResult = Result.success(Unit),
                                hasEdited = false,
                            )
                        }
                    }
                    .onFailure { error -> throw error }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        connectionResult = Result.failure(e),
                    )
                }
            }
        }
    }

    fun validateInput(
        host: String = _uiState.value.host,
        port: String = _uiState.value.port,
    ): String? {
        var error: String? = null

        if (host.isBlank() && port.isBlank()) {
            error = context.getString(R.string.settings__es__error_host_port)
        } else if (host.isBlank()) {
            error = context.getString(R.string.settings__es__error_host)
        } else if (port.isBlank()) {
            error = context.getString(R.string.settings__es__error_port)
        } else {
            val portNumber = port.toIntOrNull()
            if (portNumber == null || portNumber <= 0 || portNumber > 65535) {
                error = context.getString(R.string.settings__es__error_port_invalid)
            }
        }

        val url = "$host:$port"
        if (!isValidURL(url)) {
            error = context.getString(R.string.settings__es__error_invalid_http)
        }

        return error
    }

    private fun isValidURL(data: String): Boolean {
        // Add 'http://' if the protocol is missing to enable URL parsing
        val normalizedData = if (!data.startsWith("http://") && !data.startsWith("https://")) {
            "http://$data"
        } else {
            data
        }

        return try {
            val url = normalizedData.toUri()
            val hostname = url.host ?: return false

            // Allow standard domains, custom TLDs like .local, and IPv4 addresses
            val isValidDomainOrIP = hostname.matches(
                Regex(
                    "^([a-z\\d]([a-z\\d-]*[a-z\\d])*\\.)+[a-z\\d-]+|(\\d{1,3}\\.){3}\\d{1,3}$",
                    RegexOption.IGNORE_CASE
                )
            )

            // Always allow .local domains
            if (hostname.endsWith(".local")) {
                return true
            }

            // Allow localhost in development mode
            if (Env.isDebug && data.contains("localhost")) {
                return true
            }

            isValidDomainOrIP
        } catch (_: Throwable) {
            // If URL constructor fails, it's not a valid URL
            false
        }
    }

    private fun computeHasEdited(state: ElectrumConfigUiState): Boolean {
        val protocol = state.protocol ?: return false
        val uiPeer = ElectrumServerPeer(
            host = state.host,
            port = state.port,
            protocol = protocol,
        )
        return uiPeer != state.connectedPeer
    }

    fun clearConnectionResult() {
        _uiState.update { it.copy(connectionResult = null) }
    }

    fun onClickConnect() {
        viewModelScope.launch {
            val validationError = validateInput()
            if (validationError != null) {
                ToastEventBus.send(
                    type = Toast.ToastType.WARNING,
                    title = context.getString(R.string.settings__es__error_peer),
                    description = validationError,
                )
            } else {
                connectToServer()
            }
        }
    }

    fun onScan(data: String) {
        viewModelScope.launch {
            val parseResult = parseElectrumScanData(data)
            val serverPeer = parseResult.getOrDefault(ElectrumServerPeer("", "", ElectrumProtocol.TCP))

            val host = serverPeer.host
            val port = serverPeer.port
            val protocol = serverPeer.protocol

            val validationError = validateInput(host, port)
            if (validationError != null) {
                ToastEventBus.send(
                    type = Toast.ToastType.WARNING,
                    title = context.getString(R.string.settings__es__error_peer),
                    description = validationError,
                )
                return@launch
            }

            setHost(host)
            setPort(port)
            setProtocol(protocol)

            connectToServer()
        }
    }

    private fun parseElectrumScanData(data: String): Result<ElectrumServerPeer> {
        return when {
            // Handle plain format: host:port or host:port:s (Umbrel format)
            !data.startsWith("http://") && !data.startsWith("https://") -> {
                runCatching {
                    val parts = data.split(":")
                    val host = parts.getOrNull(0) ?: ""
                    val port = parts.getOrNull(1) ?: ""
                    val shortProtocol = parts.getOrNull(2)

                    val protocol = if (shortProtocol != null) {
                        // Support Umbrel connection URL format
                        if (shortProtocol == "s") ElectrumProtocol.SSL else ElectrumProtocol.TCP
                    } else {
                        // Prefix protocol for common ports if missing
                        getProtocolForPort(port)
                    }

                    return@runCatching ElectrumServerPeer(host, port, protocol)
                }
            }
            // Handle URLs with http:// or https:// prefix
            else -> {
                runCatching {
                    val uri = data.toUri()
                    val host = uri.host ?: ""
                    val port = if (uri.port > 0) {
                        uri.port.toString()
                    } else {
                        if (uri.scheme == "https") "443" else "80"
                    }
                    val protocol = if (uri.scheme == "https") ElectrumProtocol.SSL else ElectrumProtocol.TCP

                    return@runCatching ElectrumServerPeer(host, port, protocol)
                }
            }
        }
    }

    private fun getProtocolForPort(port: String): ElectrumProtocol {
        if (port == "443") return ElectrumProtocol.SSL

        // Network-specific logic for testnet
        if (Env.network == Network.TESTNET) {
            return if (port == "51002") ElectrumProtocol.SSL else ElectrumProtocol.TCP
        }

        // Default logic for mainnet and other networks
        return when (port) {
            "50002", "51002" -> ElectrumProtocol.SSL
            "50001", "51001" -> ElectrumProtocol.TCP
            else -> ElectrumProtocol.TCP // Default to TCP
        }
    }
}

data class ElectrumConfigUiState(
    val isConnected: Boolean = false,
    val connectedPeer: ElectrumServerPeer? = null,
    val host: String = "",
    val port: String = "",
    val protocol: ElectrumProtocol? = null,
    val isLoading: Boolean = false,
    val connectionResult: Result<Unit>? = null,
    val hasEdited: Boolean = false,
)

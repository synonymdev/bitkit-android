package to.bitkit.viewmodels

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.vssclient.KeyVersion
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.PeerDetails
import to.bitkit.data.backup.VssBackupClient
import to.bitkit.di.BgDispatcher
import to.bitkit.models.ToastText
import to.bitkit.ext.of
import to.bitkit.repositories.LightningRepo
import to.bitkit.services.NetworkGraphInfo
import to.bitkit.ui.shared.toast.Toaster
import to.bitkit.utils.Logger
import java.io.File
import javax.inject.Inject

@HiltViewModel
class LdkDebugViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val lightningRepo: LightningRepo,
    private val vssBackupClient: VssBackupClient,
    private val toaster: Toaster,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LdkDebugUiState())
    val uiState = _uiState.asStateFlow()

    fun updateNodeUri(uri: String) {
        _uiState.update { it.copy(nodeUri = uri) }
    }

    fun addPeer() {
        val uri = _uiState.value.nodeUri.trim()
        if (uri.isEmpty()) {
            viewModelScope.launch { toaster.warn(title = ToastText("Please enter a node URI")) }
            return
        }
        connectPeer(uri)
    }

    fun pasteAndAddPeer() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = clipboard.primaryClip
        val pastedUri = clipData?.getItemAt(0)?.text?.toString()?.trim()

        if (pastedUri.isNullOrEmpty()) {
            viewModelScope.launch { toaster.warn(title = ToastText("Clipboard is empty")) }
            return
        }

        _uiState.update { it.copy(nodeUri = pastedUri) }
        connectPeer(pastedUri)
    }

    private fun connectPeer(uri: String) {
        viewModelScope.launch(bgDispatcher) {
            _uiState.update { it.copy(isLoading = true) }
            runCatching {
                val peer = PeerDetails.of(uri)
                lightningRepo.connectPeer(peer)
            }.onSuccess { result ->
                result.onSuccess {
                    toaster.info(title = ToastText("Peer connected"))
                    _uiState.update { it.copy(nodeUri = "") }
                }.onFailure { e ->
                    Logger.error("Failed to connect peer", e, context = TAG)
                    toaster.error(title = ToastText("Failed to connect peer"), body = e.message?.let { ToastText(it) })
                }
            }.onFailure { e ->
                Logger.error("Failed to parse peer URI", e, context = TAG)
                toaster.error(title = ToastText("Invalid node URI format"), body = e.message?.let { ToastText(it) })
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun logNetworkGraphInfo() {
        viewModelScope.launch(bgDispatcher) {
            val info = lightningRepo.getNetworkGraphInfo()
            if (info != null) {
                Logger.info(
                    "NetworkGraph Info:\n" +
                        "\tNodes: ${info.nodeCount}\n" +
                        "\tChannels: ${info.channelCount}\n" +
                        "\tLatest RGS sync: ${info.latestRgsSyncTimestamp}",
                    context = TAG
                )
                _uiState.update { it.copy(networkGraphInfo = info) }
                toaster.info(title = ToastText("Network graph info logged"))
            } else {
                toaster.warn(title = ToastText("Failed to get network graph info"))
            }
        }
    }

    fun exportNetworkGraph(onFileReady: (File) -> Unit) {
        viewModelScope.launch(bgDispatcher) {
            _uiState.update { it.copy(isLoading = true) }
            val outputDir = context.cacheDir.absolutePath
            lightningRepo.exportNetworkGraphToFile(outputDir).onSuccess { file ->
                Logger.info("Network graph exported to: ${file.absolutePath}", context = TAG)
                toaster.info(title = ToastText("Network graph exported"))
                onFileReady(file)
            }.onFailure { e ->
                Logger.error("Failed to export network graph", e, context = TAG)
                toaster.error(title = ToastText("Failed to export network graph"), body = e.message?.let { ToastText(it) })
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun listVssKeys() {
        viewModelScope.launch(bgDispatcher) {
            _uiState.update { it.copy(isLoading = true) }
            vssBackupClient.listKeys().onSuccess { keys ->
                Logger.info("VSS keys: ${keys.size}", context = TAG)
                _uiState.update { it.copy(vssKeys = keys) }
                toaster.info(title = ToastText("Found ${keys.size} VSS key(s)"))
            }.onFailure { e ->
                Logger.error("Failed to list VSS keys", e, context = TAG)
                toaster.error(title = ToastText("Failed to list VSS keys"), body = e.message?.let { ToastText(it) })
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun deleteAllVssKeys() {
        viewModelScope.launch(bgDispatcher) {
            _uiState.update { it.copy(isLoading = true) }
            vssBackupClient.deleteAllKeys().onSuccess { deletedCount ->
                Logger.info("Deleted $deletedCount VSS keys", context = TAG)
                _uiState.update { it.copy(vssKeys = emptyList()) }
                toaster.info(title = ToastText("Deleted $deletedCount VSS key(s)"))
            }.onFailure { e ->
                Logger.error("Failed to delete VSS keys", e, context = TAG)
                toaster.error(title = ToastText("Failed to delete VSS keys"), body = e.message?.let { ToastText(it) })
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun deleteVssKey(key: String) {
        viewModelScope.launch(bgDispatcher) {
            _uiState.update { it.copy(isLoading = true) }
            vssBackupClient.deleteObject(key)
                .onSuccess { wasDeleted ->
                    if (wasDeleted) {
                        Logger.info("Deleted VSS key: $key", context = TAG)
                        _uiState.update { state ->
                            state.copy(vssKeys = state.vssKeys.filter { it.key != key })
                        }
                        toaster.info(title = ToastText("Deleted key: $key"))
                    } else {
                        toaster.warn(title = ToastText("Key not found: $key"))
                    }
                }
                .onFailure { e ->
                    Logger.error("Failed to delete VSS key: $key", e, context = TAG)
                    toaster.error(title = ToastText("Failed to delete key"), body = e.message?.let { ToastText(it) })
                }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun restartNode() {
        viewModelScope.launch(bgDispatcher) {
            _uiState.update { it.copy(isLoading = true) }
            lightningRepo.restartNode()
                .onSuccess {
                    Logger.info("Node restarted successfully", context = TAG)
                    toaster.info(title = ToastText("Node restarted"))
                }
                .onFailure { e ->
                    Logger.error("Failed to restart node", e, context = TAG)
                    toaster.error(title = ToastText("Failed to restart node"), body = e.message?.let { ToastText(it) })
                }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    companion object {
        private const val TAG = "LdkDebugViewModel"
    }
}

@Stable
data class LdkDebugUiState(
    val nodeUri: String = "",
    val isLoading: Boolean = false,
    @Stable val vssKeys: List<KeyVersion> = emptyList(),
    val networkGraphInfo: NetworkGraphInfo? = null,
)

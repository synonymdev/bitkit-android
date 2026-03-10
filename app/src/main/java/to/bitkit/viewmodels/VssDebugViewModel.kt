package to.bitkit.viewmodels

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.vssclient.KeyVersion
import com.synonym.vssclient.LdkNamespace
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.data.backup.VssBackupClient
import to.bitkit.data.backup.VssBackupClientLdk
import to.bitkit.di.BgDispatcher
import to.bitkit.models.Toast
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import java.io.File
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

@Suppress("TooManyFunctions")
@HiltViewModel
class VssDebugViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val vssBackupClient: VssBackupClient,
    private val vssBackupClientLdk: VssBackupClientLdk,
) : ViewModel() {
    companion object {
        private const val TAG = "VssDebugViewModel"
        private const val DIR_EXPORTS = "vss_exports"
        private val DELAY_EXPORT_CLEANUP = 60.seconds
    }

    private val _uiState = MutableStateFlow(VssDebugUiState())
    val uiState = _uiState.asStateFlow()

    fun refreshAllKeys() {
        listVssKeys()
        listVssLdkKeys()
    }

    fun listVssKeys() {
        viewModelScope.launch(bgDispatcher) {
            _uiState.update { it.copy(isLoading = true) }
            vssBackupClient.listKeys().onSuccess { keys ->
                Logger.info("VSS keys: ${keys.size}", context = TAG)
                keys.forEach { Logger.debug("  ${it.key} v${it.version}", context = TAG) }
                _uiState.update { it.copy(vssKeys = keys.toImmutableList()) }
                ToastEventBus.send(
                    type = Toast.ToastType.INFO,
                    title = "Found ${keys.size} VSS key(s)",
                )
            }.onFailure {
                Logger.error("Failed to list VSS keys", it, context = TAG)
                ToastEventBus.send(
                    type = Toast.ToastType.ERROR,
                    title = "Failed to list VSS keys",
                    description = it.message,
                )
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun deleteAllVssKeys() {
        viewModelScope.launch(bgDispatcher) {
            _uiState.update { it.copy(isLoading = true) }
            vssBackupClient.deleteAllKeys().onSuccess { deletedCount ->
                Logger.info("Deleted $deletedCount VSS keys", context = TAG)
                _uiState.update { it.copy(vssKeys = persistentListOf()) }
                ToastEventBus.send(
                    type = Toast.ToastType.INFO,
                    title = "Deleted $deletedCount VSS key(s)",
                )
            }.onFailure {
                Logger.error("Failed to delete VSS keys", it, context = TAG)
                ToastEventBus.send(
                    type = Toast.ToastType.ERROR,
                    title = "Failed to delete VSS keys",
                    description = it.message,
                )
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
                            state.copy(vssKeys = state.vssKeys.filter { it.key != key }.toImmutableList())
                        }
                        ToastEventBus.send(
                            type = Toast.ToastType.INFO,
                            title = "Deleted key: $key",
                        )
                    } else {
                        ToastEventBus.send(
                            type = Toast.ToastType.WARNING,
                            title = "Key not found: $key",
                        )
                    }
                }
                .onFailure {
                    Logger.error("Failed to delete VSS key: $key", it, context = TAG)
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = "Failed to delete key",
                        description = it.message,
                    )
                }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun listVssLdkKeys() {
        viewModelScope.launch(bgDispatcher) {
            _uiState.update { it.copy(isLoading = true) }
            vssBackupClientLdk.listAllKeysTagged().onSuccess { tagged ->
                Logger.info("VSS LDK keys: ${tagged.size}", context = TAG)
                tagged.forEach { Logger.debug("  ${it.second.key} v${it.second.version}", context = TAG) }
                val items = tagged.map { VssLdkKeyItem(keyVersion = it.second, namespace = it.first) }.toImmutableList()
                _uiState.update { it.copy(vssLdkKeys = items) }
                ToastEventBus.send(
                    type = Toast.ToastType.INFO,
                    title = "Found ${tagged.size} VSS LDK key(s)",
                )
            }.onFailure {
                Logger.error("Failed to list VSS LDK keys", it, context = TAG)
                ToastEventBus.send(
                    type = Toast.ToastType.ERROR,
                    title = "Failed to list VSS LDK keys",
                    description = it.message,
                )
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun deleteVssLdkKey(key: String, namespace: LdkNamespace) {
        viewModelScope.launch(bgDispatcher) {
            _uiState.update { it.copy(isLoading = true) }
            vssBackupClientLdk.deleteObject(key, namespace)
                .onSuccess { wasDeleted ->
                    if (wasDeleted) {
                        Logger.info("Deleted VSS LDK key: '$key'", context = TAG)
                        _uiState.update { state ->
                            state.copy(
                                vssLdkKeys = state.vssLdkKeys.filter {
                                    it.keyVersion.key != key || it.namespace != namespace
                                }.toImmutableList()
                            )
                        }
                        ToastEventBus.send(
                            type = Toast.ToastType.INFO,
                            title = "Deleted LDK key: $key",
                        )
                    } else {
                        ToastEventBus.send(
                            type = Toast.ToastType.WARNING,
                            title = "Key not found: $key",
                        )
                    }
                }
                .onFailure {
                    Logger.error("Failed to delete VSS LDK key: '$key'", it, context = TAG)
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = "Failed to delete LDK key",
                        description = it.message,
                    )
                }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun shareVssLdkKey(key: String, namespace: LdkNamespace, onFileReady: (File) -> Unit) {
        viewModelScope.launch(bgDispatcher) {
            _uiState.update { it.copy(isLoading = true) }
            vssBackupClientLdk.getObject(key, namespace).onSuccess { item ->
                if (item != null) {
                    val cacheExportsDir = File(context.cacheDir, DIR_EXPORTS).apply { mkdirs() }
                    val file = File(cacheExportsDir, "vss_ldk_$key")
                    file.writeBytes(item.value)
                    Logger.info("VSS LDK key exported: '$key' of (${item.value.size} bytes)", context = TAG)
                    onFileReady(file)
                    viewModelScope.launch(bgDispatcher) {
                        delay(DELAY_EXPORT_CLEANUP)
                        if (file.exists()) {
                            file.delete()
                            Logger.verbose("VSS LDK file deleted: '$key'", context = TAG)
                        }
                    }
                } else {
                    ToastEventBus.send(
                        type = Toast.ToastType.WARNING,
                        title = "Key not found: $key",
                    )
                }
            }.onFailure {
                Logger.error("Failed to get VSS LDK key: $key", it, context = TAG)
                ToastEventBus.send(
                    type = Toast.ToastType.ERROR,
                    title = "Failed to get VSS LDK key",
                    description = it.message,
                )
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        File(context.cacheDir, DIR_EXPORTS).deleteRecursively()
    }
}

@Immutable
data class VssDebugUiState(
    val isLoading: Boolean = false,
    val vssKeys: ImmutableList<KeyVersion> = persistentListOf(),
    val vssLdkKeys: ImmutableList<VssLdkKeyItem> = persistentListOf(),
)

data class VssLdkKeyItem(
    val keyVersion: KeyVersion,
    val namespace: LdkNamespace,
)

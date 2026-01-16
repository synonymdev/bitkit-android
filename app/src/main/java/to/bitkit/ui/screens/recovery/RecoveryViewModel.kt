package to.bitkit.ui.screens.recovery

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.data.SettingsStore
import to.bitkit.env.Env
import to.bitkit.models.Toast
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LogsRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class RecoveryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logsRepo: LogsRepo,
    private val lightningRepo: LightningRepo,
    private val walletRepo: WalletRepo,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecoveryUiState())
    val uiState: StateFlow<RecoveryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                settingsStore.data,
                walletRepo.walletState
            ) { settingsData, walletState ->
                _uiState.value.copy(
                    isPinEnabled = settingsData.isPinEnabled,
                    walletExists = walletState.walletExists,
                )
            }.collect { newState ->
                _uiState.update { newState }
            }
        }
    }

    fun disableRecoveryMode() {
        lightningRepo.setRecoveryMode(false)
    }

    fun onExportLogs() {
        viewModelScope.launch {
            _uiState.update { it.copy(isExportingLogs = true) }

            logsRepo.zipLogsForSharing().fold(
                onSuccess = { uri ->
                    shareLogsFile(uri)
                    _uiState.update { it.copy(isExportingLogs = false) }
                },
                onFailure = { error ->
                    Logger.error("Failed to export logs", error, context = TAG)
                    _uiState.update {
                        it.copy(
                            isExportingLogs = false,
                        )
                    }
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = context.getString(R.string.common__error),
                        description = context.getString(R.string.other__logs_export_error),
                    )
                }
            )
        }
    }

    fun onContactSupport() {
        val supportIntent = createSupportIntent()
        runCatching {
            context.startActivity(supportIntent)
        }.onFailure { e ->
            Logger.warn("Failed to open email client, trying web fallback", e, context = TAG)
            // Fallback to web contact page
            val fallbackIntent = Intent(Intent.ACTION_VIEW, Env.SYNONYM_CONTACT.toUri()).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                context.startActivity(fallbackIntent)
            }.onFailure { fallbackError ->
                Logger.error("Failed to open support links", fallbackError, context = TAG)
                viewModelScope.launch {
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = context.getString(R.string.common__error),
                        description = context.getString(R.string.settings__support__link_error),
                    )
                }
            }
        }
    }

    fun showWipeConfirmation() {
        _uiState.update { it.copy(showWipeConfirmation = true) }
    }

    fun hideWipeConfirmation() {
        _uiState.update { it.copy(showWipeConfirmation = false) }
    }

    fun wipeWallet() {
        viewModelScope.launch {
            walletRepo.wipeWallet().onFailure { error ->
                ToastEventBus.send(error)
            }.onSuccess {
                ToastEventBus.send(
                    type = Toast.ToastType.SUCCESS,
                    title = context.getString(R.string.security__wiped_title),
                    description = context.getString(R.string.security__wiped_message),
                )
            }
        }
    }

    fun setAuthAction(authAction: PendingAuthAction) {
        _uiState.update { it.copy(authAction = authAction) }
    }

    private fun shareLogsFile(uri: Uri) {
        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, SUBJECT)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val chooserIntent = Intent.createChooser(
            shareIntent,
            SUBJECT
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(chooserIntent)
    }

    private fun createSupportIntent(): Intent {
        val subject = SUBJECT
        val body = buildSupportEmailBody()

        return Intent(Intent.ACTION_SENDTO).apply {
            data = "mailto:".toUri()
            putExtra(Intent.EXTRA_EMAIL, arrayOf(Env.SUPPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun buildSupportEmailBody(): String {
        return buildString {
            appendLine("Platform: ${Env.platform}")
            appendLine("Version: ${Env.version}")

            val nodeId = lightningRepo.lightningState.value.nodeId
            appendLine("LDK node ID: $nodeId")
            appendLine()
        }
    }

    private companion object {
        const val TAG = "RecoveryViewModel"
        private const val SUBJECT = "Bitkit Support"
    }
}

data class RecoveryUiState(
    val isExportingLogs: Boolean = false,
    val showWipeConfirmation: Boolean = false,
    val errorMessage: String? = null,
    val authAction: PendingAuthAction = PendingAuthAction.None,
    val isPinEnabled: Boolean = false,
    val walletExists: Boolean = true,
)

sealed interface PendingAuthAction {
    object None : PendingAuthAction
    object ShowSeed : PendingAuthAction
    object WipeApp : PendingAuthAction
}

package to.bitkit.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.data.AppStorage
import to.bitkit.models.BackupCategory
import to.bitkit.models.BackupItemStatus
import javax.inject.Inject

@HiltViewModel
class BackupSettingsViewModel @Inject constructor(
    private val appStorage: AppStorage,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupStatusUiState())
    val uiState = _uiState.asStateFlow()

    init {
        observeBackupStatuses()
    }

    private fun observeBackupStatuses() {
        viewModelScope.launch {
            appStorage.backupStatuses.collect { cachedStatuses ->
                val categories = BackupCategory.entries.map { category ->
                    val cachedStatus = cachedStatuses[category] ?: BackupItemStatus(synced = 0, required = 1)
                    category.toUiState(cachedStatus).let { uiState ->
                        when (category) {
                            BackupCategory.LDK_ACTIVITY -> uiState.copy(disableRetry = true)
                            else -> uiState
                        }
                    }
                }
                _uiState.update { it.copy(categories = categories) }
            }
        }
    }

    fun retryBackup(category: BackupCategory) {
        viewModelScope.launch {
            appStorage.updateBackupStatus(category) { currentStatus ->
                currentStatus.copy(
                    running = true,
                    required = System.currentTimeMillis(),
                )
            }

            // TODO: Implement actual retry logic
            delay(2000) // Simulate backup completion after 2 seconds

            appStorage.updateBackupStatus(category) {
                BackupItemStatus(
                    running = false,
                    synced = System.currentTimeMillis(),
                )
            }
        }
    }
}

data class BackupCategoryUiState(
    val category: BackupCategory,
    val status: BackupItemStatus,
    val disableRetry: Boolean = false,
)

data class BackupStatusUiState(
    val categories: List<BackupCategoryUiState> = emptyList(),
)

fun BackupCategory.toUiState(status: BackupItemStatus = BackupItemStatus()): BackupCategoryUiState {
    return BackupCategoryUiState(
        category = this,
        status = status,
    )
}

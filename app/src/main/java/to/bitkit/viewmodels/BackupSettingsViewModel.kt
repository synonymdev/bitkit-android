package to.bitkit.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.data.AppStorage
import to.bitkit.models.BackupCategory
import to.bitkit.models.BackupItemStatus
import to.bitkit.repositories.BackupRepo
import javax.inject.Inject

@HiltViewModel
class BackupSettingsViewModel @Inject constructor(
    private val appStorage: AppStorage,
    private val backupRepo: BackupRepo,
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
            backupRepo.triggerBackup(category)
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

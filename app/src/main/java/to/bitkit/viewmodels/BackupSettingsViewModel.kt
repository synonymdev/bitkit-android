package to.bitkit.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.models.BackupCategory
import to.bitkit.models.BackupItemStatus
import javax.inject.Inject

@HiltViewModel
class BackupSettingsViewModel @Inject constructor(
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupStatusUiState())
    val uiState = _uiState.asStateFlow()

    init {
        loadBackupStatus()
    }

    private fun loadBackupStatus() {
        viewModelScope.launch {
            val categories = BackupCategory.entries
                .map { it.toUiState() }
                .map {
                    // TODO: Replace with actual backup state from repository
                    when (it.category) {
                        BackupCategory.LDK_ACTIVITY -> it.copy(disableRetry = true)
                        BackupCategory.WALLET -> it.copy(status = BackupItemStatus(running = true, required = 1))
                        BackupCategory.METADATA -> it.copy(status = BackupItemStatus(required = 1))
                        else -> it
                    }
                }

            _uiState.update {
                BackupStatusUiState(categories = categories)
            }
        }
    }

    fun retryBackup(category: BackupCategory) {
        viewModelScope.launch {
            // TODO: Implement actual retry logic
            val currentState = _uiState.value
            val updatedCategories = currentState.categories.map { categoryState ->
                if (categoryState.category == category) {
                    categoryState.copy(
                        status = categoryState.status.copy(running = true)
                    )
                } else {
                    categoryState
                }
            }

            _uiState.update { currentState.copy(categories = updatedCategories) }

            // Simulate backup completion after 2 seconds
            delay(2000)

            val finalState = _uiState.value
            val finalCategories = finalState.categories.map { categoryState ->
                if (categoryState.category == category) {
                    categoryState.copy(
                        status = BackupItemStatus(
                            synced = System.currentTimeMillis(),
                            required = System.currentTimeMillis()
                        )
                    )
                } else {
                    categoryState
                }
            }

            _uiState.update { finalState.copy(categories = finalCategories) }
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

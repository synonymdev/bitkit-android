package to.bitkit.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.models.BackupCategory
import to.bitkit.models.BackupItemStatus
import javax.inject.Inject

@HiltViewModel
class BackupStatusViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(BackupStatusUiState())
    val uiState = _uiState.asStateFlow()

    init {
        loadBackupStatus()
    }

    private fun loadBackupStatus() {
        viewModelScope.launch {
            // TODO: Replace with actual backup state from repository
            val mockCategories = listOf(
                BackupCategoryUiState(
                    category = BackupCategory.LIGHTNING_CONNECTIONS,
                    status = BackupItemStatus(
                        synced = System.currentTimeMillis() - 300000,
                        required = System.currentTimeMillis() - 300000
                    ),
                    disableRetry = true,
                ),
                BackupCategoryUiState(
                    category = BackupCategory.BLOCKTANK,
                    status = BackupItemStatus(
                        synced = System.currentTimeMillis() - 600000,
                        required = System.currentTimeMillis() - 600000
                    ),
                ),
                BackupCategoryUiState(
                    category = BackupCategory.LDK_ACTIVITY,
                    status = BackupItemStatus(
                        synced = System.currentTimeMillis() - 600000,
                        required = System.currentTimeMillis() - 600000
                    ),
                ),
                BackupCategoryUiState(
                    category = BackupCategory.WALLET,
                    status = BackupItemStatus(
                        synced = System.currentTimeMillis() - 1800000,
                        required = System.currentTimeMillis() - 1800000
                    ),
                ),
                BackupCategoryUiState(
                    category = BackupCategory.SETTINGS,
                    status = BackupItemStatus(synced = 0, required = 1),
                ),
                BackupCategoryUiState(
                    category = BackupCategory.WIDGETS,
                    status = BackupItemStatus(
                        synced = System.currentTimeMillis() - 900000,
                        required = System.currentTimeMillis() - 900000
                    ),
                ),
                BackupCategoryUiState(
                    category = BackupCategory.METADATA,
                    status = BackupItemStatus(
                        synced = System.currentTimeMillis() - 1200000,
                        required = System.currentTimeMillis() - 1200000
                    ),
                ),
                BackupCategoryUiState(
                    category = BackupCategory.SLASHTAGS,
                    status = BackupItemStatus(
                        synced = System.currentTimeMillis() - 2100000,
                        required = System.currentTimeMillis() - 2100000
                    ),
                ),
            )

            _uiState.update {
                BackupStatusUiState(categories = mockCategories)
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
            kotlinx.coroutines.delay(2000)

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

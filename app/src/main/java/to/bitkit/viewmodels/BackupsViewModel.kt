package to.bitkit.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.data.CacheStore
import to.bitkit.models.BackupCategory
import to.bitkit.models.BackupItemStatus
import to.bitkit.models.NodeLifecycleState
import to.bitkit.repositories.BackupsRepo
import to.bitkit.repositories.LightningRepo
import javax.inject.Inject

@HiltViewModel
class BackupsViewModel @Inject constructor(
    private val cacheStore: CacheStore,
    private val backupsRepo: BackupsRepo,
    private val lightningRepo: LightningRepo,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupStatusUiState())
    val uiState = _uiState.asStateFlow()

    init {
        collectState()
    }

    private fun collectState() {
        viewModelScope.launch {
            cacheStore.backupStatuses.collect { cachedStatuses ->
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

    fun observeAndSyncBackups() {
        viewModelScope.launch {
            lightningRepo.lightningState.collect { lightningState ->
                when (lightningState.nodeLifecycleState) {
                    NodeLifecycleState.Running -> backupsRepo.startObservingBackups()
                    else -> backupsRepo.stopObservingBackups()
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        backupsRepo.stopObservingBackups()
    }

    fun retryBackup(category: BackupCategory) {
        viewModelScope.launch {
            delay(500) // small delay for UX feedback
            backupsRepo.triggerBackup(category)
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

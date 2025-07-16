package to.bitkit.ui.settings.appStatus

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.data.CacheStore
import to.bitkit.ext.toLocalizedTimestamp
import to.bitkit.models.BackupCategory
import to.bitkit.models.BackupItemStatus
import to.bitkit.models.HealthState
import to.bitkit.repositories.AppHealthState
import to.bitkit.repositories.HealthRepo
import to.bitkit.repositories.LightningRepo
import javax.inject.Inject

@HiltViewModel
class AppStatusViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val healthRepo: HealthRepo,
    private val lightningRepo: LightningRepo,
    private val cacheStore: CacheStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppStatusUiState())
    val uiState: StateFlow<AppStatusUiState> = _uiState.asStateFlow()

    init {
        collectState()
    }

    private fun collectState() {
        viewModelScope.launch {
            combine(
                healthRepo.healthState,
                cacheStore.backupStatuses,
                lightningRepo.lightningState,
            ) { healthState, backupStatuses, lightningState ->
                AppStatusUiState(
                    health = healthState,
                    backupSubtitle = computeBackupSubtitle(healthState.backups, backupStatuses),
                    nodeSubtitle = lightningState.nodeLifecycleState.uiText,
                )
            }.collect { newState ->
                _uiState.value = newState
            }
        }
    }

    private fun computeBackupSubtitle(
        backupHealthState: HealthState,
        backupStatuses: Map<BackupCategory, BackupItemStatus>,
    ): String {
        return when (backupHealthState) {
            HealthState.ERROR -> context.getString(R.string.settings__status__backup__error)
            else -> {
                val syncTimes = BackupCategory.entries
                    .filter { it != BackupCategory.LIGHTNING_CONNECTIONS }
                    .map { category -> backupStatuses[category]?.synced ?: 0L }

                when (val maxSyncTime = syncTimes.max()) {
                    0L -> context.getString(R.string.settings__status__backup__ready)
                    else -> runCatching { maxSyncTime.toLocalizedTimestamp() }
                        .getOrDefault(
                            context.getString(R.string.settings__status__backup__ready)
                        )
                }
            }
        }
    }
}

data class AppStatusUiState(
    val health: AppHealthState = AppHealthState(),
    val backupSubtitle: String = "",
    val nodeSubtitle: String = "",
)

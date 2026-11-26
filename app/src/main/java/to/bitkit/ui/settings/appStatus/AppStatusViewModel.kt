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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.data.CacheStore
import to.bitkit.env.Env
import to.bitkit.ext.toBackupTimestamp
import to.bitkit.models.BackupCategory
import to.bitkit.models.BackupItemStatus
import to.bitkit.models.HealthState
import to.bitkit.repositories.AppHealthState
import to.bitkit.repositories.HealthRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.SparkExitFundingState
import to.bitkit.repositories.SparkExitRecord
import to.bitkit.repositories.SparkExitRepo
import to.bitkit.repositories.SparkRepo
import javax.inject.Inject
import android.text.format.DateFormat as AndroidDateFormat

@HiltViewModel
class AppStatusViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val healthRepo: HealthRepo,
    private val lightningRepo: LightningRepo,
    private val sparkExitRepo: SparkExitRepo,
    private val sparkRepo: SparkRepo,
    private val cacheStore: CacheStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppStatusUiState())
    val uiState: StateFlow<AppStatusUiState> = _uiState.asStateFlow()

    init {
        collectState()
    }

    private fun collectState() = viewModelScope.launch {
        combine(
            healthRepo.healthState,
            cacheStore.backupStatuses,
            lightningRepo.lightningState,
            sparkExitRepo.state,
            sparkRepo.state,
        ) { healthState, backupStatuses, lightningState, exitState, sparkState ->
            AppStatusUiState(
                health = healthState,
                backupSubtitle = computeBackupSubtitle(healthState.backups, backupStatuses),
                nodeSubtitle = when (healthState.node) {
                    HealthState.ERROR -> context.getString(R.string.settings__status__lightning_node__error)
                    HealthState.READY -> context.getString(R.string.settings__status__lightning_node__ready)
                    else -> lightningState.nodeLifecycleState.uiText(context)
                },
                exitStep = exitState.record.exitStep(exitState.fundingState),
                hasSparkState = sparkState.hasReadState,
            )
        }.collect { newState ->
            _uiState.update { newState }
        }
    }

    private fun computeBackupSubtitle(
        backupHealthState: HealthState,
        backupStatuses: Map<BackupCategory, BackupItemStatus>,
    ) = when (backupHealthState) {
        HealthState.ERROR -> context.getString(R.string.settings__status__backup__error)
        else -> {
            val syncTimes = BackupCategory.entries
                .filter { it != BackupCategory.LIGHTNING_CONNECTIONS }
                .map { category -> backupStatuses[category]?.synced ?: 0L }

            when (val maxSyncTime = syncTimes.max()) {
                0L -> context.getString(R.string.settings__status__backup__ready)
                else -> runCatching {
                    maxSyncTime.toBackupTimestamp(
                        is24Hour = AndroidDateFormat.is24HourFormat(context),
                        template = context.getString(R.string.settings__status__backup__date),
                    )
                }.getOrDefault(context.getString(R.string.settings__status__backup__ready))
            }
        }
    }
}

data class AppStatusUiState(
    val health: AppHealthState = AppHealthState(),
    val backupSubtitle: String = "",
    val nodeSubtitle: String = "",
    /** Null while no emergency exit runs. */
    val exitStep: ExitStep? = null,
    /** Spark read its state, so its detail shows the last known balance and payments, also while it is offline. */
    val hasSparkState: Boolean = false,
    /** The app's version and build, as "3.2.0 (182)". */
    val appVersion: String = Env.version,
)

enum class ExitStep { FUNDING, RUNNING }

/** Asks for fee funding only while no payment covers it, as the progress screen does. */
internal fun SparkExitRecord?.exitStep(funding: SparkExitFundingState? = null): ExitStep? {
    val record = this?.takeIf { it.isActive } ?: return null
    // Before the first tick reads the funding, a savings payment the exit has not adopted yet counts as paid,
    // as on iOS.
    val isPaymentPending = record.fundingPaymentTxid?.let { txid -> record.funding.none { it.txid == txid } } == true
    val isAskingFunding = record.fundingShortfallSats != null &&
        if (funding == null) !isPaymentPending else funding is SparkExitFundingState.Ask
    return if (isAskingFunding) ExitStep.FUNDING else ExitStep.RUNNING
}

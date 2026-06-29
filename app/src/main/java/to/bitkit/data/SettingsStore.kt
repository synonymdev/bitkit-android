package to.bitkit.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import to.bitkit.data.serializers.SettingsSerializer
import to.bitkit.env.Env
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.CoinSelectionPreference
import to.bitkit.models.DEFAULT_ADDRESS_TYPE_STRING
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.SettingsBackupV1
import to.bitkit.models.Suggestion
import to.bitkit.models.TransactionSpeed
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<SettingsData> by dataStore(
    fileName = "settings.json",
    serializer = SettingsSerializer,
)

private val Context.localSettingsDataStore: DataStore<Preferences> by preferencesDataStore("local_settings")

@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store = context.settingsDataStore
    private val localStore = context.localSettingsDataStore

    val data: Flow<SettingsData> = store.data
    val isPaykitEnabled: Flow<Boolean> = localStore.data.map { it[PAYKIT_ENABLED_KEY] ?: false }

    @Volatile
    var restoredMonitoredTypesFromBackup: Boolean = false
        private set

    suspend fun restoreFromBackup(payload: SettingsBackupV1) =
        runCatching {
            val data = payload.settings.resetPin()
            store.updateData { data }

            val monitored = data.addressTypesToMonitor
            val selected = data.selectedAddressType
            restoredMonitoredTypesFromBackup = monitored.size > 1 ||
                (monitored.size == 1 && monitored.first() != selected)
        }.onSuccess {
            Logger.debug("Restored settings, monitoredFromBackup=$restoredMonitoredTypesFromBackup", context = TAG)
        }

    suspend fun update(transform: (SettingsData) -> SettingsData) {
        store.updateData(transform)
    }

    suspend fun setIsPaykitEnabled(value: Boolean) {
        localStore.edit { it[PAYKIT_ENABLED_KEY] = value }
    }

    suspend fun addLastUsedTag(newTag: String) {
        store.updateData { currentSettings ->
            val combinedTags = (listOf(newTag) + currentSettings.lastUsedTags).distinct()
            val limitedTags = combinedTags.take(MAX_LAST_USED_TAGS)
            currentSettings.copy(lastUsedTags = limitedTags)
        }
    }

    suspend fun deleteLastUsedTag(tag: String) {
        store.updateData { currentSettings ->
            currentSettings.copy(lastUsedTags = currentSettings.lastUsedTags.filter { it != tag })
        }
    }

    suspend fun addDismissedSuggestion(suggestion: Suggestion) {
        store.updateData { currentSettings ->
            val updatedDismissedSuggestions = (currentSettings.dismissedSuggestions + suggestion.name).distinct()
            currentSettings.copy(dismissedSuggestions = updatedDismissedSuggestions)
        }
    }

    suspend fun reset() {
        store.updateData { SettingsData() }
        localStore.edit { it.clear() }
        restoredMonitoredTypesFromBackup = false
        Logger.info("Deleted all user settings data.")
    }

    companion object {
        private const val TAG = "SettingsStore"
        private const val MAX_LAST_USED_TAGS = 10
        private val PAYKIT_ENABLED_KEY = booleanPreferencesKey("paykit_enabled")
    }
}

@Serializable
data class SettingsData(
    val primaryDisplay: PrimaryDisplay = PrimaryDisplay.BITCOIN,
    val displayUnit: BitcoinDisplayUnit = BitcoinDisplayUnit.MODERN,
    val selectedCurrency: String = "USD",
    val defaultTransactionSpeed: TransactionSpeed = TransactionSpeed.default(),
    val showEmptyBalanceView: Boolean = true,
    val hasSeenSpendingIntro: Boolean = false,
    val hasSeenWidgetsIntro: Boolean = false,
    val hasSeenTransferIntro: Boolean = false,
    val hasSeenSavingsIntro: Boolean = false,
    val hasSeenShopIntro: Boolean = false,
    val hasSeenProfileIntro: Boolean = false,
    val hasSeenContactsIntro: Boolean = false,
    val hasConfirmedPublicPaykitEndpoints: Boolean = false,
    val sharesPublicPaykitEndpoints: Boolean = false,
    val sharesPrivatePaykitEndpoints: Boolean = false,
    val publicPaykitCleanupPending: Boolean = false,
    val publicPaykitLightningEnabled: Boolean = true,
    val publicPaykitOnchainEnabled: Boolean = true,
    val publicPaykitBolt11: String = "",
    val publicPaykitBolt11PaymentHash: String = "",
    val publicPaykitBolt11ExpiresAtMillis: Long = 0,
    val quickPayIntroSeen: Boolean = false,
    val bgPaymentsIntroSeen: Boolean = false,
    val isQuickPayEnabled: Boolean = false,
    val quickPayAmount: Int = 5,
    val lightningSetupStep: Int = 0,
    val isPinEnabled: Boolean = false,
    val isBiometricEnabled: Boolean = false,
    val isPinForPaymentsEnabled: Boolean = false,
    val isDevModeEnabled: Boolean = Env.isDebug,
    val showWidgets: Boolean = true,
    val lastUsedTags: List<String> = emptyList(),
    val enableSwipeToHideBalance: Boolean = true,
    val hideBalance: Boolean = false,
    val hideBalanceOnOpen: Boolean = false,
    val enableAutoReadClipboard: Boolean = false,
    val enableSendAmountWarning: Boolean = false,
    val backupVerified: Boolean = false,
    val notificationsGranted: Boolean = false,
    val keepBitkitActiveInBackground: Boolean = false,
    val dismissedSuggestions: List<String> = emptyList(),
    val balanceWarningIgnoredMillis: Long = 0,
    val backupWarningIgnoredMillis: Long = 0,
    val notificationsIgnoredMillis: Long = 0,
    val balanceWarningTimes: Int = 0,
    val widgetsOnboardingHintDismissed: Boolean = false,
    val coinSelectAuto: Boolean = true,
    val coinSelectPreference: CoinSelectionPreference = CoinSelectionPreference.BranchAndBound,
    val electrumServer: String = Env.electrumServerUrl,
    val rgsServerUrl: String? = Env.ldkRgsServerUrl,
    val selectedAddressType: String = DEFAULT_ADDRESS_TYPE_STRING,
    val addressTypesToMonitor: List<String> = listOf(DEFAULT_ADDRESS_TYPE_STRING),
    val pendingRestoreAddressTypePrune: Boolean = false,
)

fun SettingsData.resetPin() = this.copy(
    isPinEnabled = false,
    isPinForPaymentsEnabled = false,
    isBiometricEnabled = false,
)

fun SettingsData.hasPublicPaykitPublicationState(): Boolean =
    hasConfirmedPublicPaykitEndpoints ||
        sharesPublicPaykitEndpoints ||
        publicPaykitCleanupPending ||
        publicPaykitBolt11.isNotBlank() ||
        publicPaykitBolt11PaymentHash.isNotBlank() ||
        publicPaykitBolt11ExpiresAtMillis > 0L

fun SettingsData.hasPaykitState(): Boolean =
    hasPublicPaykitPublicationState() ||
        sharesPrivatePaykitEndpoints

fun SettingsData.paykitDisabled(markPublicCleanupPending: Boolean = false) = copy(
    hasConfirmedPublicPaykitEndpoints = false,
    sharesPublicPaykitEndpoints = false,
    sharesPrivatePaykitEndpoints = false,
    publicPaykitCleanupPending = publicPaykitCleanupPending || markPublicCleanupPending,
    publicPaykitBolt11 = "",
    publicPaykitBolt11PaymentHash = "",
    publicPaykitBolt11ExpiresAtMillis = 0,
)

package to.bitkit.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import to.bitkit.data.serializers.SettingsSerializer
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.CoinSelectionPreference
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.Suggestion
import to.bitkit.models.TransactionSpeed
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store: DataStore<SettingsData> = DataStoreFactory.create(
        serializer = SettingsSerializer,
        produceFile = { context.dataStoreFile("settings.json") },
    )

    val data: Flow<SettingsData> = store.data

    suspend fun update(transform: (SettingsData) -> SettingsData) {
        store.updateData(transform)
    }

    suspend fun addLastUsedTag(newTag: String) {
        store.updateData { currentSettings ->
            val combinedTags = (listOf(newTag) + currentSettings.lastUsedTags).distinct()
            val limitedTags = combinedTags.take(10)
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
        Logger.info("Deleted all user settings data.")
    }
}

@Serializable
data class SettingsData(
    val primaryDisplay: PrimaryDisplay = PrimaryDisplay.BITCOIN,
    val displayUnit: BitcoinDisplayUnit = BitcoinDisplayUnit.MODERN,
    val selectedCurrency: String = "USD",
    val defaultTransactionSpeed: TransactionSpeed = TransactionSpeed.Medium,
    val showEmptyState: Boolean = false,
    val hasSeenSpendingIntro: Boolean = false,
    val hasSeenWidgetsIntro: Boolean = false,
    val hasSeenTransferIntro: Boolean = false,
    val hasSeenSavingsIntro: Boolean = false,
    val hasSeenShopIntro: Boolean = false,
    val hasSeenProfileIntro: Boolean = false,
    val quickPayIntroSeen: Boolean = false,
    val isQuickPayEnabled: Boolean = false,
    val quickPayAmount: Int = 5,
    val lightningSetupStep: Int = 0,
    val isPinEnabled: Boolean = false,
    val isPinOnLaunchEnabled: Boolean = false,
    val isBiometricEnabled: Boolean = false,
    val isPinOnIdleEnabled: Boolean = false,
    val isPinForPaymentsEnabled: Boolean = false,
    val isDevModeEnabled: Boolean = false,
    val showWidgets: Boolean = false,
    val showWidgetTitles: Boolean = false,
    val lastUsedTags: List<String> = emptyList(),
    val enableSwipeToHideBalance: Boolean = true,
    val hideBalance: Boolean = false,
    val hideBalanceOnOpen: Boolean = false,
    val enableAutoReadClipboard: Boolean = false,
    val enableSendAmountWarning: Boolean = false,
    val backupVerified: Boolean = false,
    val dismissedSuggestions: List<String> = emptyList(),
    val coinSelectAuto: Boolean = true,
    val coinSelectPreference: CoinSelectionPreference = CoinSelectionPreference.SmallestFirst,
)

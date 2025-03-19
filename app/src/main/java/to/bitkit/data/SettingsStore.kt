package to.bitkit.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import to.bitkit.ext.enumValueOfOrNull
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.PrimaryDisplay
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore by preferencesDataStore(name = "settings")

@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store = context.settingsStore

    val primaryDisplay: Flow<PrimaryDisplay> = store.data
        .map {
            it[PRIMARY_DISPLAY_UNIT_KEY]?.let { x -> enumValueOfOrNull<PrimaryDisplay>(x) } ?: PrimaryDisplay.BITCOIN
        }

    suspend fun setPrimaryDisplayUnit(display: PrimaryDisplay) {
        store.edit { it[PRIMARY_DISPLAY_UNIT_KEY] = display.name }
    }

    val displayUnit: Flow<BitcoinDisplayUnit> = store.data
        .map {
            it[BTC_DISPLAY_UNIT_KEY]?.let { x -> enumValueOfOrNull<BitcoinDisplayUnit>(x) } ?: BitcoinDisplayUnit.MODERN
        }

    suspend fun setBtcDisplayUnit(unit: BitcoinDisplayUnit) {
        store.edit { it[BTC_DISPLAY_UNIT_KEY] = unit.name }
    }

    val selectedCurrency: Flow<String> = store.data.map { it[SELECTED_CURRENCY_KEY] ?: "USD" }

    suspend fun setSelectedCurrency(currency: String) {
        store.edit { it[SELECTED_CURRENCY_KEY] = currency }
    }

    val showEmptyState: Flow<Boolean> = store.data.map { it[SHOW_EMPTY_STATE] ?: false }
    suspend fun setShowEmptyState(show: Boolean) {
        store.edit { it[SHOW_EMPTY_STATE] = show }
    }

    val hasSeenSpendingIntro: Flow<Boolean> = store.data.map { it[HAS_SEEN_SPENDING_INTRO] ?: false }
    suspend fun setHasSeenSpendingIntro(value: Boolean) {
        store.edit { it[HAS_SEEN_SPENDING_INTRO] = value }
    }

    val hasSeenSavingsIntro: Flow<Boolean> = store.data.map { it[HAS_SEEN_SAVINGS_INTRO] ?: false }
    suspend fun setHasSeenSavingsIntro(value: Boolean) {
        store.edit { it[HAS_SEEN_SAVINGS_INTRO] = value }
    }

    val lightningSetupStep: Flow<Int> = store.data.map { it[LIGHTNING_SETUP_STEP] ?: 0 }
    suspend fun setLightningSetupStep(value: Int) {
        store.edit { it[LIGHTNING_SETUP_STEP] = value }
    }

    private companion object {
        private val PRIMARY_DISPLAY_UNIT_KEY = stringPreferencesKey("primary_display_unit")
        private val BTC_DISPLAY_UNIT_KEY = stringPreferencesKey("btc_display_unit")
        private val SELECTED_CURRENCY_KEY = stringPreferencesKey("selected_currency")
        private val SHOW_EMPTY_STATE = booleanPreferencesKey("show_empty_state")
        private val HAS_SEEN_SPENDING_INTRO = booleanPreferencesKey("has_seen_spending_intro")
        private val HAS_SEEN_SAVINGS_INTRO = booleanPreferencesKey("has_seen_savings_intro")
        private val LIGHTNING_SETUP_STEP = intPreferencesKey("lightning_setup_step")
    }
}

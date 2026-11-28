package to.bitkit.wear.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import to.bitkit.wear.models.BitcoinDisplayUnit
import to.bitkit.wear.models.PhoneSettings
import to.bitkit.wear.models.PrimaryDisplay
import to.bitkit.wear.models.QuickPayLimits
import to.bitkit.wear.models.USD
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The watch's settings file. One that cannot be read is replaced by an empty one, so a corrupted file never blocks
 * the launch or a wallet removal. The note of a wipe under way is not in it: see [WipeMarker].
 */
fun settingsDataStore(scope: CoroutineScope, produceFile: () -> File): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
        produceFile = produceFile,
    )

/**
 * The watch's display preferences, what it imported from the phone's settings, and the wallet wipe's progress notes.
 * It holds nothing secret.
 */
@Singleton
class SettingsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val wipeMarker: WipeMarker,
) {
    private val primaryDisplayKey = stringPreferencesKey("primary_display")
    private val selectedCurrencyKey = stringPreferencesKey("selected_currency")
    private val removedNoticeKey = booleanPreferencesKey("wallet_removed_notice")
    private val displayUnitKey = stringPreferencesKey("display_unit")
    private val phonePrimaryDisplayKey = stringPreferencesKey("phone_primary_display")
    private val quickPayEnabledKey = booleanPreferencesKey("quickpay_enabled")
    private val quickPayAmountKey = intPreferencesKey("quickpay_amount_usd")
    private val quickPayMultiplierKey = intPreferencesKey("quickpay_daily_multiplier")
    private val phoneSyncedAtKey = longPreferencesKey("phone_settings_synced_at")

    val data: Flow<WatchSettings> = dataStore.data.map { prefs ->
        WatchSettings(
            primaryDisplay = prefs[primaryDisplayKey]
                ?.let { runCatching { PrimaryDisplay.valueOf(it) }.getOrNull() }
                ?: PrimaryDisplay.BITCOIN,
            selectedCurrency = prefs[selectedCurrencyKey] ?: USD,
            displayUnit = prefs[displayUnitKey]
                ?.let { runCatching { BitcoinDisplayUnit.valueOf(it) }.getOrNull() }
                ?: BitcoinDisplayUnit.MODERN,
            quickPay = quickPay(prefs),
            phoneSyncedAtMs = prefs[phoneSyncedAtKey],
        )
    }

    private fun quickPay(prefs: Preferences): QuickPayLimits? {
        val amountUsd = prefs[quickPayAmountKey] ?: return null
        val multiplier = prefs[quickPayMultiplierKey] ?: return null
        return QuickPayLimits(prefs[quickPayEnabledKey] == true, amountUsd, multiplier)
    }

    /**
     * Adopts [settings], read from the phone's backup at [syncedAtMs]. A display value the backup does not hold
     * keeps the watch's. The phone's primary display is adopted when it differs from the one last imported, so the
     * wearer's own swap on the balance lasts until the phone's setting changes. QuickPay is replaced as a whole and
     * is off when the backup's values cannot be read.
     */
    suspend fun applyPhoneSettings(settings: PhoneSettings, syncedAtMs: Long) {
        dataStore.edit { prefs ->
            settings.primaryDisplay?.let {
                if (prefs[phonePrimaryDisplayKey] != it.name) prefs[primaryDisplayKey] = it.name
                prefs[phonePrimaryDisplayKey] = it.name
            }
            settings.displayUnit?.let { prefs[displayUnitKey] = it.name }
            settings.selectedCurrency?.let { prefs[selectedCurrencyKey] = it }
            val quickPay = settings.quickPay
            if (quickPay == null) {
                prefs.clearQuickPay()
            } else {
                prefs[quickPayEnabledKey] = quickPay.isEnabled
                prefs[quickPayAmountKey] = quickPay.amountUsd
                prefs[quickPayMultiplierKey] = quickPay.dailyMultiplier
            }
            prefs[phoneSyncedAtKey] = syncedAtMs
        }
    }

    /**
     * Turns QuickPay off and forgets when the phone's settings were last read, as when the phone's backup is missing
     * or cannot be read. The display values stay.
     */
    suspend fun clearQuickPay() {
        dataStore.edit { prefs ->
            prefs.clearQuickPay()
            prefs.remove(phoneSyncedAtKey)
        }
    }

    private fun MutablePreferences.clearQuickPay() {
        remove(quickPayEnabledKey)
        remove(quickPayAmountKey)
        remove(quickPayMultiplierKey)
    }

    /** Forgets everything imported from the phone, back to the watch's defaults: an import that a wipe overtook. */
    suspend fun clearPhoneSettings() {
        dataStore.edit { prefs ->
            prefs.clearQuickPay()
            listOf(phoneSyncedAtKey, phonePrimaryDisplayKey, primaryDisplayKey, displayUnitKey, selectedCurrencyKey)
                .forEach { prefs.remove(it) }
        }
    }

    suspend fun setPrimaryDisplay(display: PrimaryDisplay) {
        dataStore.edit { it[primaryDisplayKey] = display.name }
    }

    /** Whether the wearer still has to be told that the wallet was deleted because its seed key was lost. */
    suspend fun isRemovedNoticePending(): Boolean = dataStore.data.first()[removedNoticeKey] == true

    suspend fun setRemovedNoticePending(isPending: Boolean) {
        dataStore.edit { it[removedNoticeKey] = isPending }
    }

    /** Whether a wallet wipe started and was not finished, as when the process died during it. */
    suspend fun isWipePending(): Boolean = wipeMarker.exists()

    /**
     * Notes that a wallet wipe is under way; [showsRemovedNotice] also leaves the removed notice for the wearer.
     * The marker is written first, so a notice is never left without a wipe that will finish.
     */
    suspend fun beginWipe(showsRemovedNotice: Boolean) {
        wipeMarker.create()
        if (showsRemovedNotice) dataStore.edit { it[removedNoticeKey] = true }
    }

    /** Clears every setting, keeping only a pending removed notice, and then the wipe note. */
    suspend fun finishWipe() {
        dataStore.edit {
            val isRemovedNoticePending = it[removedNoticeKey] == true
            it.clear()
            if (isRemovedNoticePending) it[removedNoticeKey] = true
        }
        wipeMarker.clear()
    }
}

/**
 * [quickPay] is the phone's QuickPay as last imported, or null when none was. [phoneSyncedAtMs] is when the phone's
 * settings were last read, in epoch milliseconds, or null when they never were or the last read found none.
 */
data class WatchSettings(
    val primaryDisplay: PrimaryDisplay = PrimaryDisplay.BITCOIN,
    val selectedCurrency: String = USD,
    val displayUnit: BitcoinDisplayUnit = BitcoinDisplayUnit.MODERN,
    val quickPay: QuickPayLimits? = null,
    val phoneSyncedAtMs: Long? = null,
)

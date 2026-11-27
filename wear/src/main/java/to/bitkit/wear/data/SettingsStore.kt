package to.bitkit.wear.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import to.bitkit.wear.models.PrimaryDisplay
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

/** The watch's display preferences and the wallet wipe's progress notes. It holds nothing secret. */
@Singleton
class SettingsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val wipeMarker: WipeMarker,
) {
    private val primaryDisplayKey = stringPreferencesKey("primary_display")
    private val selectedCurrencyKey = stringPreferencesKey("selected_currency")
    private val removedNoticeKey = booleanPreferencesKey("wallet_removed_notice")

    val data: Flow<WatchSettings> = dataStore.data.map { prefs ->
        WatchSettings(
            primaryDisplay = prefs[primaryDisplayKey]
                ?.let { runCatching { PrimaryDisplay.valueOf(it) }.getOrNull() }
                ?: PrimaryDisplay.BITCOIN,
            selectedCurrency = prefs[selectedCurrencyKey] ?: USD,
        )
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

data class WatchSettings(
    val primaryDisplay: PrimaryDisplay = PrimaryDisplay.BITCOIN,
    val selectedCurrency: String = USD,
)

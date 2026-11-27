package to.bitkit.wear.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import to.bitkit.wear.models.PrimaryDisplay
import to.bitkit.wear.models.USD
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** The watch's display preferences and the wallet wipe's progress notes. It holds nothing secret. */
@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val primaryDisplayKey = stringPreferencesKey("primary_display")
    private val selectedCurrencyKey = stringPreferencesKey("selected_currency")
    private val removedNoticeKey = booleanPreferencesKey("wallet_removed_notice")
    private val wipePendingKey = booleanPreferencesKey("wallet_wipe_pending")

    val data: Flow<WatchSettings> = context.settingsDataStore.data.map { prefs ->
        WatchSettings(
            primaryDisplay = prefs[primaryDisplayKey]
                ?.let { runCatching { PrimaryDisplay.valueOf(it) }.getOrNull() }
                ?: PrimaryDisplay.BITCOIN,
            selectedCurrency = prefs[selectedCurrencyKey] ?: USD,
        )
    }

    suspend fun setPrimaryDisplay(display: PrimaryDisplay) {
        context.settingsDataStore.edit { it[primaryDisplayKey] = display.name }
    }

    /** Whether the wearer still has to be told that the wallet was deleted because its seed key was lost. */
    suspend fun isRemovedNoticePending(): Boolean =
        context.settingsDataStore.data.first()[removedNoticeKey] == true

    suspend fun setRemovedNoticePending(isPending: Boolean) {
        context.settingsDataStore.edit { it[removedNoticeKey] = isPending }
    }

    /** Whether a wallet wipe started and was not finished, as when the process died during it. */
    suspend fun isWipePending(): Boolean = context.settingsDataStore.data.first()[wipePendingKey] == true

    /** Notes that a wallet wipe is under way; [showsRemovedNotice] also leaves the removed notice for the wearer. */
    suspend fun beginWipe(showsRemovedNotice: Boolean) {
        context.settingsDataStore.edit {
            it[wipePendingKey] = true
            if (showsRemovedNotice) it[removedNoticeKey] = true
        }
    }

    /** Clears every setting and the wipe note in one write, keeping only a pending removed notice. */
    suspend fun finishWipe() {
        context.settingsDataStore.edit {
            val isRemovedNoticePending = it[removedNoticeKey] == true
            it.clear()
            if (isRemovedNoticePending) it[removedNoticeKey] = true
        }
    }
}

data class WatchSettings(
    val primaryDisplay: PrimaryDisplay = PrimaryDisplay.BITCOIN,
    val selectedCurrency: String = USD,
)

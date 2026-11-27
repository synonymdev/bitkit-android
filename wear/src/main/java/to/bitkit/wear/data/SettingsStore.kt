package to.bitkit.wear.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import to.bitkit.wear.models.PrimaryDisplay
import to.bitkit.wear.models.USD
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** The watch's display preferences. It holds nothing secret. */
@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val primaryDisplayKey = stringPreferencesKey("primary_display")
    private val selectedCurrencyKey = stringPreferencesKey("selected_currency")

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

    suspend fun reset() {
        context.settingsDataStore.edit { it.clear() }
    }
}

data class WatchSettings(
    val primaryDisplay: PrimaryDisplay = PrimaryDisplay.BITCOIN,
    val selectedCurrency: String = USD,
)

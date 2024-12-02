package to.bitkit.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import to.bitkit.ext.enumValueOfOrNull
import to.bitkit.viewmodels.PrimaryDisplay
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
        store.edit {
            it[PRIMARY_DISPLAY_UNIT_KEY] = display.name
        }
    }

    private companion object {
        private val PRIMARY_DISPLAY_UNIT_KEY = stringPreferencesKey("primary_display_unit")
    }
}

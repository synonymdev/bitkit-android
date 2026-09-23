package to.bitkit.services.offline

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** The client request identity of the active offline receive preparation. */
data class PersistedOfflineReceiveRequest(
    val requestId: String,
    val amountSats: ULong,
    val description: String,
)

/**
 * Durable memory of the active preparation request. The same request id must be reused for the same intent after
 * process death so the library resumes the existing request instead of preparing a second one.
 */
interface OfflineReceiveRequestStore {
    suspend fun load(): PersistedOfflineReceiveRequest?
    suspend fun save(request: PersistedOfflineReceiveRequest)
    suspend fun clear()
}

private val Context.offlineReceiveDataStore: DataStore<Preferences> by preferencesDataStore("offline_receive")

@Singleton
class PreferencesOfflineReceiveRequestStore @Inject constructor(
    @ApplicationContext context: Context,
) : OfflineReceiveRequestStore {
    private val store = context.offlineReceiveDataStore

    override suspend fun load(): PersistedOfflineReceiveRequest? {
        val prefs = store.data.first()
        val requestId = prefs[REQUEST_ID_KEY]?.takeIf { it.isNotBlank() } ?: return null
        val amountSats = prefs[AMOUNT_SATS_KEY] ?: return null
        val description = prefs[DESCRIPTION_KEY] ?: return null
        return PersistedOfflineReceiveRequest(requestId, amountSats.toULong(), description)
    }

    override suspend fun save(request: PersistedOfflineReceiveRequest) {
        store.edit {
            it[REQUEST_ID_KEY] = request.requestId
            it[AMOUNT_SATS_KEY] = request.amountSats.toLong()
            it[DESCRIPTION_KEY] = request.description
        }
    }

    override suspend fun clear() {
        store.edit { it.clear() }
    }

    private companion object {
        val REQUEST_ID_KEY = stringPreferencesKey("request_id")
        val AMOUNT_SATS_KEY = longPreferencesKey("amount_sats")
        val DESCRIPTION_KEY = stringPreferencesKey("description")
    }
}

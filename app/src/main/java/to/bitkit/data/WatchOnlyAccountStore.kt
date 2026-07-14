package to.bitkit.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import to.bitkit.data.serializers.WatchOnlyAccountDataSerializer
import to.bitkit.di.IoDispatcher
import to.bitkit.models.WatchOnlyAccountRecord
import javax.inject.Inject
import javax.inject.Singleton

private val Context.watchOnlyAccountDataStore: DataStore<WatchOnlyAccountData> by dataStore(
    fileName = "watch_only_accounts.json",
    serializer = WatchOnlyAccountDataSerializer,
)

@Singleton
class WatchOnlyAccountStore @Inject constructor(
    @ApplicationContext context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val store = context.watchOnlyAccountDataStore

    val data: Flow<WatchOnlyAccountData> = store.data

    suspend fun load(): List<WatchOnlyAccountRecord> = withContext(ioDispatcher) {
        store.data.first().accounts
    }

    suspend fun save(accounts: List<WatchOnlyAccountRecord>) = withContext(ioDispatcher) {
        store.updateData { WatchOnlyAccountData(accounts.sortedBy(WatchOnlyAccountRecord::accountIndex)) }
        Unit
    }

    suspend fun update(transform: (List<WatchOnlyAccountRecord>) -> List<WatchOnlyAccountRecord>) =
        withContext(ioDispatcher) {
            store.updateData { current ->
                current.copy(accounts = transform(current.accounts).sortedBy(WatchOnlyAccountRecord::accountIndex))
            }
            Unit
        }
}

@Serializable
data class WatchOnlyAccountData(
    val accounts: List<WatchOnlyAccountRecord> = emptyList(),
)

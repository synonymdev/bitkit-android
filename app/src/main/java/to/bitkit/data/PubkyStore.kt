package to.bitkit.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import to.bitkit.data.serializers.PubkyStoreSerializer
import javax.inject.Inject
import javax.inject.Singleton

private val Context.pubkyDataStore: DataStore<PubkyStoreData> by dataStore(
    fileName = "pubky_profile_cache.json",
    serializer = PubkyStoreSerializer,
)

@Singleton
class PubkyStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store = context.pubkyDataStore

    val data: Flow<PubkyStoreData> = store.data

    suspend fun update(transform: (PubkyStoreData) -> PubkyStoreData) {
        store.updateData(transform)
    }

    suspend fun reset() {
        store.updateData { PubkyStoreData() }
    }
}

@Serializable
data class PubkyStoreData(
    val cachedName: String? = null,
    val cachedImageUri: String? = null,
)

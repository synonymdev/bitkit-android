package to.bitkit.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import to.bitkit.data.serializers.AppCacheSerializer
import to.bitkit.data.serializers.SettingsSerializer
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.FxRate
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.Suggestion
import to.bitkit.models.TransactionSpeed
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CacheStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store: DataStore<AppCacheData> = DataStoreFactory.create(
        serializer = AppCacheSerializer,
        produceFile = { context.dataStoreFile("app_cache.json") },
    )

    val data: Flow<AppCacheData> = store.data

    suspend fun update(transform: (AppCacheData) -> AppCacheData) {
        store.updateData(transform)
    }


    suspend fun reset() {
        store.updateData { AppCacheData() }
        Logger.info("Deleted all app cached data.")
    }
}

@Serializable
data class AppCacheData(
    val cachedRates : List<FxRate> = listOf()
)

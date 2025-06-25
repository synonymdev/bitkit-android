package to.bitkit.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import to.bitkit.data.serializers.AppCacheSerializer
import to.bitkit.models.FxRate
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

    suspend fun addPaidOrder(orderId: String, txId: String) {
        store.updateData {
            val newEntry = mapOf(orderId to txId)
            val updatedOrders = newEntry + it.paidOrders
            val limitedOrders = when {
                updatedOrders.size > MAX_PAID_ORDERS -> updatedOrders.toList().take(MAX_PAID_ORDERS).toMap()
                else -> updatedOrders
            }
            it.copy(paidOrders = limitedOrders)
        }
    }

    suspend fun reset() {
        store.updateData { AppCacheData() }
        Logger.info("Deleted all app cached data.")
    }

    companion object {
        private const val MAX_PAID_ORDERS = 50
    }
}

@Serializable
data class AppCacheData(
    val cachedRates: List<FxRate> = listOf(),
    val paidOrders: Map<String, String> = mapOf(),
)

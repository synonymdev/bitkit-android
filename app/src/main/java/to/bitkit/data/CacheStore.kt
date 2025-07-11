package to.bitkit.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import to.bitkit.data.serializers.AppCacheSerializer
import to.bitkit.models.BackupCategory
import to.bitkit.models.BackupItemStatus
import to.bitkit.models.BalanceState
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

    val backupStatuses: Flow<Map<BackupCategory, BackupItemStatus>> = data.map { it.backupStatuses }

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
            Logger.debug("Cached paid order '$orderId'")
            it.copy(paidOrders = limitedOrders)
        }
    }

    suspend fun setOnchainAddress(address: String) {
        store.updateData { it.copy(onchainAddress = address) }
    }

    suspend fun saveBolt11(bolt11: String) {
        store.updateData { it.copy(bolt11 = bolt11) }
    }

    suspend fun setBip21(bip21: String) {
        store.updateData { it.copy(bip21 = bip21) }
    }

    suspend fun cacheBalance(balanceState: BalanceState) {
        store.updateData { it.copy(balance = balanceState) }
    }

    suspend fun updateBackupStatus(category: BackupCategory, transform: (BackupItemStatus) -> BackupItemStatus) {
        store.updateData { currentData ->
            val currentStatus = currentData.backupStatuses[category] ?: BackupItemStatus()
            val updatedStatus = transform(currentStatus)
            val updatedStatuses = currentData.backupStatuses.toMutableMap()
            updatedStatuses[category] = updatedStatus
            currentData.copy(backupStatuses = updatedStatuses)
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
    val onchainAddress: String = "",
    val bolt11: String = "",
    val bip21: String = "",
    val balance: BalanceState? = null,
    val backupStatuses: Map<BackupCategory, BackupItemStatus> = mapOf(),
)

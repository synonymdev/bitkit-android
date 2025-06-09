package to.bitkit.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import to.bitkit.di.json
import to.bitkit.models.BackupCategory
import to.bitkit.models.BackupItemStatus
import to.bitkit.models.BalanceState
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KProperty

const val APP_PREFS = "bitkit_prefs"

// TODO refactor to dataStore (named 'CacheStore'?!)
@Singleton
class AppStorage @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    val sharedPreferences: SharedPreferences
        get() = appContext.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)

    var onchainAddress: String by SharedPrefDelegate(Key.ONCHAIN_ADDRESS)
    var bolt11: String by SharedPrefDelegate(Key.BOLT11)
    var bip21: String by SharedPrefDelegate(Key.BIP21)

    private val _backupStatuses = MutableStateFlow<Map<BackupCategory, BackupItemStatus>>(emptyMap())
    val backupStatuses: StateFlow<Map<BackupCategory, BackupItemStatus>> = _backupStatuses.asStateFlow()

    private val backupStatusMutex = Mutex()

    init {
        _backupStatuses.value = getBackupStatuses()
    }

    fun cacheBalance(balanceState: BalanceState) {
        try {
            val jsonData = json.encodeToString(balanceState)
            sharedPreferences.edit { putString(Key.BALANCE.name, jsonData) }

        } catch (e: Throwable) {
            Logger.debug("Failed to cache balance: $e")
        }
    }

    fun loadBalance(): BalanceState? {
        val jsonData = sharedPreferences.getString(Key.BALANCE.name, null) ?: return null

        return try {
            json.decodeFromString(jsonData)
        } catch (e: Throwable) {
            Logger.debug("Failed to load cached balance: $e")
            null
        }
    }

    private fun getBackupStatuses(): Map<BackupCategory, BackupItemStatus> {
        val jsonData = sharedPreferences.getString(Key.BACKUP_STATUSES.name, null) ?: return emptyMap()

        return try {
            json.decodeFromString(jsonData)
        } catch (e: Throwable) {
            Logger.debug("Failed to load cached backup statuses: $e")
            emptyMap()
        }
    }

    suspend fun updateBackupStatus(category: BackupCategory, updateBlock: (BackupItemStatus) -> BackupItemStatus) {
        backupStatusMutex.withLock {
            val currentStatus = _backupStatuses.value[category] ?: BackupItemStatus()
            val updatedStatus = updateBlock(currentStatus)

            try {
                val currentStatuses = _backupStatuses.value.toMutableMap()
                currentStatuses[category] = updatedStatus

                val jsonData = json.encodeToString(currentStatuses)
                sharedPreferences.edit { putString(Key.BACKUP_STATUSES.name, jsonData) }

                _backupStatuses.value = currentStatuses
            } catch (e: Throwable) {
                Logger.error("Failed to cache backup status for $category: $e", e)
            }
        }
    }

    enum class Key {
        ONCHAIN_ADDRESS,
        BOLT11,
        BIP21,
        BALANCE,
        BACKUP_STATUSES,
    }

    fun clear() {
        sharedPreferences.edit { clear() }
    }
}

@Suppress("unused")
private class SharedPrefDelegate(private val key: AppStorage.Key) {
    operator fun getValue(thisRef: AppStorage, property: KProperty<*>): String {
        return thisRef.sharedPreferences.getString(key.name, "") ?: ""
    }

    operator fun setValue(thisRef: AppStorage, property: KProperty<*>, value: String) {
        thisRef.sharedPreferences.edit { putString(key.name, value) }
    }
}

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
import to.bitkit.data.serializers.HwWalletDataSerializer
import to.bitkit.di.IoDispatcher
import to.bitkit.models.KnownDevice
import javax.inject.Inject
import javax.inject.Singleton

private val Context.hwWalletDataStore: DataStore<HwWalletData> by dataStore(
    fileName = "trezor_device.json",
    serializer = HwWalletDataSerializer
)

@Singleton
class HwWalletStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val store = context.hwWalletDataStore

    val data: Flow<HwWalletData> = store.data

    suspend fun loadKnownDevices(): List<KnownDevice> = withContext(ioDispatcher) {
        store.data.first().knownDevices
    }

    /**
     * @param consumedPendingName the wallet whose pending name one of [devices] has just adopted, dropped
     * in the same write. Splitting the two would let the entry carrying the name fail to save while the
     * pending copy is deleted anyway, leaving the name nowhere.
     */
    suspend fun saveKnownDevices(
        devices: List<KnownDevice>,
        consumedPendingName: String? = null,
    ) = withContext(ioDispatcher) {
        store.updateData { data ->
            data.copy(
                knownDevices = devices,
                pendingNames = consumedPendingName
                    ?.let { data.pendingNames - it }
                    ?: data.pendingNames,
            )
        }
        Unit
    }

    suspend fun loadPendingNames(): Map<String, String> = withContext(ioDispatcher) {
        store.data.first().pendingNames
    }

    /** Stores the name of a wallet with no device entry, or drops it when [name] is null. */
    suspend fun setPendingName(walletId: String, name: String?) = withContext(ioDispatcher) {
        if (walletId.isBlank()) return@withContext
        store.updateData { data ->
            val pendingNames = when {
                name.isNullOrBlank() -> data.pendingNames - walletId
                else -> data.pendingNames + (walletId to name)
            }
            data.copy(pendingNames = pendingNames)
        }
        Unit
    }

    suspend fun backupSnapshot(): Map<String, String> = withContext(ioDispatcher) {
        store.data.first().hwWalletNames()
    }

    /**
     * Merges backed up names into the pending ones, so they are adopted the next time each wallet is
     * paired. Names already held locally win: they were set on this device after the backup was written.
     *
     * Never clears: an envelope without names predates this field, and must not drop what is stored.
     */
    suspend fun restoreNames(names: Map<String, String>) = withContext(ioDispatcher) {
        if (names.isEmpty()) return@withContext
        store.updateData { it.copy(pendingNames = names + it.pendingNames) }
        Unit
    }

    suspend fun reset() = withContext(ioDispatcher) {
        store.updateData { HwWalletData() }
        Unit
    }
}

@Serializable
data class HwWalletData(
    val knownDevices: List<KnownDevice> = emptyList(),
    /**
     * Names of wallets with no [KnownDevice] entry, keyed by wallet id: restored from a backup before
     * the device was paired again, or kept when the wallet was removed. Pairing consumes an entry into
     * the device's own `customLabel`.
     */
    val pendingNames: Map<String, String> = emptyMap(),
)

/**
 * Every hardware wallet name this wallet knows, keyed by wallet id: the name of each paired wallet,
 * overlaid on the pending ones. A paired name wins because it is what the user currently sees.
 *
 * Entries without a wallet id are skipped. Only a device stored before any account key was captured
 * has none, and such an entry is filtered out of the wallet list, so it can never have been named.
 */
internal fun HwWalletData.hwWalletNames(): Map<String, String> =
    pendingNames + knownDevices.mapNotNull { device ->
        val walletId = device.walletId.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val name = device.customLabel?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        walletId to name
    }

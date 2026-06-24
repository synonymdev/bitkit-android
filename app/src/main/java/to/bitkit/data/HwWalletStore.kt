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

    suspend fun saveKnownDevices(devices: List<KnownDevice>) = withContext(ioDispatcher) {
        store.updateData { it.copy(knownDevices = devices) }
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
)

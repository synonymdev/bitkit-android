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
import to.bitkit.data.serializers.TrezorDataSerializer
import to.bitkit.di.IoDispatcher
import to.bitkit.repositories.KnownDevice
import javax.inject.Inject
import javax.inject.Singleton

private val Context.trezorDataStore: DataStore<TrezorData> by dataStore(
    fileName = "trezor_device.json",
    serializer = TrezorDataSerializer
)

@Singleton
class TrezorStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val store = context.trezorDataStore

    val data: Flow<TrezorData> = store.data

    suspend fun loadKnownDevices(): List<KnownDevice> = withContext(ioDispatcher) {
        store.data.first().knownDevices
    }

    suspend fun saveKnownDevices(devices: List<KnownDevice>) = withContext(ioDispatcher) {
        store.updateData { it.copy(knownDevices = devices) }
        Unit
    }

    suspend fun reset() = withContext(ioDispatcher) {
        store.updateData { TrezorData() }
        Unit
    }
}

@Serializable
data class TrezorData(
    val knownDevices: List<KnownDevice> = emptyList(),
)

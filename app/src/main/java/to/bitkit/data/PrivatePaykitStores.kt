package to.bitkit.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import to.bitkit.di.json
import to.bitkit.utils.Logger
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

private val Context.privatePaykitCacheDataStore: DataStore<PrivatePaykitCacheData> by dataStore(
    fileName = "private_paykit_cache.json",
    serializer = PrivatePaykitCacheSerializer,
)

private val Context.privatePaykitReservationDataStore: DataStore<PrivatePaykitReservationData> by dataStore(
    fileName = "private_paykit_reservations.json",
    serializer = PrivatePaykitReservationSerializer,
)

@Singleton
class PrivatePaykitCacheStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store = context.privatePaykitCacheDataStore

    val data: Flow<PrivatePaykitCacheData> = store.data

    suspend fun update(transform: (PrivatePaykitCacheData) -> PrivatePaykitCacheData) {
        store.updateData(transform)
    }

    suspend fun reset() {
        store.updateData { PrivatePaykitCacheData() }
    }
}

@Singleton
class PrivatePaykitReservationStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store = context.privatePaykitReservationDataStore

    val data: Flow<PrivatePaykitReservationData> = store.data

    suspend fun update(transform: (PrivatePaykitReservationData) -> PrivatePaykitReservationData) {
        store.updateData(transform)
    }

    suspend fun reset() {
        store.updateData { PrivatePaykitReservationData() }
    }
}

@Serializable
data class PrivatePaykitCacheData(
    val contacts: Map<String, PrivatePaykitContactCacheData> = emptyMap(),
    val cleanupPending: Boolean = false,
    val deletedContactCleanupPendingPublicKeys: Set<String> = emptySet(),
    @Transient internal val receiptSourceReadable: Boolean = true,
)

@Serializable
data class PrivatePaykitContactCacheData(
    val remoteEndpoints: List<PrivatePaykitStoredPaymentEntryData> = emptyList(),
    val consumedPrivatePaymentListVersion: ULong? = null,
    val consumedPrivatePaymentListOwner: String? = null,
    val localInvoice: PrivatePaykitStoredInvoiceData? = null,
    val receivedInvoicePaymentHashes: List<String> = emptyList(),
    val hasPublishedPrivatePaymentList: Boolean = false,
)

@Serializable
data class PrivatePaykitStoredPaymentEntryData(
    val methodId: String,
    val endpointData: String,
)

@Serializable
data class PrivatePaykitStoredInvoiceData(
    val bolt11: String,
    val paymentHash: String,
    val expiresAt: Long,
)

@Serializable
data class PrivatePaykitReservationData(
    val version: Int = 1,
    val reservedReceiveIndexesByAddressType: Map<String, Set<Int>> = emptyMap(),
    val contactAssignments: Map<String, PrivatePaykitStoredAssignmentData> = emptyMap(),
    val contactAssignmentHistory: Map<String, List<PrivatePaykitStoredAssignmentData>> = emptyMap(),
    val restoredReservedReceiveIndexCeilingsByAddressType: Map<String, Int> = emptyMap(),
    @Transient internal val receiptSourceReadable: Boolean = true,
)

@Serializable
data class PrivatePaykitStoredAssignmentData(
    val addressType: String,
    val receiveIndex: Int,
    val address: String = "",
)

internal object PrivatePaykitCacheSerializer : Serializer<PrivatePaykitCacheData> {
    private const val TAG = "PrivatePaykitCacheSerializer"

    override val defaultValue: PrivatePaykitCacheData = PrivatePaykitCacheData()

    override suspend fun readFrom(input: InputStream): PrivatePaykitCacheData =
        runCatching {
            json.decodeFromString<PrivatePaykitCacheData>(input.readBytes().decodeToString())
        }.getOrElse {
            Logger.error("Failed to deserialize PrivatePaykitCacheData", it, context = TAG)
            defaultValue.copy(receiptSourceReadable = false)
        }

    override suspend fun writeTo(t: PrivatePaykitCacheData, output: OutputStream) {
        check(t.receiptSourceReadable) { "Cannot persist an incomplete invoice cache" }
        output.write(json.encodeToString(t).encodeToByteArray())
    }
}

internal object PrivatePaykitReservationSerializer : Serializer<PrivatePaykitReservationData> {
    private const val TAG = "PrivatePaykitReservationSerializer"

    override val defaultValue: PrivatePaykitReservationData = PrivatePaykitReservationData()

    override suspend fun readFrom(input: InputStream): PrivatePaykitReservationData =
        runCatching {
            json.decodeFromString<PrivatePaykitReservationData>(input.readBytes().decodeToString())
        }.getOrElse {
            Logger.error("Failed to deserialize PrivatePaykitReservationData", it, context = TAG)
            defaultValue.copy(receiptSourceReadable = false)
        }

    override suspend fun writeTo(t: PrivatePaykitReservationData, output: OutputStream) {
        check(t.receiptSourceReadable) { "Cannot persist an incomplete reservation ledger" }
        output.write(json.encodeToString(t).encodeToByteArray())
    }
}

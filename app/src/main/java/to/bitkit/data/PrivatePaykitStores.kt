package to.bitkit.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import to.bitkit.di.json
import to.bitkit.models.PubkyPublicKeyFormat
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

private val Context.paykitOutboundDeliveryDataStore: DataStore<PaykitOutboundDeliveryData> by dataStore(
    fileName = "paykit_outbound_delivery.json",
    serializer = PaykitOutboundDeliverySerializer,
)

/**
 * Receipt ids seen sent, per identity. The SDK reports Receipt Access delivery on each issuance, but a restored SDK
 * does not send a delivered message again, so the wallet backup carries these ids to keep such receipts sent.
 */
@Singleton
class PaykitOutboundDeliveryStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store by lazy { context.paykitOutboundDeliveryDataStore }
    private val _backupStateVersion = MutableStateFlow(0L)

    /** Moves when a receipt is first seen sent. */
    val backupStateVersion: StateFlow<Long> = _backupStateVersion.asStateFlow()

    suspend fun isSent(identity: String, receiptId: String): Boolean =
        store.data.first().identities[key(identity)]?.sentReceiptIds?.contains(receiptId) == true

    suspend fun markSent(identity: String, receiptId: String) {
        updateRecords(identity) { it.copy(sentReceiptIds = it.sentReceiptIds + receiptId) }
    }

    suspend fun reset() {
        store.updateData { PaykitOutboundDeliveryData() }
    }

    /** Receipt ids seen sent, by identity, for the wallet backup. */
    suspend fun backupSnapshot(): Map<String, Set<String>> =
        store.data.first().identities.mapValues { it.value.sentReceiptIds }.filterValues { it.isNotEmpty() }

    /** Adds the restored sent receipt ids to what this device already recorded. */
    suspend fun restoreBackup(sentReceiptIds: Map<String, Set<String>>) {
        sentReceiptIds.forEach { (identity, ids) ->
            updateRecords(identity) { it.copy(sentReceiptIds = it.sentReceiptIds + ids) }
        }
    }

    private suspend fun updateRecords(
        identity: String,
        transform: (PaykitOutboundDeliveryRecords) -> PaykitOutboundDeliveryRecords,
    ) {
        val key = key(identity)
        var sentChanged = false
        store.updateData { data ->
            val current = data.identities[key] ?: PaykitOutboundDeliveryRecords()
            val records = transform(current)
            sentChanged = records.sentReceiptIds != current.sentReceiptIds
            if (records == current) data else data.copy(identities = data.identities + (key to records))
        }
        if (sentChanged) _backupStateVersion.update { it + 1 }
    }

    private fun key(identity: String) = PubkyPublicKeyFormat.normalized(identity) ?: identity
}

@Serializable
data class PaykitOutboundDeliveryData(
    val identities: Map<String, PaykitOutboundDeliveryRecords> = emptyMap(),
)

@Serializable
data class PaykitOutboundDeliveryRecords(
    val sentReceiptIds: Set<String> = emptySet(),
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
    val publicAssignments: Map<String, PrivatePaykitStoredAssignmentData> = emptyMap(),
    val quarantinedPublicAddresses: Set<String> = emptySet(),
    val restoredReservedReceiveIndexCeilingsByAddressType: Map<String, Int> = emptyMap(),
)

@Serializable
data class PrivatePaykitStoredAssignmentData(
    val addressType: String,
    val receiveIndex: Int,
    val address: String = "",
)

private object PrivatePaykitCacheSerializer : Serializer<PrivatePaykitCacheData> {
    private const val TAG = "PrivatePaykitCacheSerializer"

    override val defaultValue: PrivatePaykitCacheData = PrivatePaykitCacheData()

    override suspend fun readFrom(input: InputStream): PrivatePaykitCacheData =
        runCatching {
            json.decodeFromString<PrivatePaykitCacheData>(input.readBytes().decodeToString())
        }.getOrElse {
            Logger.error("Failed to deserialize PrivatePaykitCacheData", it, context = TAG)
            defaultValue
        }

    override suspend fun writeTo(t: PrivatePaykitCacheData, output: OutputStream) {
        output.write(json.encodeToString(t).encodeToByteArray())
    }
}

private object PrivatePaykitReservationSerializer : Serializer<PrivatePaykitReservationData> {
    private const val TAG = "PrivatePaykitReservationSerializer"

    override val defaultValue: PrivatePaykitReservationData = PrivatePaykitReservationData()

    override suspend fun readFrom(input: InputStream): PrivatePaykitReservationData =
        runCatching {
            json.decodeFromString<PrivatePaykitReservationData>(input.readBytes().decodeToString())
        }.getOrElse {
            Logger.error("Failed to deserialize PrivatePaykitReservationData", it, context = TAG)
            defaultValue
        }

    override suspend fun writeTo(t: PrivatePaykitReservationData, output: OutputStream) {
        output.write(json.encodeToString(t).encodeToByteArray())
    }
}

private object PaykitOutboundDeliverySerializer : Serializer<PaykitOutboundDeliveryData> {
    private const val TAG = "PaykitOutboundDeliverySerializer"

    override val defaultValue: PaykitOutboundDeliveryData = PaykitOutboundDeliveryData()

    override suspend fun readFrom(input: InputStream): PaykitOutboundDeliveryData =
        runCatching {
            json.decodeFromString<PaykitOutboundDeliveryData>(input.readBytes().decodeToString())
        }.getOrElse {
            Logger.error("Failed to deserialize PaykitOutboundDeliveryData", it, context = TAG)
            defaultValue
        }

    override suspend fun writeTo(t: PaykitOutboundDeliveryData, output: OutputStream) {
        output.write(json.encodeToString(t).encodeToByteArray())
    }
}

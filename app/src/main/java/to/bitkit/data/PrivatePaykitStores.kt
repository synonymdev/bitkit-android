package to.bitkit.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import com.synonym.paykit.OutboundPrivateSendReport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
 * What drains reported for queued Paykit private messages, kept across launches: the SDK reports a message's delivery
 * only to the call that drained it, so a receipt counts as sent only once some drain saw its message go out.
 */
@Singleton
class PaykitOutboundDeliveryStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        /** Most message outcomes kept; the oldest ids are dropped first. */
        private const val MAX_ENTRIES = 1000
    }

    private val store by lazy { context.paykitOutboundDeliveryDataStore }

    suspend fun record(report: OutboundPrivateSendReport) {
        if (report.sent.isEmpty() && report.failed.isEmpty()) return
        store.updateData { data ->
            val outcomes = data.outcomes +
                report.failed.associate { it.outboundMessageId.toString() to false } +
                report.sent.associate { it.toString() to true }
            val kept = outcomes.keys.mapNotNull { it.toULongOrNull() }.sorted().takeLast(MAX_ENTRIES)
                .map { it.toString() }.toSet()
            data.copy(outcomes = outcomes.filterKeys(kept::contains))
        }
    }

    /** True once a drain sent the message, false when its latest drain failed, null when no drain reported it. */
    suspend fun wasSent(messageId: ULong?): Boolean? =
        messageId?.let { store.data.first().outcomes[it.toString()] }

    /** Receipts whose access was seen sent stay sent, whatever later pruning drops from the message outcomes. */
    suspend fun markReceiptSent(receiptId: String) {
        store.updateData { data ->
            if (receiptId in data.sentReceiptIds) data else data.copy(sentReceiptIds = data.sentReceiptIds + receiptId)
        }
    }

    suspend fun isReceiptSent(receiptId: String): Boolean = receiptId in store.data.first().sentReceiptIds

    /** Message ids are local to one identity's Paykit state, so outcomes recorded for another or none are dropped. */
    suspend fun bind(identity: String) {
        store.updateData { data ->
            if (PubkyPublicKeyFormat.matches(data.identity, identity)) data else PaykitOutboundDeliveryData(identity)
        }
    }

    suspend fun reset() {
        store.updateData { PaykitOutboundDeliveryData() }
    }
}

@Serializable
data class PaykitOutboundDeliveryData(
    val identity: String? = null,
    val outcomes: Map<String, Boolean> = emptyMap(),
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

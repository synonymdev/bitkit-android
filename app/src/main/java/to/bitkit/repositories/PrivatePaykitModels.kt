package to.bitkit.repositories

import kotlinx.serialization.Serializable
import to.bitkit.data.PrivatePaykitCacheData
import to.bitkit.data.PrivatePaykitContactCacheData
import to.bitkit.data.PrivatePaykitStoredInvoiceData
import to.bitkit.data.PrivatePaykitStoredPaymentEntryData
import to.bitkit.utils.AppError

sealed class PrivatePaykitError(message: String) : AppError(message) {
    data object InvalidPublicKey : PrivatePaykitError("Contact public key is invalid")
    data object PaymentListAlreadyConsumed : PrivatePaykitError("Private payment details are no longer available")
    data object PrivateUnavailable : PrivatePaykitError("Private Paykit is not available")
    data object RouteHintsUnavailable : PrivatePaykitError("Reachable private Lightning endpoint is not available yet")
}

internal data class PrivatePaykitState(
    val contacts: MutableMap<String, ContactState> = mutableMapOf(),
) {
    constructor(cacheState: PrivatePaykitCacheData) : this(
        contacts = cacheState.contacts.mapValues { (_, cache) -> ContactState(cache) }.toMutableMap(),
    )

    fun cacheState(
        cleanupPending: Boolean,
        deletedContactCleanupPendingPublicKeys: Set<String>,
    ) = PrivatePaykitCacheData(
        contacts = contacts.mapNotNull { (publicKey, contactState) ->
            (publicKey to contactState.cacheState()).takeIf { contactState.hasCacheState }
        }.toMap(),
        cleanupPending = cleanupPending,
        deletedContactCleanupPendingPublicKeys = deletedContactCleanupPendingPublicKeys,
    )
}

internal data class ContactState(
    var remoteEndpoints: List<StoredPaymentEntry> = emptyList(),
    var consumedPrivatePaymentListVersion: ULong? = null,
    var localInvoice: StoredInvoice? = null,
    var receivedInvoicePaymentHashes: List<String> = emptyList(),
    var hasPublishedPrivatePaymentList: Boolean = false,
) {
    constructor(cache: PrivatePaykitContactCacheData) : this(
        remoteEndpoints = cache.remoteEndpoints.map { StoredPaymentEntry(it.methodId, it.endpointData) },
        consumedPrivatePaymentListVersion = cache.consumedPrivatePaymentListVersion,
        localInvoice = cache.localInvoice?.let { invoice ->
            StoredInvoice(invoice.bolt11, invoice.paymentHash, invoice.expiresAt)
        },
        receivedInvoicePaymentHashes = cache.receivedInvoicePaymentHashes,
        hasPublishedPrivatePaymentList = cache.hasPublishedPrivatePaymentList,
    )

    val hasCacheState: Boolean
        get() = hasPublishedPrivatePaymentList ||
            remoteEndpoints.isNotEmpty() ||
            (consumedPrivatePaymentListVersion != null) ||
            (localInvoice != null) ||
            receivedInvoicePaymentHashes.isNotEmpty()

    fun cacheState() = PrivatePaykitContactCacheData(
        remoteEndpoints = remoteEndpoints.map { PrivatePaykitStoredPaymentEntryData(it.methodId, it.endpointData) },
        consumedPrivatePaymentListVersion = consumedPrivatePaymentListVersion,
        localInvoice = localInvoice?.let { invoice ->
            PrivatePaykitStoredInvoiceData(invoice.bolt11, invoice.paymentHash, invoice.expiresAt)
        },
        receivedInvoicePaymentHashes = receivedInvoicePaymentHashes,
        hasPublishedPrivatePaymentList = hasPublishedPrivatePaymentList,
    )
}

internal data class StoredPaymentEntry(
    val methodId: String,
    val endpointData: String,
)

@Serializable
internal data class PrivatePaykitBackup(
    val sdkState: String,
    val consumedPrivatePaymentListVersions: Map<String, ULong>,
)

internal data class StoredInvoice(
    val bolt11: String,
    val paymentHash: String,
    val expiresAt: Long,
)

package to.bitkit.repositories

import to.bitkit.data.PrivatePaykitCacheData
import to.bitkit.data.PrivatePaykitContactCacheData
import to.bitkit.data.PrivatePaykitStoredInvoiceData
import to.bitkit.data.PrivatePaykitStoredPaymentEntryData
import to.bitkit.utils.AppError

sealed class PrivatePaykitError(message: String) : AppError(message) {
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
    var localInvoicesByReceiverPath: Map<String, StoredInvoice> = emptyMap(),
    var receivedInvoicePaymentHashes: List<String> = emptyList(),
    var publishedPrivatePaymentReceiverPaths: Set<String> = emptySet(),
) {
    constructor(cache: PrivatePaykitContactCacheData) : this(
        remoteEndpoints = cache.remoteEndpoints.map { StoredPaymentEntry(it.methodId, it.endpointData) },
        localInvoicesByReceiverPath = cache.localInvoicesByReceiverPath.mapValues { (_, invoice) ->
            StoredInvoice(invoice.bolt11, invoice.paymentHash, invoice.expiresAt)
        },
        receivedInvoicePaymentHashes = cache.receivedInvoicePaymentHashes,
        publishedPrivatePaymentReceiverPaths = cache.publishedPrivatePaymentReceiverPaths,
    )

    val hasCacheState: Boolean
        get() = publishedPrivatePaymentReceiverPaths.isNotEmpty() ||
            remoteEndpoints.isNotEmpty() ||
            localInvoicesByReceiverPath.isNotEmpty() ||
            receivedInvoicePaymentHashes.isNotEmpty()

    fun cacheState() = PrivatePaykitContactCacheData(
        remoteEndpoints = remoteEndpoints.map { PrivatePaykitStoredPaymentEntryData(it.methodId, it.endpointData) },
        localInvoicesByReceiverPath = localInvoicesByReceiverPath.mapValues { (_, invoice) ->
            PrivatePaykitStoredInvoiceData(invoice.bolt11, invoice.paymentHash, invoice.expiresAt)
        },
        receivedInvoicePaymentHashes = receivedInvoicePaymentHashes,
        publishedPrivatePaymentReceiverPaths = publishedPrivatePaymentReceiverPaths,
    )
}

internal data class StoredPaymentEntry(
    val methodId: String,
    val endpointData: String,
)

internal data class StoredInvoice(
    val bolt11: String,
    val paymentHash: String,
    val expiresAt: Long,
)

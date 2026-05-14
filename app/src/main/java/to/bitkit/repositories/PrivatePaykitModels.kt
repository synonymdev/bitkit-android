package to.bitkit.repositories

import kotlinx.serialization.Serializable
import to.bitkit.data.PrivatePaykitCacheData
import to.bitkit.data.PrivatePaykitContactCacheData
import to.bitkit.data.PrivatePaykitStoredInvoiceData
import to.bitkit.data.PrivatePaykitStoredPaymentEntryData
import to.bitkit.utils.AppError

sealed class PrivatePaykitError(message: String, cause: Throwable? = null) : AppError(message, cause) {
    data object PrivateUnavailable : PrivatePaykitError("Private Paykit is not available")
    data object PayloadTooLarge : PrivatePaykitError("Private Paykit payload is too large")
    data object StaleLinkState : PrivatePaykitError("Private Paykit link state changed")
    class StatePersistenceFailed(cause: Throwable) : PrivatePaykitError("Failed to persist private Paykit state", cause)
}

internal data class ContactPaykitHandles(
    val linkId: String? = null,
    val handshakeId: String? = null,
)

internal data class PrivatePaykitState(
    val contacts: MutableMap<String, ContactState> = mutableMapOf(),
) {
    constructor(secretState: PrivatePaykitSecretState, cacheState: PrivatePaykitCacheData) : this(
        contacts = cacheState.contacts.mapValues { (_, cache) -> ContactState(cache) }.toMutableMap(),
    ) {
        secretState.contacts.forEach { (publicKey, secret) ->
            val contactState = contacts.getOrPut(publicKey) { ContactState() }
            contactState.linkSnapshotHex = secret.linkSnapshotHex
            contactState.handshakeSnapshotHex = secret.handshakeSnapshotHex
        }
    }

    fun secretState() = PrivatePaykitSecretState(
        contacts = contacts.mapNotNull { (publicKey, contactState) ->
            val secretState = ContactSecretState(contactState.linkSnapshotHex, contactState.handshakeSnapshotHex)
            (publicKey to secretState).takeIf { secretState.hasSecretState }
        }.toMap(),
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
    var linkSnapshotHex: String? = null,
    var handshakeSnapshotHex: String? = null,
    var remoteEndpoints: List<StoredPaymentEntry> = emptyList(),
    var localInvoice: StoredInvoice? = null,
    var receivedInvoicePaymentHashes: List<String> = emptyList(),
    var lastLocalPayloadHash: String? = null,
    var linkCompletedAt: Long? = null,
    var handshakeUpdatedAt: Long? = null,
    var recoveryStartedAt: Long? = null,
    var mainRecoveryAttemptId: String? = null,
    var responderRecoveryAttemptId: String? = null,
    var lastCompletedRecoveryAttemptId: String? = null,
    var linkFailureCount: Int = 0,
) {
    constructor(cache: PrivatePaykitContactCacheData) : this(
        remoteEndpoints = cache.remoteEndpoints.map { StoredPaymentEntry(it.methodId, it.endpointData) },
        localInvoice = cache.localInvoice?.let { StoredInvoice(it.bolt11, it.paymentHash, it.expiresAt) },
        receivedInvoicePaymentHashes = cache.receivedInvoicePaymentHashes,
        lastLocalPayloadHash = cache.lastLocalPayloadHash,
        linkCompletedAt = cache.linkCompletedAt,
        handshakeUpdatedAt = cache.handshakeUpdatedAt,
        recoveryStartedAt = cache.recoveryStartedAt,
        mainRecoveryAttemptId = cache.mainRecoveryAttemptId,
        responderRecoveryAttemptId = cache.responderRecoveryAttemptId,
        lastCompletedRecoveryAttemptId = cache.lastCompletedRecoveryAttemptId,
        linkFailureCount = cache.linkFailureCount,
    )

    val hasBackupState: Boolean
        get() = linkSnapshotHex != null ||
            handshakeSnapshotHex != null ||
            remoteEndpoints.isNotEmpty() ||
            linkCompletedAt != null ||
            handshakeUpdatedAt != null ||
            recoveryStartedAt != null ||
            mainRecoveryAttemptId != null ||
            responderRecoveryAttemptId != null ||
            lastCompletedRecoveryAttemptId != null

    val hasCacheState: Boolean
        get() = remoteEndpoints.isNotEmpty() ||
            localInvoice != null ||
            receivedInvoicePaymentHashes.isNotEmpty() ||
            lastLocalPayloadHash != null ||
            linkCompletedAt != null ||
            handshakeUpdatedAt != null ||
            recoveryStartedAt != null ||
            mainRecoveryAttemptId != null ||
            responderRecoveryAttemptId != null ||
            lastCompletedRecoveryAttemptId != null ||
            linkFailureCount != 0

    fun cacheState() = PrivatePaykitContactCacheData(
        remoteEndpoints = remoteEndpoints.map { PrivatePaykitStoredPaymentEntryData(it.methodId, it.endpointData) },
        localInvoice = localInvoice?.let { PrivatePaykitStoredInvoiceData(it.bolt11, it.paymentHash, it.expiresAt) },
        receivedInvoicePaymentHashes = receivedInvoicePaymentHashes,
        lastLocalPayloadHash = lastLocalPayloadHash,
        linkCompletedAt = linkCompletedAt,
        handshakeUpdatedAt = handshakeUpdatedAt,
        recoveryStartedAt = recoveryStartedAt,
        mainRecoveryAttemptId = mainRecoveryAttemptId,
        responderRecoveryAttemptId = responderRecoveryAttemptId,
        lastCompletedRecoveryAttemptId = lastCompletedRecoveryAttemptId,
        linkFailureCount = linkFailureCount,
    )
}

@Serializable
internal data class PrivatePaykitSecretState(
    val contacts: Map<String, ContactSecretState> = emptyMap(),
)

@Serializable
internal data class ContactSecretState(
    val linkSnapshotHex: String? = null,
    val handshakeSnapshotHex: String? = null,
) {
    val hasSecretState: Boolean
        get() = linkSnapshotHex != null || handshakeSnapshotHex != null
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

internal data class PrivateStoragePurgeResult(
    val deletedCount: Int,
    val didHitLimit: Boolean,
    val didFail: Boolean,
)

@Serializable
internal data class RecoveryMarker(
    val version: Int,
    val path: String,
    val stage: String,
    val attemptId: String,
    val createdAt: Long,
)

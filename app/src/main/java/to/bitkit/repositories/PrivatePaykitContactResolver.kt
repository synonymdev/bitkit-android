package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import to.bitkit.data.PrivatePaykitCacheStore
import to.bitkit.data.PrivatePaykitStoredAssignmentData
import to.bitkit.data.ProfileRemovalPending
import to.bitkit.data.ProfileRemovalStore
import to.bitkit.data.WalletConnectionRecovery
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.PubkyPublicKeyFormat
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class PrivatePaykitContactResolver @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val cacheStore: PrivatePaykitCacheStore,
    private val addressReservationRepo: Provider<PrivatePaykitAddressReservationRepo>,
    private val paymentRequestRepo: Provider<PaykitPaymentRequestRepo>,
    private val profileRemovalStore: ProfileRemovalStore,
    private val connectionRecovery: Provider<WalletConnectionRecovery>,
) {
    /** Local receipt associations belong to the wallet that admitted the incoming synchronization. */
    internal suspend fun <T> withReceiptOperation(block: suspend (Receipt) -> T): T =
        connectionRecovery.get().withReceiptOperation { session ->
            session.requireCurrent()
            val snapshot = runSuspendCatching {
                profileRemovalStore.withReceiptSnapshot {
                    val cache = cacheStore.data.first()
                    check(cache.receiptSourceReadable) { "Receipt invoice source is unavailable" }
                    val shared = receivedPaymentContacts
                    val requestGeneration = receivedPaymentContactsGeneration
                    val reserved = addressReservationRepo.get().receiptAssignments()
                    check(
                        cacheStore.data.first() == cache && receivedPaymentContacts === shared &&
                            reservationVersion == reserved.revision &&
                            receivedPaymentContactsGeneration == requestGeneration
                    ) { "Receipt associations changed during capture" }
                    val invoiceContacts = mutableMapOf<String, MutableSet<String>>()
                    cache.contacts.forEach { (key, value) ->
                        (value.receivedInvoicePaymentHashes + listOfNotNull(value.localInvoice?.paymentHash))
                            .forEach { hash -> invoiceContacts.getOrPut(hash) { mutableSetOf() }.add(key) }
                    }
                    ReceiptSnapshot(
                        session.receiptGeneration,
                        invoiceContacts.mapValues { it.value.toSet() },
                        shared,
                        reserved.assignments,
                        requestGeneration,
                        reserved.revision
                    )
                }
            }.getOrElse {
                if (it is ProfileRemovalPending) throw it
                null
            }
            profileRemovalStore.requireReceiptAccountingAccess()
            session.requireCurrent()
            block(Receipt(session, snapshot))
        }

    internal inner class Receipt internal constructor(
        private val session: WalletConnectionRecovery.Session,
        /** Null is unavailable association evidence, never a complete empty source or completed backfill. */
        val snapshot: ReceiptSnapshot?,
    ) {
        private val lookupFailed = AtomicBoolean(false)
        val associationsComplete: Boolean get() = snapshot != null && !lookupFailed.get()

        suspend fun requireCurrent() = session.requireCurrent()

        private suspend fun <T> lookupAssociations(block: suspend () -> T): T? {
            val result = runSuspendCatching { block() }
            requireCurrent()
            if (result.isFailure) lookupFailed.set(true)
            return result.getOrNull()
        }

        fun <T> commit(block: () -> T): T = session.commitReceiptState(block)

        suspend fun contactForInvoice(paymentHash: String): String? {
            requireCurrent()
            val source = snapshot ?: return null
            if (paymentHash.isBlank()) return null
            return (source.invoiceContacts[paymentHash].orEmpty() + source.requests.contactsForPaymentHash(paymentHash))
                .mapNotNull(PubkyPublicKeyFormat::normalized).distinct().singleOrNull()
        }

        suspend fun contactForAddresses(receivingAddress: String?, addresses: Collection<String>): String? {
            requireCurrent()
            val source = snapshot ?: return null
            if (receivingAddress.isNullOrBlank() || receivingAddress !in addresses) return null
            val reserved = lookupAssociations {
                addresses.distinct().associateWith {
                    addressReservationRepo.get().contactsForReceipt(it, source.assignments)
                }
            } ?: return null
            val receivingContacts = reserved[receivingAddress].orEmpty() +
                source.requests.contactsForAddresses(listOf(receivingAddress))
            val contact = receivingContacts.mapNotNull(PubkyPublicKeyFormat::normalized).distinct().singleOrNull()
                ?: return null
            return contact.takeIf {
                (reserved.values.flatten() + source.requests.contactsForAddresses(addresses))
                    .mapNotNull(PubkyPublicKeyFormat::normalized).distinct().singleOrNull() == contact
            }
        }

        suspend fun isReservedReceiveAddress(address: String): Boolean {
            requireCurrent()
            val source = snapshot ?: return false
            return lookupAssociations {
                addressReservationRepo.get().contactsForReceipt(address, source.assignments).isNotEmpty()
            } ?: false
        }
    }

    internal data class ReceiptSnapshot(
        val walletGeneration: Long,
        val invoiceContacts: Map<String, Set<String>>,
        val requests: PaykitReceivedPaymentContacts,
        val assignments: List<Pair<String, PrivatePaykitStoredAssignmentData>>,
        val requestGeneration: Long,
        val reservationRevision: Long,
    )

    internal val recoveryGeneration: Long get() = profileRemovalStore.generation

    private suspend fun isRecoveryCurrent(generation: Long): Boolean =
        hasProfileAccess() && generation == recoveryGeneration

    internal suspend fun hasProfileAccess(): Boolean =
        runSuspendCatching { profileRemovalStore.requireNoPending() }.isSuccess

    /** Wallet address accounting is independent of whether its old profile may be attributed. */
    internal suspend fun isReservedReceiveAddress(address: String): Boolean = withContext(ioDispatcher) {
        addressReservationRepo.get().contactPublicKeyForReservedAddress(address) != null
    }

    internal val receivedPaymentContacts: PaykitReceivedPaymentContacts
        get() = paymentRequestRepo.get().receivedPaymentContacts

    internal val receivedPaymentContactsGeneration: Long
        get() = paymentRequestRepo.get().receivedPaymentContactsGeneration

    internal val reservationVersion: Long
        get() = addressReservationRepo.get().attributionVersion

    suspend fun contactPublicKeyForPrivateInvoicePaymentHash(paymentHash: String): String? =
        withContext(ioDispatcher) {
            val generation = recoveryGeneration
            if (paymentHash.isBlank() || !hasProfileAccess()) return@withContext null
            val shared = receivedPaymentContacts
            val contacts = cacheStore.data.first().contacts.mapNotNull { (publicKey, contactState) ->
                publicKey.takeIf {
                    contactState.localInvoice?.paymentHash == paymentHash ||
                        paymentHash in contactState.receivedInvoicePaymentHashes
                }
            }
            if (receivedPaymentContacts !== shared || !isRecoveryCurrent(generation)) return@withContext null
            (contacts + shared.contactsForPaymentHash(paymentHash))
                .mapNotNull(PubkyPublicKeyFormat::normalized).distinct().singleOrNull()
        }

    suspend fun contactPublicKeyForReservedAddress(address: String): String? = withContext(ioDispatcher) {
        val generation = recoveryGeneration
        if (!hasProfileAccess()) return@withContext null
        val contact = addressReservationRepo.get().contactPublicKeyForReservedAddress(address)
        contact.takeIf { isRecoveryCurrent(generation) }
    }

    suspend fun contactPublicKeyForPrivateOnchainAddresses(
        receivingAddress: String?,
        addresses: Collection<String>,
    ): String? =
        withContext(ioDispatcher) {
            val generation = recoveryGeneration
            if (receivingAddress.isNullOrBlank() || receivingAddress !in addresses || !hasProfileAccess()) {
                return@withContext null
            }
            val shared = receivedPaymentContacts
            val reservedContacts = addresses.distinct().associateWith {
                contactPublicKeyForReservedAddress(it)
            }
            val receivingContacts = listOfNotNull(reservedContacts[receivingAddress]) +
                shared.contactsForAddresses(listOf(receivingAddress))
            val contact = receivingContacts.mapNotNull(PubkyPublicKeyFormat::normalized).distinct().singleOrNull()
                ?: return@withContext null
            if (receivedPaymentContacts !== shared || !isRecoveryCurrent(generation)) return@withContext null
            contact.takeIf {
                (reservedContacts.values.filterNotNull() + shared.contactsForAddresses(addresses))
                    .mapNotNull(PubkyPublicKeyFormat::normalized).distinct().singleOrNull() == contact
            }
        }
}

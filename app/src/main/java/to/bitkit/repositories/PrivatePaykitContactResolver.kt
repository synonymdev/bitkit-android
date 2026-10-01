package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import to.bitkit.data.PrivatePaykitCacheStore
import to.bitkit.di.IoDispatcher
import to.bitkit.models.PubkyPublicKeyFormat
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class PrivatePaykitContactResolver @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val cacheStore: PrivatePaykitCacheStore,
    private val addressReservationRepo: Provider<PrivatePaykitAddressReservationRepo>,
    private val paymentRequestRepo: Provider<PaykitPaymentRequestRepo>,
) {
    internal val receivedPaymentContacts: PaykitReceivedPaymentContacts
        get() = paymentRequestRepo.get().receivedPaymentContacts

    internal val receivedPaymentContactsGeneration: Long
        get() = paymentRequestRepo.get().receivedPaymentContactsGeneration

    internal val reservationVersion: Long
        get() = addressReservationRepo.get().attributionVersion

    suspend fun contactPublicKeyForPrivateInvoicePaymentHash(paymentHash: String): String? =
        withContext(ioDispatcher) {
            if (paymentHash.isBlank()) return@withContext null
            val shared = receivedPaymentContacts
            val contacts = cacheStore.data.first().contacts.mapNotNull { (publicKey, contactState) ->
                publicKey.takeIf {
                    contactState.localInvoice?.paymentHash == paymentHash ||
                        paymentHash in contactState.receivedInvoicePaymentHashes
                }
            }
            if (receivedPaymentContacts !== shared) return@withContext null
            (contacts + shared.contactsForPaymentHash(paymentHash))
                .mapNotNull(PubkyPublicKeyFormat::normalized).distinct().singleOrNull()
        }

    suspend fun contactPublicKeyForReservedAddress(address: String): String? = withContext(ioDispatcher) {
        addressReservationRepo.get().contactPublicKeyForReservedAddress(address)
    }

    suspend fun contactPublicKeyForPrivateOnchainAddresses(
        receivingAddress: String?,
        addresses: Collection<String>,
    ): String? =
        withContext(ioDispatcher) {
            if (receivingAddress.isNullOrBlank() || receivingAddress !in addresses) return@withContext null
            val shared = receivedPaymentContacts
            val reservedContacts = addresses.distinct().associateWith {
                contactPublicKeyForReservedAddress(it)
            }
            val receivingContacts = listOfNotNull(reservedContacts[receivingAddress]) +
                shared.contactsForAddresses(listOf(receivingAddress))
            val contact = receivingContacts.mapNotNull(PubkyPublicKeyFormat::normalized).distinct().singleOrNull()
                ?: return@withContext null
            if (receivedPaymentContacts !== shared) return@withContext null
            contact.takeIf {
                (reservedContacts.values.filterNotNull() + shared.contactsForAddresses(addresses))
                    .mapNotNull(PubkyPublicKeyFormat::normalized).distinct().singleOrNull() == contact
            }
        }
}

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

    suspend fun contactPublicKeyForPrivateOnchainAddresses(addresses: Collection<String>): String? =
        withContext(ioDispatcher) {
            val shared = receivedPaymentContacts
            val contacts = addresses.mapNotNull {
                addressReservationRepo.get().contactPublicKeyForReservedAddress(it)
            }
            if (receivedPaymentContacts !== shared) return@withContext null
            (contacts + shared.contactsForAddresses(addresses))
                .mapNotNull(PubkyPublicKeyFormat::normalized).distinct().singleOrNull()
        }
}

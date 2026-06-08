package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import to.bitkit.data.PrivatePaykitCacheStore
import to.bitkit.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class PrivatePaykitContactResolver @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val cacheStore: PrivatePaykitCacheStore,
    private val addressReservationRepo: Provider<PrivatePaykitAddressReservationRepo>,
) {
    suspend fun contactPublicKeyForPrivateInvoicePaymentHash(paymentHash: String): String? =
        withContext(ioDispatcher) {
            if (paymentHash.isBlank()) return@withContext null
            cacheStore.data.first().contacts.firstNotNullOfOrNull { (publicKey, contactState) ->
                publicKey.takeIf {
                    contactState.localInvoice?.paymentHash == paymentHash ||
                        paymentHash in contactState.receivedInvoicePaymentHashes
                }
            }
        }

    suspend fun contactPublicKeyForPrivateOnchainAddresses(addresses: Collection<String>): String? =
        withContext(ioDispatcher) {
            addresses.firstNotNullOfOrNull {
                addressReservationRepo.get().contactPublicKeyForReservedAddress(it)
            }
        }
}

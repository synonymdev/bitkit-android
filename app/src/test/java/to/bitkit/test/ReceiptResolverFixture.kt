package to.bitkit.test

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.flowOf
import org.mockito.kotlin.mock
import to.bitkit.data.PrivatePaykitCacheData
import to.bitkit.data.PrivatePaykitCacheStore
import to.bitkit.data.ProfileRemovalStore
import to.bitkit.repositories.PaykitPaymentRequestRepo
import to.bitkit.repositories.PaykitReceivedPaymentContacts
import to.bitkit.repositories.PrivatePaykitAddressReservationRepo
import to.bitkit.repositories.PrivatePaykitContactResolver
import javax.inject.Provider

fun receiptResolverFixture(dispatcher: CoroutineDispatcher): PrivatePaykitContactResolver {
    val recovery = connectionRecoveryFixture(mock())
    val cache = mock<PrivatePaykitCacheStore> { on { data }.thenReturn(flowOf(PrivatePaykitCacheData())) }
    val reservations = mock<PrivatePaykitAddressReservationRepo> {
        on { receiptAssignments() }.thenReturn(PrivatePaykitAddressReservationRepo.ReceiptAssignments(emptyList(), 0))
    }
    val requests = mock<PaykitPaymentRequestRepo> {
        on { receivedPaymentContacts }.thenReturn(PaykitReceivedPaymentContacts.Empty)
    }
    return PrivatePaykitContactResolver(
        dispatcher,
        cache,
        Provider { reservations },
        Provider { requests },
        ProfileRemovalStore.inMemoryForTesting(),
        Provider { recovery },
    )
}

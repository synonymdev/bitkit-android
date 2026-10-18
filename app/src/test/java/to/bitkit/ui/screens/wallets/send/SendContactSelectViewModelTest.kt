package to.bitkit.ui.screens.wallets.send

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.PublicPaykitPaymentResult
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class SendContactSelectViewModelTest : BaseUnitTest() {
    @Test
    fun `contact stays busy until resolution finishes and can be retried`() = test {
        val context: Context = mock()
        val contacts: PubkyRepo = mock()
        val payments: PrivatePaykitRepo = mock()
        whenever(context.getString(any<Int>())).thenReturn("")
        whenever(contacts.contacts).thenReturn(MutableStateFlow(emptyList()))
        whenever(contacts.isLoadingContacts).thenReturn(MutableStateFlow(false))
        val result = CompletableDeferred<Result<PublicPaykitPaymentResult>>()
        whenever(payments.beginSavedContactPayment("alice")).doSuspendableAnswer { result.await() }
        val model = SendContactSelectViewModel(context, contacts, payments)

        model.payContact("alice")
        model.payContact("bob")
        advanceUntilIdle()
        assertEquals("alice", model.uiState.value.resolvingPublicKey)
        verify(payments, times(0)).beginSavedContactPayment("bob")

        result.complete(Result.success(PublicPaykitPaymentResult.NoEndpoint))
        advanceUntilIdle()
        assertNull(model.uiState.value.resolvingPublicKey)
        model.payContact("alice")
        advanceUntilIdle()
        verify(payments, times(2)).beginSavedContactPayment("alice")
        assertNull(model.uiState.value.resolvingPublicKey)
    }
}

package to.bitkit.ui.screens.paymentrequests

import org.junit.Test
import to.bitkit.repositories.PaykitPaymentRequestTargetsStatus
import to.bitkit.repositories.PaykitPaymentRequestTargetsStatus.Failed
import to.bitkit.repositories.PaykitPaymentRequestTargetsStatus.Loaded
import to.bitkit.repositories.PaykitPaymentRequestTargetsStatus.Loading
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecipientEmptyStateTest {
    @Test
    fun `shows no empty state when recipients are listed`() {
        PaykitPaymentRequestTargetsStatus.entries.forEach {
            assertNull(recipientEmptyState(hasRecipients = true, query = "", status = it))
        }
    }

    @Test
    fun `shows loading while eligibility is loading`() {
        assertEquals(RecipientEmptyState.Loading, emptyState(query = "", status = Loading))
        assertEquals(RecipientEmptyState.Loading, emptyState(query = "bob", status = Loading))
    }

    @Test
    fun `shows failure instead of unavailable when discovery failed`() {
        assertEquals(RecipientEmptyState.Failed, emptyState(query = "", status = Failed))
        assertEquals(RecipientEmptyState.Failed, emptyState(query = "bob", status = Failed))
    }

    @Test
    fun `shows unavailable or no match only after discovery finished`() {
        assertEquals(RecipientEmptyState.Unavailable, emptyState(query = " ", status = Loaded))
        assertEquals(RecipientEmptyState.NoMatch, emptyState(query = "bob", status = Loaded))
    }

    private fun emptyState(query: String, status: PaykitPaymentRequestTargetsStatus) =
        recipientEmptyState(hasRecipients = false, query = query, status = status)
}

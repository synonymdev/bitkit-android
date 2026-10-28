package to.bitkit.services

import android.content.Context
import org.junit.Test
import org.lightningdevkit.ldknode.NodeException
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.utils.AppError
import to.bitkit.utils.LdkError
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrimaryWalletErrorTest {
    private val context = mock<Context>()

    @Test
    fun `receive errors use localized messages through application and LDK wrappers`() {
        val cases = listOf(
            NodeException.LiquiditySourceInsufficientFunds("provider text") to
                R.string.wallet_primary__receive_insufficient_funds,
            NodeException.LiquidityRequestRejected("provider text") to R.string.wallet_primary__receive_rejected,
            NodeException.LiquidityRequestTimedOut("provider text") to R.string.wallet_primary__receive_timed_out,
            NodeException.LiquidityReceiveAmountTooSmall("provider text") to
                R.string.wallet_primary__receive_amount_too_small,
        )
        cases.forEach { (error, resource) ->
            val localized = "Localized receive message $resource"
            whenever(context.getString(resource)).thenReturn(localized)

            assertEquals(localized, error.primaryReceiveMessage(context))
            assertEquals(localized, AppError(error).primaryReceiveMessage(context))
            assertEquals(localized, AppError(LdkError(error)).primaryReceiveMessage(context))
        }
    }

    @Test
    fun `unknown and malformed receive failures do not surface provider text`() {
        assertNull(AppError("Untrusted provider message").primaryReceiveMessage(context))
        assertNull(NodeException.LiquidityRequestFailed("Invalid response").primaryReceiveMessage(context))
    }

    @Test
    fun `receive cause traversal is bounded`() {
        var error: Throwable = NodeException.LiquidityRequestRejected("provider text")
        repeat(8) { error = AppError(error) }

        assertNull(error.primaryReceiveMessage(context))
    }
}

package to.bitkit.ext

import com.synonym.bitkitcore.BroadcastException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test
import to.bitkit.utils.AppError
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BroadcastExceptionExtTest {
    @Test
    fun `electrum broadcast failures are connectivity failures`() {
        val error = AppError(BroadcastException.ElectrumException("DNS lookup failed"))

        assertTrue(error.isBroadcastConnectivityFailure())
    }

    @Test
    fun `broadcast timeout failures are connectivity failures`() = runTest {
        val timeout = runCatching { withTimeout(0) { Unit } }.exceptionOrNull() as TimeoutCancellationException

        assertTrue(timeout.isBroadcastConnectivityFailure())
    }

    @Test
    fun `unrelated broadcast failures are not connectivity failures`() {
        val error = AppError(BroadcastException.InvalidTransaction("bad tx"))

        assertFalse(error.isBroadcastConnectivityFailure())
    }

    @Test
    fun `only invalid raw transactions are definite broadcast failures`() {
        val invalidTransactions = listOf(
            BroadcastException.InvalidHex("bad hex"),
            BroadcastException.InvalidTransaction("bad tx"),
        )
        for (error in invalidTransactions) {
            assertTrue(AppError(error).isDefiniteHardwarePreBroadcastFailure())
        }
        assertFalse(
            BroadcastException.ElectrumException("Broadcast failed: disconnected")
                .isDefiniteHardwarePreBroadcastFailure()
        )
        assertFalse(AppError("unknown result").isDefiniteHardwarePreBroadcastFailure())
    }
}

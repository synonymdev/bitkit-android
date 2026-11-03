package to.bitkit.services

import com.synonym.bitkitcore.BlocktankException
import com.synonym.bitkitcore.FeeRates
import com.synonym.bitkitcore.IBtInfo
import com.synonym.bitkitcore.IBtInfoOnchain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class BlocktankFeeFallbackTest : BaseUnitTest() {
    private val rates = FeeRates(fast = 37u, mid = 19u, slow = 7u)
    private val service = spy(BlocktankService(mock(), mock()))

    @Test
    fun `fresh rates do not read the cache`() = test {
        doReturn(infoWithRates()).whenever(service).info(true)

        assertEquals(rates, service.getFees().getOrThrow())

        verify(service, never()).info(false)
    }

    @Test
    fun `missing fresh rates use the cached schedule once`() = test {
        doReturn(null).whenever(service).info(true)
        doReturn(infoWithRates()).whenever(service).info(false)

        assertEquals(rates, service.getFees().getOrThrow())

        inOrder(service) {
            verify(service).info(true)
            verify(service).info(false)
        }
    }

    @Test
    fun `Blocktank info HTML 502 uses the cached schedule`() = test {
        val error = AppError(
            BlocktankException.DataException(
                "Failed to fetch info from Blocktank: Blocktank error: Failed to get info: " +
                    "Client error: Request failed with status 502 Bad Gateway: <html>Bad Gateway</html>"
            )
        )
        doThrow(error).whenever(service).info(true)
        doReturn(infoWithRates()).whenever(service).info(false)

        assertEquals(rates, service.getFees().getOrThrow())

        inOrder(service) {
            verify(service).info(true)
            verify(service).info(false)
        }
    }

    @Test
    fun `Blocktank info JSON 502 uses the cached schedule`() = test {
        val error = AppError(
            BlocktankException.DataException(
                "Failed to fetch info from Blocktank: Blocktank error: Failed to get info: " +
                    "Client error: Request failed (502 Bad Gateway): gateway unavailable"
            )
        )
        doThrow(error).whenever(service).info(true)
        doReturn(infoWithRates()).whenever(service).info(false)

        assertEquals(rates, service.getFees().getOrThrow())

        inOrder(service) {
            verify(service).info(true)
            verify(service).info(false)
        }
    }

    @Test
    fun `missing cache preserves the original gateway failure`() = test {
        val error = badGateway()
        doThrow(error).whenever(service).info(true)
        doReturn(null).whenever(service).info(false)

        assertSame(error, service.getFees().exceptionOrNull())
        verify(service).info(false)
    }

    @Test
    fun `cache read failure remains a failure without another lookup`() = test {
        val error = AppError("local cache unavailable")
        doThrow(badGateway()).whenever(service).info(true)
        doThrow(error).whenever(service).info(false)

        assertSame(error, assertFailsWith<AppError> { service.getFees() })
        verify(service).info(false)
    }

    @Test
    fun `other typed data errors and unrelated 502 text do not use cached rates`() = test {
        val prefix = "Failed to fetch info from Blocktank: Blocktank error: Failed to get info: Client error: "
        val errors = listOf(
            AppError(BlocktankException.DataException(prefix + "Response parsing failed: 502")),
            AppError(
                BlocktankException.DataException(
                    prefix + "Request failed with status 401 Unauthorized: 502 Bad Gateway"
                )
            ),
            AppError(BlocktankException.DataException(prefix + "Request failed with status 5020: Bad Gateway")),
            AppError(BlocktankException.DataException("502 Bad Gateway")),
            AppError(BlocktankException.DataException("Invalid parameters: 502")),
            AppError("Request failed with status 502 Bad Gateway"),
            IllegalStateException("database not initialized"),
        )
        for (error in errors) {
            doThrow(error).whenever(service).info(true)
            assertSame(error, assertFailsWith<Exception> { service.getFees() })
        }
        verify(service, never()).info(false)
    }

    @Test
    fun `fresh cancellation never reads cached rates`() = test {
        doThrow(CancellationException("cancelled")).whenever(service).info(true)

        assertFailsWith<CancellationException> { service.getFees() }
        verify(service, never()).info(false)
    }

    @Test
    fun `cached lookup cancellation is preserved`() = test {
        doThrow(badGateway()).whenever(service).info(true)
        doThrow(CancellationException("cancelled")).whenever(service).info(false)

        assertFailsWith<CancellationException> { service.getFees() }
        verify(service).info(false)
    }

    @Test
    fun `cancellation before cached fallback prevents its lookup`() = test {
        doSuspendableAnswer {
            currentCoroutineContext().cancel()
            throw badGateway()
        }.whenever(service).info(true)

        val attempt = async { service.getFees() }
        assertFailsWith<CancellationException> { attempt.await() }
        verify(service, never()).info(false)
    }

    @Test
    fun `cancellation while cached lookup returns cannot expose successful rates`() = test {
        doThrow(badGateway()).whenever(service).info(true)
        doSuspendableAnswer {
            currentCoroutineContext().cancel()
            infoWithRates()
        }.whenever(service).info(false)

        val attempt = async { service.getFees() }
        assertFailsWith<CancellationException> { attempt.await() }
        verify(service).info(false)
    }

    private fun badGateway() = AppError(
        BlocktankException.DataException(
            "Failed to fetch info from Blocktank: Blocktank error: Failed to get info: " +
                "Client error: Request failed with status 502 Bad Gateway: gateway unavailable"
        )
    )

    private fun infoWithRates(): IBtInfo {
        val onchain = mock<IBtInfoOnchain>()
        whenever(onchain.feeRates).thenReturn(rates)
        val info = mock<IBtInfo>()
        whenever(info.onchain).thenReturn(onchain)
        return info
    }
}

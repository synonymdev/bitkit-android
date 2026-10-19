package to.bitkit.repositories

import com.synonym.bitkitcore.IBtOrder
import com.synonym.bitkitcore.IcJitEntry
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.test.BaseUnitTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ChannelProviderRepoTest : BaseUnitTest() {
    private val blocktankRepo = mock<BlocktankRepo>()
    private val sut = ChannelProviderRepo(blocktankRepo)

    @Test
    fun `channel orders go to Blocktank`() = test {
        val order = mock<IBtOrder>()
        whenever(blocktankRepo.createOrder(any(), any(), any())).thenReturn(Result.success(order))

        val result = sut.createOrder(spendingBalanceSats = 50_000uL, receivingBalanceSats = 100_000uL)

        assertEquals(ChannelProvider.Blocktank, sut.current())
        assertSame(order, result.getOrThrow())
        verify(blocktankRepo).createOrder(spendingBalanceSats = 50_000uL, receivingBalanceSats = 100_000uL)
    }

    @Test
    fun `CJIT entries go to Blocktank`() = test {
        val entry = mock<IcJitEntry>()
        whenever(blocktankRepo.createCjit(any(), any())).thenReturn(Result.success(entry))

        assertSame(entry, sut.createCjit(20_000uL).getOrThrow())
        verify(blocktankRepo).createCjit(20_000uL)
    }
}

package to.bitkit.ui.screens.wallets.receive

import com.synonym.bitkitcore.IcJitEntry
import org.junit.Test
import to.bitkit.ext.mock
import to.bitkit.utils.ServiceError
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CjitEntryDetailsTest {
    @Test
    fun `from rejects fee equal to invoice amount`() {
        val entry = IcJitEntry.mock(feeSat = 10_000u, channelSizeSat = 20_000u)

        val result = CjitEntryDetails.from(entry, receiveAmountSats = 10_000u)

        assertIs<ServiceError.CjitQuoteInvalid>(result.exceptionOrNull())
    }

    @Test
    fun `from rejects fee greater than invoice amount`() {
        val entry = IcJitEntry.mock(feeSat = 10_001u, channelSizeSat = 20_000u)

        val result = CjitEntryDetails.from(entry, receiveAmountSats = 10_000u)

        assertIs<ServiceError.CjitQuoteInvalid>(result.exceptionOrNull())
    }

    @Test
    fun `from rejects net receive amount greater than channel size`() {
        val entry = IcJitEntry.mock(feeSat = 1_000u, channelSizeSat = 8_999u)

        val result = CjitEntryDetails.from(entry, receiveAmountSats = 10_000u)

        assertIs<ServiceError.CjitQuoteInvalid>(result.exceptionOrNull())
    }

    @Test
    fun `from maps valid quote`() {
        val entry = IcJitEntry.mock(feeSat = 1_000u, channelSizeSat = 20_000u)

        val result = CjitEntryDetails.from(entry, receiveAmountSats = 10_000u).getOrThrow()

        assertEquals(10_000, result.receiveAmountSats)
        assertEquals(1_000, result.feeSat)
        assertEquals(20_000, result.channelSizeSat)
        assertEquals(entry.invoice.request, result.invoice)
    }
}

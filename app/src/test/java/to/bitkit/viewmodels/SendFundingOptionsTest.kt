package to.bitkit.viewmodels

import org.junit.Test
import kotlin.test.assertEquals

class SendFundingOptionsTest {
    @Test
    fun `one software bitcoin source is shown alongside hardware and usdt`() {
        val hardware = SendFundingSource.Hardware("wallet")
        val sources = listOf(SendFundingSource.Savings, SendFundingSource.Spending, hardware, SendFundingSource.Usdt)

        assertEquals(
            listOf(SendFundingSource.Spending, hardware, SendFundingSource.Usdt),
            sources.withSingleBitcoinSource(SendFundingSource.Spending),
        )
        assertEquals(
            listOf(SendFundingSource.Savings, hardware, SendFundingSource.Usdt),
            sources.withSingleBitcoinSource(SendFundingSource.Savings),
        )
    }

    @Test
    fun `an onchain only destination keeps its available bitcoin source`() {
        assertEquals(
            listOf(SendFundingSource.Savings),
            listOf(SendFundingSource.Savings).withSingleBitcoinSource(SendFundingSource.Spending),
        )
    }

    @Test
    fun `hardware only retained payment does not add a software source`() {
        val hardware = SendFundingSource.Hardware("retained")
        assertEquals(listOf(hardware), listOf(hardware).withSingleBitcoinSource(SendFundingSource.Spending))
    }
}

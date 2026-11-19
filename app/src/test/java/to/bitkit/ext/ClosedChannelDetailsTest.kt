package to.bitkit.ext

import com.synonym.bitkitcore.ClosedChannelDetails
import org.junit.Test
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals

class ClosedChannelDetailsTest : BaseUnitTest() {

    @Test
    fun `amountOnClose is our balance at close, not the channel capacity`() {
        val closed = closedChannel(channelValueSats = 20_000u, outboundCapacityMsat = 0u, ourReserveSats = 388u)

        assertEquals(388uL, closed.amountOnClose)
    }

    @Test
    fun `amountOnClose adds floored outbound capacity to our reserve`() {
        val closed = closedChannel(
            channelValueSats = 100_000u,
            outboundCapacityMsat = 40_000_999u,
            ourReserveSats = 1_000u,
        )

        assertEquals(41_000uL, closed.amountOnClose)
    }

    private fun closedChannel(
        channelValueSats: ULong,
        outboundCapacityMsat: ULong,
        ourReserveSats: ULong,
    ) = ClosedChannelDetails(
        channelId = "channelId",
        counterpartyNodeId = "nodeId",
        fundingTxoTxid = "txid",
        fundingTxoIndex = 0u,
        channelValueSats = channelValueSats,
        closedAt = 0u,
        outboundCapacityMsat = outboundCapacityMsat,
        inboundCapacityMsat = 0u,
        counterpartyUnspendablePunishmentReserve = 0u,
        unspendablePunishmentReserve = ourReserveSats,
        forwardingFeeProportionalMillionths = 0u,
        forwardingFeeBaseMsat = 0u,
        channelName = "channel",
        channelClosureReason = "",
    )
}

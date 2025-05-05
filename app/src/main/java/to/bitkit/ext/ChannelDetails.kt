package to.bitkit.ext

import org.lightningdevkit.ldknode.ChannelConfig
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.MaxDustHtlcExposure

/**
 * Calculates the expected amount in sats that will be available upon channel closure.
 */
val ChannelDetails.amountOnClose: ULong
    get() {
        val outboundCapacitySat = this.outboundCapacityMsat / 1000u
        val reserveSats = this.unspendablePunishmentReserve ?: 0u

        return outboundCapacitySat + reserveSats
    }

fun mockChannelDetails(
    channelId: String,
    isChannelReady: Boolean = true,
): ChannelDetails {
    return ChannelDetails(
        channelId = channelId,
        counterpartyNodeId = "counterpartyNodeId",
        fundingTxo = null,
        channelValueSats = 100_000uL,
        unspendablePunishmentReserve = 354uL,
        userChannelId = "userChannelId",
        feerateSatPer1000Weight = 5u,
        outboundCapacityMsat = 50_000uL,
        inboundCapacityMsat = 50_000uL,
        confirmationsRequired = 0u,
        confirmations = 12u,
        isOutbound = false,
        isChannelReady = isChannelReady,
        isUsable = true,
        isAnnounced = false,
        cltvExpiryDelta = null,
        counterpartyUnspendablePunishmentReserve = 0uL,
        counterpartyOutboundHtlcMinimumMsat = null,
        counterpartyOutboundHtlcMaximumMsat = null,
        counterpartyForwardingInfoFeeBaseMsat = null,
        counterpartyForwardingInfoFeeProportionalMillionths = null,
        counterpartyForwardingInfoCltvExpiryDelta = null,
        nextOutboundHtlcLimitMsat = 50_000uL,
        nextOutboundHtlcMinimumMsat = 0uL,
        forceCloseSpendDelay = null,
        inboundHtlcMinimumMsat = 0uL,
        inboundHtlcMaximumMsat = null,
        config = ChannelConfig(
            forwardingFeeProportionalMillionths = 0u,
            forwardingFeeBaseMsat = 0u,
            cltvExpiryDelta = 0u,
            maxDustHtlcExposure = MaxDustHtlcExposure.FixedLimit(limitMsat = 0uL),
            forceCloseAvoidanceMaxFeeSatoshis = 0uL,
            acceptUnderpayingHtlcs = false,
        ),
        shortChannelId = 1234uL,
        outboundScidAlias = 2345uL,
        inboundScidAlias = 3456uL,
    )
}

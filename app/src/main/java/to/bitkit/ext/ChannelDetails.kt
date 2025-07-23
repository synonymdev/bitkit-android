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

/** Returns only `open` channels, filtering out pending ones. */
fun List<ChannelDetails>.filterOpen(): List<ChannelDetails> {
    return this.filter { it.isChannelReady }
}

/** Returns only `pending` channels. */
fun List<ChannelDetails>.filterPending(): List<ChannelDetails> {
    return this.filterNot { it.isChannelReady }
}

/** Returns a limit in sats as close as possible to the HTLC limit we can currently send. */
fun List<ChannelDetails>.totalNextOutboundHtlcLimitSats(): ULong {
    return this.filter { it.isUsable }
        .sumOf { it.nextOutboundHtlcLimitMsat / 1000uL }
}

fun createChannelDetails(): ChannelDetails {
    return ChannelDetails(
        channelId = "channelId",
        counterpartyNodeId = "counterpartyNodeId",
        fundingTxo = null,
        shortChannelId = null,
        outboundScidAlias = null,
        inboundScidAlias = null,
        channelValueSats = 0u,
        unspendablePunishmentReserve = null,
        userChannelId = "0",
        feerateSatPer1000Weight = 0u,
        outboundCapacityMsat = 0u,
        inboundCapacityMsat = 0u,
        confirmationsRequired = null,
        confirmations = null,
        isOutbound = false,
        isChannelReady = false,
        isUsable = false,
        isAnnounced = false,
        cltvExpiryDelta = null,
        counterpartyUnspendablePunishmentReserve = 0u,
        counterpartyOutboundHtlcMinimumMsat = null,
        counterpartyOutboundHtlcMaximumMsat = null,
        counterpartyForwardingInfoFeeBaseMsat = null,
        counterpartyForwardingInfoFeeProportionalMillionths = null,
        counterpartyForwardingInfoCltvExpiryDelta = null,
        nextOutboundHtlcLimitMsat = 0u,
        nextOutboundHtlcMinimumMsat = 0u,
        forceCloseSpendDelay = null,
        inboundHtlcMinimumMsat = 0u,
        inboundHtlcMaximumMsat = null,
        config = ChannelConfig(
            forwardingFeeProportionalMillionths = 0u,
            forwardingFeeBaseMsat = 0u,
            cltvExpiryDelta = 0u,
            maxDustHtlcExposure = MaxDustHtlcExposure.FixedLimit(limitMsat = 0u),
            forceCloseAvoidanceMaxFeeSatoshis = 0u,
            acceptUnderpayingHtlcs = false,
        ),
    )
}

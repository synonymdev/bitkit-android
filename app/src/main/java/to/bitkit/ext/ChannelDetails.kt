package to.bitkit.ext

import org.lightningdevkit.ldknode.ChannelConfig
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.MaxDustHtlcExposure

/**
 * Calculates our total balance in the channel (see `value_to_self_msat` in rust-lightning).
 *
 * This represents the amount we would receive if the channel closes now (excluding fees).
 * Approximates ldk-node's `ClaimableOnChannelClose.amountSatoshis` (excluding HTLCs).
 *
 * Formula: outbound_capacity + our_reserve
 * - outbound_capacity: What we can spend now over Lightning
 * - our_reserve: Our reserve that we get back on close
 */
val ChannelDetails.amountOnClose: ULong
    @Suppress("ForbiddenComment")
    get() {
        // TODO: use channelDetails.claimableOnCloseSats
        val outboundCapacitySat = this.outboundCapacityMsat / 1000u
        val ourReserve = this.unspendablePunishmentReserve ?: 0u

        return outboundCapacitySat + ourReserve
    }

/** Returns only `open` channels, filtering out pending ones. */
fun List<ChannelDetails>.filterOpen(): List<ChannelDetails> = this.filter { it.isChannelReady }

/** Returns only `pending` channels. */
fun List<ChannelDetails>.filterPending(): List<ChannelDetails> = this.filterNot { it.isChannelReady }

/** Returns a limit in sats as close as possible to the HTLC limit we can currently send. */
fun List<ChannelDetails>?.totalNextOutboundHtlcLimitSats(): ULong = this?.filter { it.isUsable }
    ?.sumOf { it.nextOutboundHtlcLimitMsat / 1000u }
    ?: 0u

/** Calculates the total remote balance (inbound capacity) from open channels. */
fun List<ChannelDetails>.calculateRemoteBalance(): ULong = this
    .filterOpen()
    .sumOf { it.inboundCapacityMsat / 1000u }

fun createChannelDetails(): ChannelDetails = ChannelDetails(
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
    claimableOnCloseSats = 0u,
    config = ChannelConfig(
        forwardingFeeProportionalMillionths = 0u,
        forwardingFeeBaseMsat = 0u,
        cltvExpiryDelta = 0u,
        maxDustHtlcExposure = MaxDustHtlcExposure.FixedLimit(limitMsat = 0u),
        forceCloseAvoidanceMaxFeeSatoshis = 0u,
        acceptUnderpayingHtlcs = false,
    ),
)

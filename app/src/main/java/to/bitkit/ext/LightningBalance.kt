package to.bitkit.ext

import org.lightningdevkit.ldknode.LightningBalance

fun LightningBalance.balanceTypeString(): String {
    return when (this) {
        is LightningBalance.ClaimableOnChannelClose -> "Claimable on Channel Close"
        is LightningBalance.ClaimableAwaitingConfirmations -> "Claimable Awaiting Confirmations (Height: $confirmationHeight)"
        is LightningBalance.ContentiousClaimable -> "Contentious Claimable"
        is LightningBalance.MaybeTimeoutClaimableHtlc -> "Maybe Timeout Claimable HTLC"
        is LightningBalance.MaybePreimageClaimableHtlc -> "Maybe Preimage Claimable HTLC"
        is LightningBalance.CounterpartyRevokedOutputClaimable -> "Counterparty Revoked Output Claimable"
    }
}

fun LightningBalance.amountLong(): Long {
    return when (this) {
        is LightningBalance.ClaimableOnChannelClose -> this.amountSatoshis.toLong()
        is LightningBalance.ClaimableAwaitingConfirmations -> this.amountSatoshis.toLong()
        is LightningBalance.ContentiousClaimable -> this.amountSatoshis.toLong()
        is LightningBalance.MaybeTimeoutClaimableHtlc -> this.amountSatoshis.toLong()
        is LightningBalance.MaybePreimageClaimableHtlc -> this.amountSatoshis.toLong()
        is LightningBalance.CounterpartyRevokedOutputClaimable -> this.amountSatoshis.toLong()
    }
}

fun LightningBalance.channelIdString(): String {
    return when (this) {
        is LightningBalance.ClaimableOnChannelClose -> this.channelId
        is LightningBalance.ClaimableAwaitingConfirmations -> this.channelId
        is LightningBalance.ContentiousClaimable -> this.channelId
        is LightningBalance.MaybeTimeoutClaimableHtlc -> this.channelId
        is LightningBalance.MaybePreimageClaimableHtlc -> this.channelId
        is LightningBalance.CounterpartyRevokedOutputClaimable -> this.channelId
    }
}

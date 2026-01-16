package to.bitkit.ext

import org.lightningdevkit.ldknode.LightningBalance

fun LightningBalance.amountSats(): ULong {
    return when (this) {
        is LightningBalance.ClaimableOnChannelClose -> this.amountSatoshis
        is LightningBalance.ClaimableAwaitingConfirmations -> this.amountSatoshis
        is LightningBalance.ContentiousClaimable -> this.amountSatoshis
        is LightningBalance.MaybeTimeoutClaimableHtlc -> this.amountSatoshis
        is LightningBalance.MaybePreimageClaimableHtlc -> this.amountSatoshis
        is LightningBalance.CounterpartyRevokedOutputClaimable -> this.amountSatoshis
    }
}

fun LightningBalance.channelId(): String {
    return when (this) {
        is LightningBalance.ClaimableOnChannelClose -> this.channelId
        is LightningBalance.ClaimableAwaitingConfirmations -> this.channelId
        is LightningBalance.ContentiousClaimable -> this.channelId
        is LightningBalance.MaybeTimeoutClaimableHtlc -> this.channelId
        is LightningBalance.MaybePreimageClaimableHtlc -> this.channelId
        is LightningBalance.CounterpartyRevokedOutputClaimable -> this.channelId
    }
}

fun LightningBalance.balanceUiText(): String {
    return when (this) {
        is LightningBalance.ClaimableOnChannelClose -> "Claimable on Channel Close"
        is LightningBalance.ClaimableAwaitingConfirmations ->
            "Claimable Awaiting Confirmations (Height: $confirmationHeight)"
        is LightningBalance.ContentiousClaimable -> "Contentious Claimable"
        is LightningBalance.MaybeTimeoutClaimableHtlc -> "Maybe Timeout Claimable HTLC"
        is LightningBalance.MaybePreimageClaimableHtlc -> "Maybe Preimage Claimable HTLC"
        is LightningBalance.CounterpartyRevokedOutputClaimable -> "Counterparty Revoked Output Claimable"
    }
}

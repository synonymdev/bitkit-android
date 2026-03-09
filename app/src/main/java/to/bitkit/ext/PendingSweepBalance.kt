package to.bitkit.ext

import org.lightningdevkit.ldknode.PendingSweepBalance

fun PendingSweepBalance.channelId(): String? {
    return when (this) {
        is PendingSweepBalance.PendingBroadcast -> this.channelId
        is PendingSweepBalance.BroadcastAwaitingConfirmation -> this.channelId
        is PendingSweepBalance.AwaitingThresholdConfirmations -> this.channelId
    }
}

fun PendingSweepBalance.latestSpendingTxid(): String? {
    return when (this) {
        is PendingSweepBalance.PendingBroadcast -> null
        is PendingSweepBalance.BroadcastAwaitingConfirmation -> this.latestSpendingTxid
        is PendingSweepBalance.AwaitingThresholdConfirmations -> this.latestSpendingTxid
    }
}

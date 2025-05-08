package to.bitkit.models

import kotlinx.serialization.Serializable

@Suppress("EnumEntryName")
@Serializable
enum class BlocktankNotificationType {
    incomingHtlc,
    mutualClose,
    orderPaymentConfirmed,
    cjitPaymentArrived,
    wakeToTimeout;

    override fun toString(): String = "blocktank.$name"
}

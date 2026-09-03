package to.bitkit.ui.screens.wallets.receive

import to.bitkit.utils.ServiceError

internal fun Throwable.isCjitMaxAmountError(): Boolean {
    val description = toString()
    return this is ServiceError.ChannelSizeExceedsMaximum ||
        description.contains("Channel size is too big") ||
        description.contains("channelSizeExceedsMaximum") ||
        description.contains("maxChannelSizeSat") ||
        description.contains("channelSizeSat")
}

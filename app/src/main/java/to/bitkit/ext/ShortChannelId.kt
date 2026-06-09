package to.bitkit.ext

/**
 * Decodes a BOLT short channel id into Core Lightning `block x tx x output` form
 * (e.g. `777477x916x0`): block height in the high 24 bits, transaction index in the next 24,
 * funding output index in the low 16.
 */
fun ULong.formattedAsShortChannelId(): String {
    val blockHeight = this shr 40
    val txIndex = (this shr 16) and 0xFFFFFFu
    val outputIndex = this and 0xFFFFu
    return "${blockHeight}x${txIndex}x$outputIndex"
}

/**
 * Short channel id for display. Uses the channel's own scid (open channels) and, for closed
 * channels which carry none, the scid from the confidently-linked order. Null when unavailable.
 */
internal fun resolveDisplayShortChannelId(channelScid: ULong?, linkedOrderScid: String?): String? =
    channelScid?.formattedAsShortChannelId()
        ?: linkedOrderScid?.toULongOrNull()?.formattedAsShortChannelId()

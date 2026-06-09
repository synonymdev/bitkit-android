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

private val CLN_SHORT_CHANNEL_ID = Regex("""\d+x\d+x\d+""")

/**
 * Short channel id for display. Uses the channel's own scid (open channels, a numeric BOLT scid)
 * and, for closed channels which carry none, the scid from the confidently-linked Blocktank order.
 * Blocktank delivers it already in `block x tx x output` form, so an `x`-formatted value is kept
 * as-is and only a numeric value is decoded. Null when unavailable.
 */
internal fun resolveDisplayShortChannelId(channelScid: ULong?, linkedOrderScid: String?): String? {
    channelScid?.let { return it.formattedAsShortChannelId() }

    val orderScid = linkedOrderScid?.takeIf { it.isNotBlank() } ?: return null
    orderScid.toULongOrNull()?.let { return it.formattedAsShortChannelId() }
    return orderScid.takeIf { CLN_SHORT_CHANNEL_ID.matches(it) }
}

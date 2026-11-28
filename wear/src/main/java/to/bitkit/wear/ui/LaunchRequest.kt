package to.bitkit.wear.ui

import android.content.Intent

/** What an intent that opened the app asks for, beyond showing it. */
sealed interface LaunchRequest {
    /** Pay the payment input of a `bitcoin:`, `lightning:`, `lnurl:`, `lnurlp:` or `spark:` link, in any case. */
    data class Send(val input: String) : LaunchRequest

    /** Show the receive QR, as from the tile. */
    data object Receive : LaunchRequest

    companion object {
        /** The extra that asks for [Receive], set by the tile. */
        const val EXTRA_OPEN = "to.bitkit.wear.OPEN"
        const val OPEN_RECEIVE = "receive"

        /** The manifest's link schemes, lowercased. `lnurlw` and `lnurlc` are left out: the watch only pays. */
        private val SEND_SCHEMES = setOf("bitcoin", "lightning", "lnurl", "lnurlp", "spark")

        /** The request of an intent with [action], [data] and the [open] extra, or null for a plain launch. */
        fun from(action: String?, data: String?, open: String?): LaunchRequest? {
            if (open == OPEN_RECEIVE) return Receive
            if (action != Intent.ACTION_VIEW || data.isNullOrBlank()) return null
            val scheme = data.substringBefore(':', missingDelimiterValue = "").lowercase()
            return if (scheme in SEND_SCHEMES) Send(data) else null
        }

        fun from(intent: Intent?): LaunchRequest? =
            intent?.let { from(it.action, it.dataString, it.getStringExtra(EXTRA_OPEN)) }
    }
}

package to.bitkit.wear.ui

import android.content.Intent

/** What an intent that opened the app asks for, beyond showing it. */
sealed interface LaunchRequest {
    /** Pay the payment input of a `lightning:`, `bitcoin:` or `spark:` link. */
    data class Send(val input: String) : LaunchRequest

    companion object {
        private val SEND_SCHEMES = setOf("lightning", "bitcoin", "spark")

        /** The request of an intent with [action] and [data], or null for a plain launch. */
        fun from(action: String?, data: String?): LaunchRequest? {
            if (action != Intent.ACTION_VIEW || data.isNullOrBlank()) return null
            val scheme = data.substringBefore(':', missingDelimiterValue = "").lowercase()
            return if (scheme in SEND_SCHEMES) Send(data) else null
        }

        fun from(intent: Intent?): LaunchRequest? =
            intent?.let { from(it.action, it.dataString) }
    }
}

package to.bitkit.utils

import to.bitkit.ext.runSuspendCatching

object ClipboardPromptValidator {
    private val lightningAddressPattern = Regex("^[a-z0-9._-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$")
    private val lnurlPattern = Regex(
        "^(?:(http.*|bitcoin:.*)[&?]lightning=|lightning:)?lnurl1[02-9ac-hj-np-z]+",
        RegexOption.IGNORE_CASE,
    )

    suspend fun isSupported(input: String, decode: suspend (String) -> Unit): Boolean {
        var payload = input.trim().removePrefix("lightning:")
        if (payload.startsWith("bitkit://")) {
            payload = payload.replace("bitkit://", "")
        }
        payload = payload.trim().removePrefix("lightning:")
        if (!payload.startsWith("bitcoin:", ignoreCase = true) &&
            (lnurlPattern.containsMatchIn(payload) || lightningAddressPattern.matches(payload))
        ) {
            return true
        }
        return runSuspendCatching { decode(input) }.isSuccess
    }
}

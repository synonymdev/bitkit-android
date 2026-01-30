package to.bitkit.utils

import to.bitkit.models.SATS_IN_BTC

object Bip21Utils {

    private const val BIP21_PREFIX = "bitcoin:"

    /**
     * Checks if a BIP21 URI is duplicated (contains multiple bitcoin: prefixes).
     * Workaround for https://github.com/synonymdev/bitkit-core/issues/63
     * @return true if the input contains duplicated BIP21 URIs, false otherwise
     */
    fun isDuplicatedBip21(input: String): Boolean {
        val lowercased = input.lowercase()
        val firstIndex = lowercased.indexOf(BIP21_PREFIX)
        if (firstIndex == -1) return false

        val secondIndex = lowercased.indexOf(BIP21_PREFIX, firstIndex + BIP21_PREFIX.length)
        return secondIndex != -1
    }

    fun buildBip21Url(
        bitcoinAddress: String,
        amountSats: ULong? = null,
        label: String? = null,
        message: String? = "Bitkit",
        lightningInvoice: String? = null
    ): String {
        val builder = StringBuilder("bitcoin:$bitcoinAddress")

        val queryParams = mutableListOf<String>()

        // Add amount if specified (convert from sats to BTC)
        amountSats?.let {
            queryParams.add("amount=${formatBtcAmount(amountSats)}")
        }

        // Add optional parameters
        if (!label.isNullOrBlank()) { queryParams.add("label=${label.encodeToUrl()}") }
        if (!message.isNullOrBlank()) { queryParams.add("message=${message.encodeToUrl()}") }

        // Add query parameters if any exist
        if (queryParams.isNotEmpty()) {
            builder.append("?${queryParams.joinToString("&")}")
        }

        // Add lightning parameter if invoice exists
        if (!lightningInvoice.isNullOrBlank()) {
            val separator = if (queryParams.isEmpty()) "?" else "&"
            val encodedInvoice = lightningInvoice.encodeToUrl()
            if (encodedInvoice.isNotBlank()) {
                builder.append("${separator}lightning=${lightningInvoice.encodeToUrl()}")
            }
        }

        return builder.toString()
    }

    private fun formatBtcAmount(sats: ULong): String {
        val fullBtc = sats / SATS_IN_BTC.toULong()
        val remainderSats = sats % SATS_IN_BTC.toULong()

        return if (remainderSats == 0uL) {
            fullBtc.toString()
        } else {
            val remainderStr = remainderSats.toString().padStart(8, '0')
            "$fullBtc.${remainderStr.trimEnd('0')}"
        }
    }
}

fun String.encodeToUrl(): String = runCatching { java.net.URLEncoder.encode(this, "UTF-8") }.getOrElse { "" }

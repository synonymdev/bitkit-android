package to.bitkit.utils

object Bip21Utils {

    fun buildBip21Url(
        bitcoinAddress: String,
        amountSats: ULong? = null,
        label: String? = null,
        message: String? = null,
        lightningInvoice: String? = null
    ): String {
        val builder = StringBuilder("bitcoin:$bitcoinAddress")

        val queryParams = mutableListOf<String>()

        // Add amount if specified (convert from sats to BTC)
        amountSats?.let {
            val btcAmount = it.toDouble() / 100_000_000.0
            queryParams.add("amount=${btcAmount.toString().removeSuffix(".0")}")
        }

        // Add optional parameters
        label?.let { queryParams.add("label=${it.encodeToUrl()}") }
        message?.let { queryParams.add("message=${it.encodeToUrl()}") }

        // Add query parameters if any exist
        if (queryParams.isNotEmpty()) {
            builder.append("?${queryParams.joinToString("&")}")
        }

        // Add lightning parameter if invoice exists
        lightningInvoice?.let { invoice ->
            val separator = if (queryParams.isEmpty()) "?" else "&"
            builder.append("${separator}lightning=${invoice.encodeToUrl()}")
        }

        return builder.toString()
    }

}

fun String.encodeToUrl(): String = runCatching { java.net.URLEncoder.encode(this, "UTF-8") }.getOrElse { "" }


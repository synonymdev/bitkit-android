package to.bitkit.wear.services

import com.synonym.bitkitcore.LnurlPayData
import com.synonym.bitkitcore.Scanner
import com.synonym.bitkitcore.getLnurlInvoiceForPayData
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads payment inputs the way the phone does. Every bitkit-core payment call of the watch goes through this
 * interface. Both calls can reach the network: an LNURL or a Lightning address is fetched from its host.
 */
interface InputDecoder {
    /** Decodes a BOLT11 invoice, an LNURL, a Lightning address or a BIP21 URI, with or without its URI scheme. */
    suspend fun decode(input: String): Scanner

    /** Asks the LNURL-pay service of [data] for a BOLT11 invoice of [amountMsats]. */
    suspend fun lnurlInvoice(data: LnurlPayData, amountMsats: ULong): String
}

/** [InputDecoder] backed by bitkit-core, as on the phone. */
@Singleton
class CoreInputDecoder @Inject constructor() : InputDecoder {
    override suspend fun decode(input: String): Scanner = com.synonym.bitkitcore.decode(input)

    override suspend fun lnurlInvoice(data: LnurlPayData, amountMsats: ULong): String =
        getLnurlInvoiceForPayData(data, amountMsats, null)
}

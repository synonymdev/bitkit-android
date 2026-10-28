package to.bitkit.models

import kotlinx.serialization.Serializable
import org.lightningdevkit.ldknode.DirectFundingPaymentStatus
import to.bitkit.utils.AppError
import java.net.URLDecoder

/** The sender authorizes at most this fee for a direct channel contribution. */
const val DIRECT_FUNDING_MAX_FEE_SATS = 1000uL

/** The protocol requires at least this value to fund a channel. */
const val DIRECT_FUNDING_MIN_SATS = 5000uL

/** Stable activity namespace; native request IDs survive retries and restarts. */
const val DIRECT_FUNDING_ACTIVITY_PREFIX = "direct-funding:"

fun DirectFundingPaymentStatus.blocksAnotherPayment(): Boolean = when (this) {
    DirectFundingPaymentStatus.OFFERED,
    DirectFundingPaymentStatus.SIGNED_PENDING,
    DirectFundingPaymentStatus.MEMPOOL_SEEN,
    DirectFundingPaymentStatus.CONFIRMED,
    DirectFundingPaymentStatus.FAILED,
    -> true
    DirectFundingPaymentStatus.ABORTED -> false
}

/** Failed outbound records retain a signed witness; inbound failures may precede signing. */
fun DirectFundingPaymentStatus.hasCommittedFunding(isOutbound: Boolean = true): Boolean = when (this) {
    DirectFundingPaymentStatus.SIGNED_PENDING,
    DirectFundingPaymentStatus.MEMPOOL_SEEN,
    DirectFundingPaymentStatus.CONFIRMED,
    -> true
    DirectFundingPaymentStatus.FAILED -> isOutbound
    else -> false
}

/** Extract before generic URI decoding so malformed or repeated fields cannot fall back to another rail. */
fun directFundingParameter(input: String): Result<String?> = runCatching {
    val parameters = input.substringAfter('?', "").substringBefore('#').split('&').map {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to it.substringAfter('=', "")
    }.filter { it.first.equals("bgnq", ignoreCase = true) }
    if (parameters.isEmpty()) return@runCatching null
    if (parameters.size != 1) throw AppError("Repeated direct funding request")
    val request = URLDecoder.decode(parameters.single().second, "UTF-8")
    val hasValidCharacters = request.all {
        it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_'
    }
    if (request.isEmpty() || request.length > 65536 || !hasValidCharacters) {
        throw AppError("Malformed direct funding request")
    }
    request
}

fun appendDirectFundingParameter(invoice: String, request: String?): String {
    if (invoice.isBlank() || request.isNullOrEmpty()) return invoice
    val base = if (invoice.startsWith("bitcoin:", ignoreCase = true)) invoice else "bitcoin:$invoice"
    return "$base${if ('?' in base) '&' else '?'}bgnq=$request"
}

@Serializable
data class DirectFundingReceiveLink(
    val requestId: String,
    val lightningPaymentHash: String?,
    val onchainAddress: String,
    val primaryUri: String,
    val expiresAt: ULong,
    val invalidated: Boolean = false,
)

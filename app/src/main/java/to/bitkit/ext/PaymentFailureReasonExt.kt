package to.bitkit.ext

import android.content.Context
import org.lightningdevkit.ldknode.PaymentFailureReason
import to.bitkit.R
import to.bitkit.models.SendFailureDetails
import to.bitkit.utils.LdkError

fun PaymentFailureReason?.toUserMessage(context: Context): String = when (this) {
    PaymentFailureReason.RECIPIENT_REJECTED ->
        context.getString(R.string.wallet__payment_recipient_rejected)
    PaymentFailureReason.USER_ABANDONED ->
        context.getString(R.string.wallet__payment_abandoned)
    PaymentFailureReason.RETRIES_EXHAUSTED ->
        context.getString(R.string.wallet__payment_retries_exhausted)
    PaymentFailureReason.ROUTE_NOT_FOUND ->
        context.getString(R.string.wallet__payment_route_not_found)
    PaymentFailureReason.PAYMENT_EXPIRED ->
        context.getString(R.string.wallet__payment_expired)
    PaymentFailureReason.UNKNOWN_REQUIRED_FEATURES ->
        context.getString(R.string.wallet__payment_unknown_required_features)
    PaymentFailureReason.INVOICE_REQUEST_EXPIRED ->
        context.getString(R.string.wallet__payment_invoice_request_expired)
    PaymentFailureReason.INVOICE_REQUEST_REJECTED ->
        context.getString(R.string.wallet__payment_invoice_request_rejected)
    else -> context.getString(R.string.wallet__payment_failed_description)
}

fun PaymentFailureReason?.shouldResetRoutingCachesOnRetry(): Boolean =
    this == PaymentFailureReason.ROUTE_NOT_FOUND || this == PaymentFailureReason.RETRIES_EXHAUSTED

fun PaymentFailureReason?.toCompactFailureType(): String {
    return this?.name?.snakeToLowerCamel() ?: UNKNOWN_FAILURE_TYPE
}

fun PaymentFailureReason?.toSendFailureDetails(
    context: Context,
    paymentRequest: String? = null,
): SendFailureDetails {
    return SendFailureDetails(
        message = toUserMessage(context),
        failureType = toCompactFailureType(),
        resetRoutingCachesOnRetry = shouldResetRoutingCachesOnRetry(),
        paymentRequest = paymentRequest,
    )
}

fun Throwable.toSendFailureMessage(context: Context): String {
    val fallbackMessage = context.getString(R.string.wallet__payment_failed_description)
    val rawMessage = message?.trim().orEmpty()

    if (this is LdkError || rawMessage.isBlank() || rawMessage.looksInternalPaymentError()) {
        return fallbackMessage
    }

    return rawMessage
}

fun Throwable.toCompactFailureType(): String {
    if (this is LdkError) return compactType ?: UNKNOWN_FAILURE_TYPE

    val rawValue = message?.trim()?.takeIf { it.isNotEmpty() }
        ?: this::class.simpleName
        ?: UNKNOWN_FAILURE_TYPE

    return rawValue.compactFailureType()
}

fun Throwable.toSendFailureDetails(
    context: Context,
    paymentRequest: String? = null,
): SendFailureDetails {
    return SendFailureDetails(
        message = toSendFailureMessage(context),
        failureType = toCompactFailureType(),
        resetRoutingCachesOnRetry = false,
        paymentRequest = paymentRequest,
    )
}

private fun String.snakeToLowerCamel(): String {
    return lowercase()
        .split("_")
        .filter { it.isNotBlank() }
        .mapIndexed { index, segment ->
            if (index == 0) segment else segment.replaceFirstChar { it.titlecase() }
        }
        .joinToString("")
        .ifBlank { UNKNOWN_FAILURE_TYPE }
}

private fun String.compactFailureType(): String {
    val strippedPrefixes = this
        .removePrefix("LDK Node error:")
        .removePrefix("LDK Build error:")
        .trim()
    val type = strippedPrefixes
        .substringBefore("(")
        .trim()
        .trimEnd('.')
        .trim()

    return type.toCompactTypeName()
}

private fun String.toCompactTypeName(): String {
    if (isBlank()) return UNKNOWN_FAILURE_TYPE
    if (none { it.isWhitespace() || it == '-' || it == '_' }) return this

    return split(Regex("[\\s_-]+"))
        .filter { it.isNotBlank() }
        .joinToString("") { it.replaceFirstChar(Char::titlecase) }
        .ifBlank { UNKNOWN_FAILURE_TYPE }
}

private fun String.looksInternalPaymentError(): Boolean {
    return INTERNAL_PAYMENT_ERROR_MARKERS.any { contains(it, ignoreCase = true) }
}

private val INTERNAL_PAYMENT_ERROR_MARKERS = listOf(
    "DuplicatePayment",
    "PaymentFailureReason",
    "ldknode",
    "LDK",
)

private const val UNKNOWN_FAILURE_TYPE = "Unknown"

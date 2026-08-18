package to.bitkit.models

data class SendFailureDetails(
    val message: String,
    val failureType: String,
    val resetRoutingCachesOnRetry: Boolean,
    val paymentRequest: String? = null,
) {
    fun shouldResetRoutingCaches(routingCacheResetAttempted: Boolean): Boolean {
        return resetRoutingCachesOnRetry && !routingCacheResetAttempted
    }
}

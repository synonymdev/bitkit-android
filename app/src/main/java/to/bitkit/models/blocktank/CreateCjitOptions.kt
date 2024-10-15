package to.bitkit.models.blocktank

import kotlinx.serialization.Serializable

@Serializable
data class CreateCjitRequest(
    val channelSizeSat: Int,
    val invoiceSat: Int,
    val invoiceDescription: String,
    val nodeId: String,
    val channelExpiryWeeks: Int,
    // region options
    val source: String? = null,
    val discountCode: String? = null,
    // endregion
) {
    fun withOptions(options: CreateCjitOptions): CreateCjitRequest {
        return this.copy(
            source = options.source,
            discountCode = options.discountCode,
        )
    }
}

@Serializable
data class CreateCjitOptions(
    /**
     * What created this order. Example: 'bitkit', 'widget'.
     */
    val source: String? = null,

    /**
     * User entered discount code.
     */
    val discountCode: String? = null,
)

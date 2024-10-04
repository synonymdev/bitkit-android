package to.bitkit.models.blocktank

import kotlinx.serialization.Serializable

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

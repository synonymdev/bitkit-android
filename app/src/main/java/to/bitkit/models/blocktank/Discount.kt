package to.bitkit.models.blocktank

import kotlinx.serialization.Serializable

@Serializable
data class Discount(
    /**
     * User provided discount code label.
     */
    val code: String,

    /**
     * Absolute discount given for this code.
     */
    val absoluteSat: Int,

    /**
     * Relative discount % given for this code.
     */
    val relative: Int,

    /**
     * Overall sum of the discount calculated by `fee * relative + absoluteSat`.
     */
    val overallSat: Int,
)

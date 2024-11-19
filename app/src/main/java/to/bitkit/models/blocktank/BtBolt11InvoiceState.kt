package to.bitkit.models.blocktank

import kotlinx.serialization.Serializable

@Suppress("EnumEntryName")
@Serializable
enum class BtBolt11InvoiceState {
    /**
     * Expect payment
     */
    pending,

    /**
     * Payment received but not confirmed/rejected yet. Only possible with HODL invoices.
     */
    holding,

    /**
     * Payment confirmed
     */
    paid,

    /**
     * Payment rejected or invoice expired.
     */
    canceled,
}

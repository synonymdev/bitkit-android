package to.bitkit.models

object ReceiveLiquidityDecision {
    fun canCreateLightningInvoice(
        hasReadyChannels: Boolean,
        inboundCapacitySats: ULong?,
        invoiceAmountSats: ULong?,
    ): Boolean {
        if (!hasReadyChannels || inboundCapacitySats == null) return false

        if (invoiceAmountSats == null || invoiceAmountSats == 0uL) {
            return inboundCapacitySats > 0uL
        }

        return invoiceAmountSats <= inboundCapacitySats
    }
}

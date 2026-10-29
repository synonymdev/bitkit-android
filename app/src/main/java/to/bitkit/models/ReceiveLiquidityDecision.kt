package to.bitkit.models

enum class ReceiveLiquiditySource {
    SAVINGS,
    AUTO,
    SPENDING,
}

sealed interface ReceiveAdditionalLiquidityAction {
    data object None : ReceiveAdditionalLiquidityAction

    /** More inbound capacity needs a new channel, and Blocktank no longer sells them. */
    data object TransferUnavailable : ReceiveAdditionalLiquidityAction

    /** The primary node opens or grows the channel for this receive, once the user approves its fee. */
    data object PrimaryReceive : ReceiveAdditionalLiquidityAction
}

data class ReceiveAdditionalLiquidityParams(
    val source: ReceiveLiquiditySource,
    val invoiceAmountSats: ULong,
    val inboundCapacitySats: ULong?,
    val hasPrimaryNode: Boolean = false,
)

object ReceiveLiquidityDecision {
    fun canCreateLightningInvoice(
        hasReadyChannels: Boolean,
        inboundCapacitySats: ULong?,
        invoiceAmountSats: ULong?,
        hasPrimaryInvoice: Boolean = false,
    ): Boolean {
        if (hasPrimaryInvoice) return true
        if (!hasReadyChannels || inboundCapacitySats == null) return false

        if (invoiceAmountSats == null || invoiceAmountSats == 0uL) {
            return inboundCapacitySats > 0uL
        }

        return invoiceAmountSats <= inboundCapacitySats
    }

    fun additionalLiquidityAction(params: ReceiveAdditionalLiquidityParams): ReceiveAdditionalLiquidityAction {
        return when {
            params.source == ReceiveLiquiditySource.SAVINGS -> ReceiveAdditionalLiquidityAction.None
            !needsInboundLiquidity(params.invoiceAmountSats, params.inboundCapacitySats) ->
                ReceiveAdditionalLiquidityAction.None
            params.hasPrimaryNode && params.invoiceAmountSats > 0uL -> ReceiveAdditionalLiquidityAction.PrimaryReceive
            params.source != ReceiveLiquiditySource.SPENDING -> ReceiveAdditionalLiquidityAction.None
            (params.inboundCapacitySats ?: 0uL) == 0uL -> ReceiveAdditionalLiquidityAction.None
            else -> ReceiveAdditionalLiquidityAction.TransferUnavailable
        }
    }

    fun needsInboundLiquidity(
        invoiceAmountSats: ULong,
        inboundCapacitySats: ULong?,
    ): Boolean {
        val inbound = inboundCapacitySats ?: 0uL

        if (invoiceAmountSats == 0uL) {
            return inbound == 0uL
        }

        return invoiceAmountSats > inbound
    }
}

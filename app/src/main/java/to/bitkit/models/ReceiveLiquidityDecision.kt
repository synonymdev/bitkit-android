package to.bitkit.models

enum class ReceiveLiquiditySource {
    SAVINGS,
    AUTO,
    SPENDING,
}

sealed interface ReceiveAdditionalLiquidityAction {
    data object None : ReceiveAdditionalLiquidityAction
    data object ChooseAmount : ReceiveAdditionalLiquidityAction
    data class CreateCjit(val amountSats: ULong) : ReceiveAdditionalLiquidityAction
    data object GeoBlocked : ReceiveAdditionalLiquidityAction
}

data class ReceiveAdditionalLiquidityParams(
    val source: ReceiveLiquiditySource,
    val invoiceAmountSats: ULong,
    val inboundCapacitySats: ULong?,
    val minCjitSats: ULong?,
    val maxCjitAmountSats: ULong?,
    val isGeoBlocked: Boolean,
)

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

    fun additionalLiquidityAction(params: ReceiveAdditionalLiquidityParams): ReceiveAdditionalLiquidityAction {
        return when {
            params.source != ReceiveLiquiditySource.SPENDING -> ReceiveAdditionalLiquidityAction.None
            !needsInboundLiquidity(params.invoiceAmountSats, params.inboundCapacitySats) ->
                ReceiveAdditionalLiquidityAction.None
            (params.inboundCapacitySats ?: 0uL) == 0uL -> ReceiveAdditionalLiquidityAction.None
            params.isGeoBlocked -> ReceiveAdditionalLiquidityAction.GeoBlocked
            shouldChooseAmount(params) -> ReceiveAdditionalLiquidityAction.ChooseAmount
            else -> ReceiveAdditionalLiquidityAction.CreateCjit(params.invoiceAmountSats)
        }
    }

    fun needsCjitLimitsForAdditionalLiquidity(
        source: ReceiveLiquiditySource,
        invoiceAmountSats: ULong,
        inboundCapacitySats: ULong?,
        isGeoBlocked: Boolean,
    ): Boolean {
        if (source != ReceiveLiquiditySource.SPENDING) return false
        if (!needsInboundLiquidity(invoiceAmountSats, inboundCapacitySats)) return false
        if ((inboundCapacitySats ?: 0uL) == 0uL) return false

        return !isGeoBlocked
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

    private fun shouldChooseAmount(params: ReceiveAdditionalLiquidityParams): Boolean {
        val min = params.minCjitSats ?: 0uL
        val max = params.maxCjitAmountSats?.takeIf { it > 0uL } ?: return true
        if (params.invoiceAmountSats == 0uL || min == 0uL) return true

        return params.invoiceAmountSats < min || params.invoiceAmountSats > max
    }
}

package to.bitkit.models

import to.bitkit.utils.ServiceError

object CjitQuoteValidator {
    fun validate(
        invoiceSat: ULong,
        feeSat: ULong,
        channelSizeSat: ULong,
    ): Result<Unit> {
        if (feeSat >= invoiceSat) {
            return Result.failure(ServiceError.CjitQuoteInvalid())
        }

        val netReceiveSat = invoiceSat - feeSat
        if (channelSizeSat < netReceiveSat) {
            return Result.failure(ServiceError.CjitQuoteInvalid())
        }

        return Result.success(Unit)
    }
}

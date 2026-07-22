package to.bitkit.models

import com.synonym.bitkitcore.ActivityFilter
import com.synonym.bitkitcore.PaymentType
import kotlinx.serialization.Serializable

@Serializable
data class NewTransactionSheetDetails(
    val type: NewTransactionSheetType,
    val direction: NewTransactionSheetDirection,
    val paymentHashOrTxId: String? = null,
    val activityId: String? = null,
    val activityWalletId: String? = null,
    val sats: Long = 0,
    val isLoadingDetails: Boolean = false,
) {
    companion object {
        val EMPTY = NewTransactionSheetDetails(
            type = NewTransactionSheetType.LIGHTNING,
            direction = NewTransactionSheetDirection.RECEIVED,
        )
    }
}

@Serializable
enum class NewTransactionSheetType {
    ONCHAIN, LIGHTNING
}

@Serializable
enum class NewTransactionSheetDirection {
    SENT, RECEIVED
}

fun NewTransactionSheetDirection.toTxType(): PaymentType {
    return if (this == NewTransactionSheetDirection.SENT) {
        PaymentType.SENT
    } else {
        PaymentType.RECEIVED
    }
}

fun NewTransactionSheetType.toActivityFilter(): ActivityFilter {
    return if (this == NewTransactionSheetType.ONCHAIN) {
        ActivityFilter.ONCHAIN
    } else {
        ActivityFilter.LIGHTNING
    }
}

package to.bitkit.models

import kotlinx.serialization.Serializable

@Serializable
sealed class TransactionSheet(
    val type: NewTransactionSheetType,
    val direction: NewTransactionSheetDirection,
) {
    data class SendOnChain(
        val sats: ULong,
        val fee: ULong,
        val isSelfSend: Boolean,
        val isTransfer: Boolean,
        val timestamp: ULong,
        val tags: List<String> = emptyList(),
    ) :
        TransactionSheet(
            type = NewTransactionSheetType.ONCHAIN,
            direction = NewTransactionSheetDirection.SENT
        )
}

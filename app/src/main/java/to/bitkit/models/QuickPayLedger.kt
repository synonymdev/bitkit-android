package to.bitkit.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class QuickPayRecordPhase {
    @SerialName("submitting")
    SUBMITTING,

    @SerialName("submitted")
    SUBMITTED,
}

@Serializable
data class QuickPayLedgerRecord(
    val id: String,
    val amountCents: Long,
    val dayKey: String,
    val invoicePaymentHash: String,
    val paymentId: String? = null,
    val phase: QuickPayRecordPhase,
)

@Serializable
data class QuickPayLedger(
    val version: Int,
    val dayKey: String,
    val spentCents: Long,
    val records: List<QuickPayLedgerRecord> = emptyList(),
)

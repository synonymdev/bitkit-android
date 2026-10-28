package to.bitkit.models

import kotlinx.serialization.Serializable
import to.bitkit.repositories.PaykitAllowance
import to.bitkit.repositories.PaykitAllowanceLimits
import to.bitkit.repositories.PaykitAllowanceLocalState
import to.bitkit.repositories.PaykitPaymentRequestId
import java.math.BigDecimal
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Instant
import java.time.Instant as JavaInstant

/**
 * One identity's allowance state in the wallet backup, in the wire format both apps share: dates are RFC 3339 strings
 * with milliseconds and stages are lowercase. A restored wallet keeps its journal, so recovery can still settle open
 * attempts, and its automatic-payment history.
 */
@Serializable
data class PaykitAllowanceStateBackup(
    val groups: List<Group> = emptyList(),
    val journal: List<JournalEntry> = emptyList(),
    val autoPaidSatsByAllowance: Map<String, ULong> = emptyMap(),
    val autoPaidRequestIds: Set<PaykitPaymentRequestId> = emptySet(),
    val presentedProposalIds: Set<String> = emptySet(),
    val unannouncedOfferIds: Set<String> = emptySet(),
    val notifiedRequestIds: Set<String> = emptySet(),
    val lastTrustedTime: String? = null,
) {
    @Serializable
    data class Group(
        val id: String,
        val counterparty: String,
        val limits: Limits,
        val allowanceIds: List<String>,
        val createdAt: String,
    )

    /** US dollar limits travel as decimal strings, as iOS keeps them. */
    @Serializable
    data class Limits(
        val perPaymentUsd: String,
        val monthlyUsd: String,
        val perPaymentSats: ULong,
        val monthlySats: ULong,
    )

    @Serializable
    data class JournalEntry(
        val attemptId: String,
        val isAutomatic: Boolean,
        val requestId: PaykitPaymentRequestId,
        val allowanceId: String? = null,
        val amountSats: ULong,
        val paymentEndpointIdentifier: String,
        val paymentHash: String? = null,
        val onchainAddress: String? = null,
        val transactionId: String? = null,
        val stage: String,
        val createdAt: String,
        val paymentListVersion: ULong? = null,
        val previousPaymentListVersion: ULong? = null,
    )

    constructor(state: PaykitAllowanceLocalState) : this(
        groups = state.groups.map {
            val limits = Limits(
                perPaymentUsd = it.limits.perPaymentUsd.toString(),
                monthlyUsd = it.limits.monthlyUsd.toString(),
                perPaymentSats = it.limits.perPaymentSats,
                monthlySats = it.limits.monthlySats,
            )
            Group(it.id, it.counterparty, limits, it.allowanceIds, isoTime(it.createdAtMillis))
        },
        journal = state.journal.map {
            JournalEntry(
                attemptId = it.attemptId,
                isAutomatic = it.isAutomatic,
                requestId = it.requestId,
                allowanceId = it.allowanceId,
                amountSats = it.amountSats,
                paymentEndpointIdentifier = it.paymentEndpointIdentifier,
                paymentHash = it.paymentHash,
                onchainAddress = it.onchainAddress,
                transactionId = it.transactionId,
                stage = it.stage.name.lowercase(Locale.ROOT),
                createdAt = isoTime(it.createdAtMillis),
                paymentListVersion = it.paymentListVersion,
                previousPaymentListVersion = it.previousPaymentListVersion,
            )
        },
        autoPaidSatsByAllowance = state.autoPaidSatsByAllowance,
        autoPaidRequestIds = state.autoPaidRequestIds,
        presentedProposalIds = state.presentedProposalIds,
        unannouncedOfferIds = state.acceptingOfferIds,
        notifiedRequestIds = state.notifiedRequestIds,
        lastTrustedTime = state.lastTrustedTimeMillis?.let(::isoTime),
    )

    fun restored(): PaykitAllowanceLocalState {
        val state = PaykitAllowanceLocalState(
            groups = groups.map {
                val limits = PaykitAllowanceLimits(
                    perPaymentUsd = BigDecimal(it.limits.perPaymentUsd).toInt(),
                    monthlyUsd = BigDecimal(it.limits.monthlyUsd).toInt(),
                    perPaymentSats = it.limits.perPaymentSats,
                    monthlySats = it.limits.monthlySats,
                )
                PaykitAllowanceLocalState.Group(it.id, it.counterparty, limits, it.allowanceIds, millis(it.createdAt))
            },
            journal = journal.map {
                PaykitAllowanceLocalState.JournalEntry(
                    attemptId = it.attemptId,
                    isAutomatic = it.isAutomatic,
                    requestId = it.requestId,
                    allowanceId = it.allowanceId,
                    amountSats = it.amountSats,
                    paymentEndpointIdentifier = it.paymentEndpointIdentifier,
                    paymentHash = it.paymentHash,
                    onchainAddress = it.onchainAddress,
                    transactionId = it.transactionId,
                    // A stage this version does not know stays open, so recovery settles it from the node.
                    stage = stage(it.stage),
                    createdAtMillis = millis(it.createdAt),
                    paymentListVersion = it.paymentListVersion,
                    previousPaymentListVersion = it.previousPaymentListVersion,
                )
            },
            presentedProposalIds = presentedProposalIds,
            acceptingOfferIds = unannouncedOfferIds,
            notifiedRequestIds = notifiedRequestIds,
            lastTrustedTimeMillis = lastTrustedTime?.let(::millis),
            autoPaidSatsByAllowance = autoPaidSatsByAllowance,
            autoPaidRequestIds = autoPaidRequestIds,
        )
        if (autoPaidSatsByAllowance.isNotEmpty() || autoPaidRequestIds.isNotEmpty()) return state
        // A backup written by iOS keeps no lasting totals, so they are rebuilt from the restored journal.
        val paid = state.journal.filter { it.isAutomatic && it.stage == PaykitAllowanceLocalState.Stage.SUCCEEDED }
        return state.copy(
            autoPaidSatsByAllowance = paid
                .groupBy { PaykitAllowance.key(it.requestId.counterparty, it.allowanceId) }
                .mapValues { (_, payments) ->
                    payments.fold(0uL) { total, payment -> total.safe() + payment.amountSats.safe() }
                },
            autoPaidRequestIds = paid.mapTo(mutableSetOf()) { it.requestId },
        )
    }
}

private fun stage(value: String): PaykitAllowanceLocalState.Stage =
    PaykitAllowanceLocalState.Stage.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        ?: PaykitAllowanceLocalState.Stage.UNKNOWN

private val isoTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

private fun isoTime(millis: Long): String = isoTimeFormat.format(JavaInstant.ofEpochMilli(millis))

private fun millis(isoTime: String): Long = Instant.parse(isoTime).toEpochMilliseconds()

@file:OptIn(ExperimentalTime::class)

package to.bitkit.repositories

import com.synonym.paykit.BillingPeriod
import com.synonym.paykit.PaymentRequestLifecycleState
import com.synonym.paykit.PaymentRequestLocalRole
import com.synonym.paykit.PaymentRequestRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Serializable
data class PaykitBillingPeriod(
    val startsAt: Instant,
    val endsAt: Instant,
) {
    val sdkValue: BillingPeriod
        get() = BillingPeriod(startsAt.toString(), endsAt.toString())
}

enum class PaykitRecurrenceUnit(val rawValue: String) {
    Minute("minute"),
    Hour("hour"),
    Day("day"),
    Week("week"),
    Month("month"),
    Year("year");

    val isSupported: Boolean
        get() = this !in setOf(Minute, Hour)

    companion object {
        fun fromRawValue(value: String): PaykitRecurrenceUnit? = entries.firstOrNull { it.rawValue == value }
    }
}

data class PaykitSubscriptionRecurrence(
    val every: Int,
    val unit: PaykitRecurrenceUnit,
    val startsAt: Instant,
    val anchor: Instant,
    val endsAt: Instant?,
) {
    val canMaterializePeriods: Boolean
        get() = firstBoundaryIndexAfter(startsAt) != null

    @Suppress("ReturnCount")
    fun periodsThrough(date: Instant, acceptedAt: Instant): List<PaykitBillingPeriod> {
        if (!unit.isSupported || startsAt > date) return emptyList()
        val periods = mutableListOf<PaykitBillingPeriod>()
        var start = startsAt
        var index = firstBoundaryIndexAfter(start) ?: return emptyList()
        repeat(MAX_PERIODS) {
            if (start > date) return periods
            var end = boundary(index++) ?: return periods
            if (end <= start) end = addInterval(start) ?: return periods
            endsAt?.let {
                if (start >= it) return periods
                if (end > it) end = it
            }
            if (end <= start) return periods
            if (end > acceptedAt) periods += PaykitBillingPeriod(start, end)
            start = end
        }
        return periods
    }

    @Suppress("ReturnCount")
    fun nextPeriodAfter(date: Instant): PaykitBillingPeriod? {
        var start = startsAt
        var index = firstBoundaryIndexAfter(start) ?: return null
        repeat(MAX_PERIODS) {
            var end = boundary(index++) ?: return null
            if (end <= start) end = addInterval(start) ?: return null
            endsAt?.let {
                if (start >= it) return null
                if (end > it) end = it
            }
            if (start > date) return PaykitBillingPeriod(start, end)
            start = end
        }
        return null
    }

    fun upcomingPeriodsAfter(date: Instant, limit: Int): List<PaykitBillingPeriod> {
        if (limit <= 0) return emptyList()
        val periods = mutableListOf<PaykitBillingPeriod>()
        var cursor = date
        repeat(limit.coerceAtMost(MAX_PERIODS)) {
            val period = nextPeriodAfter(cursor) ?: return periods
            periods += period
            cursor = period.startsAt
        }
        return periods
    }

    private fun firstBoundaryIndexAfter(date: Instant): Int? {
        var index = 0
        val anchorBoundary = boundary(index) ?: return null
        if (anchorBoundary > date) {
            while (index > -MAX_PERIODS && boundary(index - 1)?.let { it > date } == true) index--
        } else {
            while (index < MAX_PERIODS && boundary(index)?.let { it <= date } == true) index++
        }

        boundary(index)?.takeIf { it > date } ?: return null
        boundary(index - 1)?.takeIf { it <= date } ?: return null
        return index
    }

    private fun boundary(index: Int): Instant? = runCatching {
        val value = every.toLong() * index
        when (unit) {
            PaykitRecurrenceUnit.Minute -> anchor.utc().plusMinutes(value)
            PaykitRecurrenceUnit.Hour -> anchor.utc().plusHours(value)
            PaykitRecurrenceUnit.Day -> anchor.utc().plusDays(value)
            PaykitRecurrenceUnit.Week -> anchor.utc().plusWeeks(value)
            PaykitRecurrenceUnit.Month -> anchor.utc().plusMonths(value)
            PaykitRecurrenceUnit.Year -> anchor.utc().plusYears(value)
        }.toKotlinInstant()
    }.getOrNull()

    private fun addInterval(date: Instant): Instant? = runCatching {
        val value = every.toLong()
        when (unit) {
            PaykitRecurrenceUnit.Minute -> date.utc().plusMinutes(value)
            PaykitRecurrenceUnit.Hour -> date.utc().plusHours(value)
            PaykitRecurrenceUnit.Day -> date.utc().plusDays(value)
            PaykitRecurrenceUnit.Week -> date.utc().plusWeeks(value)
            PaykitRecurrenceUnit.Month -> date.utc().plusMonths(value)
            PaykitRecurrenceUnit.Year -> date.utc().plusYears(value)
        }.toKotlinInstant()
    }.getOrNull()

    private companion object {
        const val MAX_PERIODS = 10_000
    }
}

data class PaykitSubscriptionMetadata(
    val description: String?,
    val benefits: List<String>,
)

@Serializable
data class PaykitSubscriptionId(
    val paymentRequestId: String,
    val counterparty: String,
    val counterpartyReceiverPath: String,
)

data class PaykitSubscription(
    val paymentRequestId: String,
    val counterparty: String,
    val counterpartyReceiverPath: String,
    val amountValue: String,
    val amountSats: ULong,
    val note: String?,
    val createdAt: Instant?,
    val proposalExpiresAt: Instant?,
    val recurrence: PaykitSubscriptionRecurrence,
    val metadata: PaykitSubscriptionMetadata,
    val acceptedPaymentEndpointIdentifiers: List<String>,
    val lifecycleState: PaymentRequestLifecycleState,
    val paidPeriods: List<PaykitBillingPeriod>,
    val paymentProofKinds: Map<PaykitBillingPeriod, PaykitPaymentProofKind> = emptyMap(),
) {
    val id: PaykitSubscriptionId
        get() = PaykitSubscriptionId(paymentRequestId, counterparty, counterpartyReceiverPath)

    fun isProposalVisible(now: Instant): Boolean =
        lifecycleState == PaymentRequestLifecycleState.PROPOSED &&
            proposalExpiresAt?.let { it > now } != false &&
            recurrence.endsAt?.let { it > now } != false

    fun isProposalActionable(now: Instant): Boolean =
        isProposalVisible(now) &&
            recurrence.unit.isSupported &&
            recurrence.canMaterializePeriods &&
            acceptedPaymentEndpointIdentifiers.isNotEmpty()

    fun isActive(now: Instant): Boolean =
        lifecycleState == PaymentRequestLifecycleState.ACTIVE_RECURRING && recurrence.endsAt?.let { it > now } != false

    fun isExpired(now: Instant): Boolean = lifecycleState in setOf(
        PaymentRequestLifecycleState.CANCELED,
        PaymentRequestLifecycleState.REJECTED,
        PaymentRequestLifecycleState.PROPOSAL_EXPIRED,
    ) || (lifecycleState == PaymentRequestLifecycleState.PROPOSED && proposalExpiresAt?.let { it <= now } == true) ||
        recurrence.endsAt?.let { it <= now } == true

    fun withExpiredLifecycle(now: Instant): PaykitSubscription = when {
        lifecycleState != PaymentRequestLifecycleState.PROPOSED -> this
        proposalExpiresAt?.let { it <= now } == true || recurrence.endsAt?.let { it <= now } == true -> {
            copy(lifecycleState = PaymentRequestLifecycleState.PROPOSAL_EXPIRED)
        }
        else -> this
    }

    fun requestsThrough(date: Instant, acceptedAt: Instant): List<PaykitPaymentRequest> =
        recurrence.periodsThrough(date, acceptedAt).map { period ->
            PaykitPaymentRequest(
                paymentRequestId = paymentRequestId,
                counterparty = counterparty,
                counterpartyReceiverPath = counterpartyReceiverPath,
                amountValue = amountValue,
                amountSats = amountSats,
                note = note,
                createdAt = period.startsAt,
                expiresAt = null,
                acceptedPaymentEndpointIdentifiers = acceptedPaymentEndpointIdentifiers,
                lifecycleState = if (period in paidPeriods) {
                    PaymentRequestLifecycleState.PROOF_SUBMITTED
                } else {
                    PaymentRequestLifecycleState.ACTIVE_RECURRING
                },
                billingPeriod = period,
                paymentProofKind = paymentProofKinds[period],
            )
        }

    fun paymentDueOnAcceptance(now: Instant): PaykitPaymentRequest? = requestsThrough(now, now).firstOrNull()
}

@Suppress("CyclomaticComplexMethod", "ReturnCount")
internal fun PaymentRequestRecord.toPaykitSubscription(): PaykitSubscription? {
    if (localRole != PaymentRequestLocalRole.PAYER) return null
    val requestTerms = terms ?: return null
    val sdkRecurrence = requestTerms.recurrence ?: return null
    if (
        requestTerms.amount.asset != "btc" ||
        sdkRecurrence.every == 0u ||
        sdkRecurrence.every > Int.MAX_VALUE.toUInt()
    ) {
        return null
    }
    val recurrenceUnit = PaykitRecurrenceUnit.fromRawValue(sdkRecurrence.unit) ?: return null
    val startsAt = sdkRecurrence.startsAt.parseInstant() ?: return null
    val anchor = sdkRecurrence.anchor.parseInstant() ?: return null
    val recurrenceEndsAt = sdkRecurrence.endsAt?.parseInstant()
        ?: if (sdkRecurrence.endsAt == null) null else return null
    val proposalExpiresAt = requestTerms.proposalExpiresAt?.parseInstant()
        ?: if (requestTerms.proposalExpiresAt == null) null else return null
    if (recurrenceEndsAt != null && recurrenceEndsAt <= startsAt) return null
    val amountSats = requestTerms.amount.value.toPaykitSats()
        ?.takeIf { it <= ULong.MAX_VALUE / 1000uL }
        ?: return null
    val endpoints = requestTerms.acceptedPaymentEndpointIdentifiers
        .filter { MethodId.fromRawValue(it) != null }
        .distinct()
    val metadataObject = requestTerms.metadata.subscriptionMetadata()
    val payments = paymentProofs.mapNotNull { proof ->
        val period = proof.billingPeriod ?: return@mapNotNull null
        val periodStart = period.startsAt.parseInstant() ?: return@mapNotNull null
        val periodEnd = period.endsAt.parseInstant() ?: return@mapNotNull null
        val billingPeriod = PaykitBillingPeriod(periodStart, periodEnd).takeIf { periodStart < periodEnd }
            ?: return@mapNotNull null
        billingPeriod to PaykitPaymentProofKind.fromPaymentEndpointIdentifier(proof.paymentEndpointIdentifier)
    }
    return PaykitSubscription(
        paymentRequestId = paymentRequestId,
        counterparty = counterparty,
        counterpartyReceiverPath = counterpartyReceiverPath,
        amountValue = requestTerms.amount.value,
        amountSats = amountSats,
        note = requestTerms.metadata.note()?.take(256),
        createdAt = lastEventAt?.parseInstant(),
        proposalExpiresAt = proposalExpiresAt,
        recurrence = PaykitSubscriptionRecurrence(
            every = sdkRecurrence.every.toInt(),
            unit = recurrenceUnit,
            startsAt = startsAt,
            anchor = anchor,
            endsAt = recurrenceEndsAt,
        ),
        metadata = metadataObject,
        acceptedPaymentEndpointIdentifiers = endpoints,
        lifecycleState = state,
        paidPeriods = payments.map { it.first },
        paymentProofKinds = payments.mapNotNull { (period, kind) -> kind?.let { period to it } }.toMap(),
    )
}

private fun com.synonym.paykit.PrivateJsonObject.subscriptionMetadata(): PaykitSubscriptionMetadata = runCatching {
    val subscription = Json.parseToJsonElement(exportText()).jsonObject["subscription"]?.jsonObject
        ?: return@runCatching PaykitSubscriptionMetadata(null, emptyList())
    if (subscription["version"]?.jsonPrimitive?.contentOrNull != "1") {
        return@runCatching PaykitSubscriptionMetadata(null, emptyList())
    }
    val description = subscription["description"]?.jsonPrimitive?.contentOrNull?.clean(1024)
    val benefits = subscription["benefits"]?.jsonArray.orEmpty()
        .take(8)
        .mapNotNull { it.jsonPrimitive.contentOrNull?.clean(160) }
    PaykitSubscriptionMetadata(description, benefits)
}.getOrDefault(PaykitSubscriptionMetadata(null, emptyList()))

private fun String.clean(limit: Int): String? = trim().take(limit).takeIf(String::isNotEmpty)

private fun String.parseInstant(): Instant? = runCatching { Instant.parse(this) }.getOrNull()

private fun Instant.utc(): ZonedDateTime = java.time.Instant.parse(toString()).atZone(ZoneOffset.UTC)

private fun ZonedDateTime.toKotlinInstant(): Instant = Instant.parse(toInstant().toString())

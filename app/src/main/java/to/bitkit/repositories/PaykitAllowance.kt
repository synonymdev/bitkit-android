package to.bitkit.repositories

import androidx.compose.runtime.Immutable
import com.synonym.paykit.AllowanceAccountingHistory
import com.synonym.paykit.AllowanceAmountRange
import com.synonym.paykit.AllowanceLifecycleState
import com.synonym.paykit.AllowanceLocalRole
import com.synonym.paykit.AllowancePeriod
import com.synonym.paykit.AllowancePeriodLimit
import com.synonym.paykit.AllowanceRecord
import com.synonym.paykit.AllowanceTerms
import com.synonym.paykit.PaymentExecutionMode
import com.synonym.paykit.PaymentExecutionStatus
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

/**
 * An Allowance between this wallet and one contact link, built from the SDK record.
 * Eligibility always runs on real time.
 */
@Immutable
data class PaykitAllowance(
    val id: Id,
    val role: Role,
    val lifecycleState: AllowanceLifecycleState,
    val isProposedByMe: Boolean,
    val perPaymentMaxSats: ULong?,
    val monthlyLimitSats: ULong?,
    val monthlyAnchor: Instant?,
    val activeFrom: Instant? = null,
    val expiresAt: Instant? = null,
    val allowedPaymentEndpointIdentifiers: List<String>? = null,
    val lastEventAt: Instant? = null,
) {
    @Serializable
    data class Id(
        val counterparty: String,
        val counterpartyReceiverPath: String,
        val allowanceId: String,
    )

    @Serializable
    enum class Role { ALLOWER, ALLOWEE }

    enum class Status {
        /** Sent by this wallet; the other side has not answered yet. */
        AWAITING_ANSWER,

        /** Received; this wallet must accept or decline. */
        AWAITING_MY_ANSWER,
        ACTIVE,
        NOT_YET_ACTIVE,
        EXPIRED,
        DECLINED,
        ENDED,
        CONFLICTED,
    }

    val counterparty: String get() = id.counterparty
    val counterpartyReceiverPath: String get() = id.counterpartyReceiverPath
    val allowanceId: String get() = id.allowanceId

    /** The payer side: this wallet pays the counterparty's requests automatically. */
    val isAllower: Boolean get() = role == Role.ALLOWER

    val canEnd: Boolean
        get() = when (lifecycleState) {
            AllowanceLifecycleState.ACCEPTED -> true
            AllowanceLifecycleState.PROPOSED -> isProposedByMe
            else -> false
        }

    val isAnswerable: Boolean get() = lifecycleState == AllowanceLifecycleState.PROPOSED && !isProposedByMe

    fun status(now: Instant): Status = when (lifecycleState) {
        AllowanceLifecycleState.PROPOSED -> if (isProposedByMe) Status.AWAITING_ANSWER else Status.AWAITING_MY_ANSWER
        AllowanceLifecycleState.ACCEPTED -> when {
            expiresAt != null && now >= expiresAt -> Status.EXPIRED
            activeFrom != null && now < activeFrom -> Status.NOT_YET_ACTIVE
            else -> Status.ACTIVE
        }
        AllowanceLifecycleState.REJECTED -> Status.DECLINED
        AllowanceLifecycleState.ENDED -> Status.ENDED
        AllowanceLifecycleState.CONFLICTED, AllowanceLifecycleState.UNKNOWN -> Status.CONFLICTED
    }

    companion object {
        fun from(record: AllowanceRecord): PaykitAllowance? {
            val role = when (record.localRole) {
                AllowanceLocalRole.ALLOWER -> Role.ALLOWER
                AllowanceLocalRole.ALLOWEE -> Role.ALLOWEE
                else -> return null
            }
            val terms = record.terms ?: return null
            if (terms.asset() != PaykitIssuerInterop.BITCOIN_ASSET) return null
            val monthly = terms.periodLimits().firstOrNull { isMonthly(it.period()) }
            return PaykitAllowance(
                id = Id(record.counterparty, record.counterpartyReceiverPath, record.allowanceId),
                role = role,
                lifecycleState = record.state,
                isProposedByMe = record.proposalOutboundMessageId != null,
                perPaymentMaxSats = terms.perPaymentAmount()?.let { satsFromBitcoinAmount(it.maximum()) },
                monthlyLimitSats = monthly?.amountLimit()?.let(::satsFromBitcoinAmount),
                monthlyAnchor = monthly?.period()?.anchor()?.let(PaykitAllowanceTime::parse),
                activeFrom = terms.activeFrom()?.let(PaykitAllowanceTime::parse),
                expiresAt = terms.expiresAt()?.let(PaykitAllowanceTime::parse),
                allowedPaymentEndpointIdentifiers = terms.allowedPaymentEndpointIdentifiers(),
                lastEventAt = record.lastEventAt?.let(PaykitAllowanceTime::parse),
            )
        }

        fun isMonthly(period: AllowancePeriod): Boolean =
            period.kind() == "anchored" && period.every() == 1uL && period.unit() == "month"

        /** BTC decimal string to sats; unlike [toPaykitSats] a zero amount is valid here. */
        fun satsFromBitcoinAmount(amount: String): ULong? {
            if (amount.split('.').all { part -> part.all { it == '0' } }) return 0uL
            return amount.toPaykitSats()
        }
    }
}

/** One grant as the user sees it: the same limits proposed on each of a contact's supported links. */
@Immutable
data class PaykitAllowanceEntry(
    val id: String,
    val allowances: List<PaykitAllowance>,
    val limits: PaykitAllowanceLimits?,
) {
    val primary: PaykitAllowance
        get() = allowances.firstOrNull { it.lifecycleState == AllowanceLifecycleState.ACCEPTED } ?: allowances.first()
    val counterparty: String get() = primary.counterparty
    val role: PaykitAllowance.Role get() = primary.role
    val perPaymentMaxSats: ULong? get() = primary.perPaymentMaxSats
    val monthlyLimitSats: ULong? get() = primary.monthlyLimitSats
    val canEnd: Boolean get() = allowances.any { it.canEnd }
    val isAnswerable: Boolean get() = allowances.any { it.isAnswerable }

    fun status(now: Instant): PaykitAllowance.Status = primary.status(now)
}

/** Limits picked in USD on the Set Allowance sheet, converted once to whole-sat BTC terms. */
@Serializable
@Immutable
data class PaykitAllowanceLimits(
    val perPaymentUsd: Int,
    val monthlyUsd: Int,
    val perPaymentSats: ULong,
    val monthlySats: ULong,
) {
    /** Terms Bitkit proposes: per-payment range 0...max, an anchored UTC calendar month, and the endpoints Bitkit pays. */
    fun terms(monthAnchor: Instant, allowedPaymentEndpointIdentifiers: List<String>): AllowanceTerms {
        val perPayment = AllowanceAmountRange(minimum = "0", maximum = perPaymentSats.toBitcoinDecimal())
        val month = AllowancePeriod(
            kind = "anchored",
            every = 1uL,
            unit = "month",
            anchor = PaykitAllowanceTime.format(monthAnchor),
        )
        val monthly = AllowancePeriodLimit(
            amountLimit = monthlySats.toBitcoinDecimal(),
            paymentCountLimit = null,
            period = month,
        )
        return AllowanceTerms(
            asset = PaykitIssuerInterop.BITCOIN_ASSET,
            perPaymentAmount = perPayment,
            periodLimits = listOf(monthly),
            lifetimeAmountLimit = null,
            activeFrom = null,
            expiresAt = null,
            allowedPaymentEndpointIdentifiers = allowedPaymentEndpointIdentifiers,
        )
    }

    companion object {
        /** Slider stops on the Set Allowance sheet, in whole US dollars. */
        val PER_PAYMENT_STOPS_USD = listOf(1, 5, 10, 20, 50)

        /** Slider stops on the Set Allowance sheet, in whole US dollars. */
        val MONTHLY_STOPS_USD = listOf(10, 50, 100, 200, 500)
    }
}

internal fun ULong.toBitcoinDecimal(): String =
    BigDecimal(toString()).movePointLeft(8).stripTrailingZeros().toPlainString()

object PaykitAllowanceTime {
    private val utc = ZoneOffset.UTC

    fun format(instant: Instant): String =
        DateTimeFormatter.ISO_INSTANT.format(instant.toJavaInstant().truncatedTo(ChronoUnit.MILLIS))

    fun parse(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()

    /** First instant of the UTC calendar month that contains [instant]. */
    fun monthStart(containing: Instant): Instant = ZonedDateTime.ofInstant(containing.toJavaInstant(), utc)
        .withDayOfMonth(1)
        .truncatedTo(ChronoUnit.DAYS)
        .toInstant()
        .toKotlinInstant()

    /**
     * The anchored monthly window `[start, end)` that contains [instant]. Anchors on a day that a short month lacks
     * clamp to that month's last day, as the spec's anchored-period arithmetic does.
     */
    fun monthlyWindow(anchor: Instant, containing: Instant): Pair<Instant, Instant> {
        val anchorTime = ZonedDateTime.ofInstant(anchor.toJavaInstant(), utc)
        val dateTime = ZonedDateTime.ofInstant(containing.toJavaInstant(), utc)
        // Every boundary counts from the original anchor, so a clamped February never shortens later months.
        val boundary = { index: Long -> anchorTime.plusMonths(index).toInstant().toKotlinInstant() }
        var index = (dateTime.year - anchorTime.year) * 12L + dateTime.monthValue - anchorTime.monthValue
        while (boundary(index) > containing) index--
        while (boundary(index + 1) <= containing) index++
        return boundary(index) to boundary(index + 1)
    }
}

/**
 * Wallet-side capacity preflight. The SDK checks capacity only after automatic Acceptance, so Bitkit sums this
 * Allowance's live automatic attempts in the current month first and keeps an over-cap request on the manual flow.
 */
object PaykitAllowanceCapacity {
    data class Attempt(
        val allowanceId: String,
        val amountSats: ULong,
        val admittedAt: Instant,
        val isLive: Boolean,
    )

    fun usedSats(allowanceId: String, attempts: List<Attempt>, anchor: Instant, now: Instant): ULong {
        val (start, end) = PaykitAllowanceTime.monthlyWindow(anchor, now)
        return attempts
            .filter { it.allowanceId == allowanceId && it.isLive && it.admittedAt >= start && it.admittedAt < end }
            .fold(0uL) { total, attempt -> total + attempt.amountSats }
    }

    fun fits(amountSats: ULong, allowance: PaykitAllowance, attempts: List<Attempt>, now: Instant): Boolean {
        val perPaymentMax = allowance.perPaymentMaxSats
        if (perPaymentMax != null && amountSats > perPaymentMax) return false
        val monthlyLimit = allowance.monthlyLimitSats ?: return true
        val anchor = allowance.monthlyAnchor ?: return true
        return usedSats(allowance.allowanceId, attempts, anchor, now) + amountSats <= monthlyLimit
    }

    fun attempts(history: AllowanceAccountingHistory): List<Attempt> = history.occurrences
        .flatMap { it.attempts }
        .mapNotNull { attempt ->
            if (attempt.mode != PaymentExecutionMode.AUTOMATIC) return@mapNotNull null
            val allowanceId = attempt.allowanceId ?: return@mapNotNull null
            val admittedAt = PaykitAllowanceTime.parse(attempt.admittedAt) ?: return@mapNotNull null
            val amountSats = PaykitAllowance.satsFromBitcoinAmount(attempt.amount.value()) ?: return@mapNotNull null
            Attempt(
                allowanceId = allowanceId,
                amountSats = amountSats,
                admittedAt = admittedAt,
                isLive = attempt.status != PaymentExecutionStatus.FAILED,
            )
        }
}

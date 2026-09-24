@file:OptIn(ExperimentalTime::class)

package to.bitkit.ui.screens.subscriptions

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.paykit.AllowanceLifecycleState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.models.Toast
import to.bitkit.models.USD
import to.bitkit.models.USD_SYMBOL
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.PaykitAllowance
import to.bitkit.repositories.PaykitAllowanceEntry
import to.bitkit.repositories.PaykitAllowanceError
import to.bitkit.repositories.PaykitAllowanceLimits
import to.bitkit.repositories.PaykitAllowanceRepo
import to.bitkit.repositories.PaykitPaymentRequestId
import to.bitkit.repositories.PaykitPaymentRequestRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@HiltViewModel
class AllowancesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val allowanceRepo: PaykitAllowanceRepo,
    private val paymentRequestRepo: PaykitPaymentRequestRepo,
    private val pubkyRepo: PubkyRepo,
    private val currencyRepo: CurrencyRepo,
    private val clock: Clock,
) : ViewModel() {
    companion object {
        /** How often time-based statuses (not active yet, expired) are re-evaluated while the UI is visible. */
        private val STATUS_REFRESH_INTERVAL = 1.minutes

        /** Keeps the state alive across short configuration changes. */
        private const val STOP_TIMEOUT_MS = 5_000L
    }

    private val isWorking = MutableStateFlow(false)

    private val now: Flow<Instant> = flow {
        while (true) {
            emit(clock.now())
            delay(STATUS_REFRESH_INTERVAL)
        }
    }

    private val allowances: Flow<ImmutableList<AllowanceUi>> = combine(
        allowanceRepo.entries,
        allowanceRepo.autoPaidSats,
        pubkyRepo.contacts,
        currencyRepo.currencyState,
        now,
    ) { entries, autoPaidSats, contacts, _, now ->
        entries.map { it.toUi(contacts, autoPaidSats[it.id] ?: 0uL, now) }.toImmutableList()
    }

    private val contactChoices: Flow<ImmutableList<PubkyProfile>> = combine(
        pubkyRepo.contacts,
        paymentRequestRepo.eligibleTargets,
    ) { contacts, targets ->
        contacts.filter { contact ->
            targets.any { PubkyPublicKeyFormat.matches(it.publicKey, contact.publicKey) }
        }.toImmutableList()
    }

    // A fresh subscriber starts from the unloaded state, so a sheet never renders a stale entry list.
    val uiState: StateFlow<AllowancesUiState> = combine(
        allowances,
        contactChoices,
        isWorking,
    ) { allowances, contacts, isWorking ->
        AllowancesUiState(
            isLoaded = true,
            allowances = allowances,
            contacts = contacts,
            isWorking = isWorking,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS, replayExpirationMillis = 0),
        initialValue = AllowancesUiState(),
    )

    /** Payment request ids paid by an allowance, for the "Auto-paid" suffix in payment history. */
    val autoPaidRequestIds: StateFlow<ImmutableSet<PaykitPaymentRequestId>> = combine(
        paymentRequestRepo.paymentRequestHistory,
        allowanceRepo.autoPaidSats,
    ) { history, _ ->
        history.map { it.id }.filter(allowanceRepo::isAutoPaid).toImmutableSet()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), persistentSetOf())

    fun refresh() {
        viewModelScope.launch { allowanceRepo.refresh() }
    }

    fun markProposalPresented(entryId: String) {
        viewModelScope.launch { allowanceRepo.markProposalPresented(entryId) }
    }

    fun propose(contact: PubkyProfile, perPaymentUsd: Int, monthlyUsd: Int, onSuccess: () -> Unit) {
        val limits = limitsInSats(perPaymentUsd, monthlyUsd)
        if (limits == null) {
            viewModelScope.launch { toastError(context.getString(R.string.subscriptions__allowance_error_unavailable)) }
            return
        }
        runAction(onSuccess) { allowanceRepo.propose(contact.publicKey, limits) }
    }

    fun accept(entryId: String, onSuccess: () -> Unit) = runAction(onSuccess) { allowanceRepo.accept(entryId) }

    fun decline(entryId: String, onSuccess: () -> Unit) = runAction(onSuccess) { allowanceRepo.reject(entryId) }

    fun end(entryId: String, onSuccess: () -> Unit) = runAction(onSuccess) { allowanceRepo.end(entryId) }

    private fun runAction(onSuccess: () -> Unit, action: suspend () -> Result<Unit>) {
        if (isWorking.value) return
        isWorking.update { true }
        viewModelScope.launch {
            action()
                .onSuccess { onSuccess() }
                .onFailure { toastError(errorDescription(it)) }
            isWorking.update { false }
        }
    }

    private fun errorDescription(error: Throwable): String = when (error) {
        PaykitAllowanceError.ContactNotLinked -> context.getString(R.string.subscriptions__allowance_error_not_linked)
        PaykitAllowanceError.Unavailable -> context.getString(R.string.subscriptions__allowance_error_unavailable)
        else -> error.message?.takeIf(String::isNotBlank) ?: context.getString(R.string.common__error_body)
    }

    private suspend fun toastError(description: String) = ToastEventBus.send(
        type = Toast.ToastType.ERROR,
        title = context.getString(R.string.common__error),
        description = description,
    )

    private fun limitsInSats(perPaymentUsd: Int, monthlyUsd: Int): PaykitAllowanceLimits? {
        val perPaymentSats = currencyRepo.convertFiatToSats(BigDecimal(perPaymentUsd), USD).getOrNull() ?: return null
        val monthlySats = currencyRepo.convertFiatToSats(BigDecimal(monthlyUsd), USD).getOrNull() ?: return null
        return PaykitAllowanceLimits(
            perPaymentUsd = perPaymentUsd,
            monthlyUsd = monthlyUsd,
            perPaymentSats = perPaymentSats,
            monthlySats = monthlySats,
        )
    }

    private fun PaykitAllowanceEntry.toUi(contacts: List<PubkyProfile>, paidSats: ULong, now: Instant): AllowanceUi {
        val profile = contacts.firstOrNull { PubkyPublicKeyFormat.matches(it.publicKey, counterparty) }
            ?: PubkyProfile.placeholder(counterparty)
        val status = status(now)
        val isAllowee = role == PaykitAllowance.Role.ALLOWEE
        return AllowanceUi(
            id = id,
            counterparty = profile,
            status = status,
            subtitle = subtitle(status, paidSats),
            explanation = explanation(status),
            reviewHeadline = context.getString(
                if (isAllowee) {
                    R.string.subscriptions__allowance_offer_headline
                } else {
                    R.string.subscriptions__allowance_request_headline
                }
            ).replace("{name}", profile.name),
            reviewExplanation = context.getString(
                if (isAllowee) {
                    R.string.subscriptions__allowance_offer_explanation
                } else {
                    R.string.subscriptions__allowance_request_explanation
                }
            ).replace("{name}", profile.name),
            monthlyAmount = limitAmount(limits?.monthlyUsd, monthlyLimitSats),
            perPaymentUpTo = upTo(limits?.perPaymentUsd, perPaymentMaxSats),
            monthlyUpTo = upTo(limits?.monthlyUsd, monthlyLimitSats),
            perPaymentSats = perPaymentMaxSats?.toDisplaySats(),
            monthlySats = monthlyLimitSats?.toDisplaySats(),
            paidSoFar = USD_SYMBOL + fiatValue(paidSats),
            isAnswerable = primary.isAnswerable,
            canEnd = canEnd,
            isProposal = primary.lifecycleState == AllowanceLifecycleState.PROPOSED,
        )
    }

    private fun PaykitAllowanceEntry.subtitle(status: PaykitAllowance.Status, paidSats: ULong): String = when (status) {
        PaykitAllowance.Status.ACTIVE -> listOfNotNull(
            context.getString(R.string.subscriptions__allowance_status_active),
            perPaymentShort(),
        ).joinToString(" · ")
        PaykitAllowance.Status.ENDED -> {
            val ended = context.getString(R.string.subscriptions__allowance_status_ended)
            if (paidSats == 0uL) {
                ended
            } else {
                val paid = context.getString(R.string.subscriptions__allowance_paid_automatically)
                    .replace("{amount}", USD_SYMBOL + fiatValue(paidSats))
                "$ended · $paid"
            }
        }
        else -> statusText(status)
    }

    private fun PaykitAllowanceEntry.explanation(status: PaykitAllowance.Status): String = when (status) {
        PaykitAllowance.Status.ACTIVE -> context.getString(
            if (role == PaykitAllowance.Role.ALLOWER) {
                R.string.subscriptions__allowance_detail_active_allower
            } else {
                R.string.subscriptions__allowance_detail_active_allowee
            }
        )
        PaykitAllowance.Status.ENDED -> context.getString(R.string.subscriptions__allowance_detail_ended)
        else -> statusText(status)
    }

    private fun statusText(status: PaykitAllowance.Status): String = context.getString(
        when (status) {
            PaykitAllowance.Status.ACTIVE -> R.string.subscriptions__allowance_status_active
            PaykitAllowance.Status.AWAITING_ANSWER -> R.string.subscriptions__allowance_status_waiting
            PaykitAllowance.Status.AWAITING_MY_ANSWER -> R.string.subscriptions__allowance_status_needs_answer
            PaykitAllowance.Status.NOT_YET_ACTIVE -> R.string.subscriptions__allowance_status_scheduled
            PaykitAllowance.Status.EXPIRED -> R.string.subscriptions__allowance_status_expired
            PaykitAllowance.Status.DECLINED -> R.string.subscriptions__allowance_status_declined
            PaykitAllowance.Status.ENDED -> R.string.subscriptions__allowance_status_ended
            PaykitAllowance.Status.CONFLICTED -> R.string.subscriptions__allowance_status_conflicted
        }
    )

    private fun PaykitAllowanceEntry.perPaymentShort(): String? {
        val amount = limits?.perPaymentUsd?.let(AllowanceAmountText::short)
            ?: perPaymentMaxSats?.let { USD_SYMBOL + limitValue(it) }
            ?: return null
        return context.getString(R.string.subscriptions__allowance_per_payment_short).replace("{amount}", amount)
    }

    private fun upTo(usd: Int?, sats: ULong?): String {
        val amount = usd?.let { USD_SYMBOL + AllowanceAmountText.formatted(BigDecimal(it)) }
            ?: sats?.let { USD_SYMBOL + limitValue(it) }
            ?: AllowanceAmountText.UNKNOWN
        return context.getString(R.string.subscriptions__allowance_up_to).replace("{amount}", amount)
    }

    private fun limitAmount(usd: Int?, sats: ULong?): String =
        usd?.let { AllowanceAmountText.formatted(BigDecimal(it)) }
            ?: sats?.let(::limitValue)
            ?: AllowanceAmountText.UNKNOWN

    private fun fiatValue(sats: ULong): String = usdValue(sats)?.let(AllowanceAmountText::formatted)
        ?: AllowanceAmountText.UNKNOWN

    private fun limitValue(sats: ULong): String = usdValue(sats)?.let(AllowanceAmountText::limit)
        ?: AllowanceAmountText.UNKNOWN

    private fun usdValue(sats: ULong): BigDecimal? =
        currencyRepo.convertSatsToFiat(sats.toDisplaySats(), USD).getOrNull()?.value
}

@Stable
data class AllowancesUiState(
    val isLoaded: Boolean = false,
    val allowances: ImmutableList<AllowanceUi> = persistentListOf(),
    val contacts: ImmutableList<PubkyProfile> = persistentListOf(),
    val isWorking: Boolean = false,
)

/** One allowance entry as the UI shows it, with every amount already converted to US dollars. */
@Stable
data class AllowanceUi(
    val id: String,
    val counterparty: PubkyProfile,
    val status: PaykitAllowance.Status,
    val subtitle: String,
    val explanation: String,
    val reviewHeadline: String,
    val reviewExplanation: String,
    val monthlyAmount: String,
    val perPaymentUpTo: String,
    val monthlyUpTo: String,
    val perPaymentSats: Long?,
    val monthlySats: Long?,
    val paidSoFar: String,
    val isAnswerable: Boolean,
    val canEnd: Boolean,
    val isProposal: Boolean,
) {
    val isInactive: Boolean
        get() = when (status) {
            PaykitAllowance.Status.ENDED,
            PaykitAllowance.Status.DECLINED,
            PaykitAllowance.Status.EXPIRED,
            PaykitAllowance.Status.CONFLICTED,
            -> true
            else -> false
        }
}

/** US dollar amounts as iOS shows them: grouped, en_US, with limits set in whole dollars rounded back to the dollar. */
internal object AllowanceAmountText {
    /** Shown when an amount cannot be converted, e.g. before exchange rates load. */
    const val UNKNOWN = "—"

    fun formatted(usd: BigDecimal): String = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US)).format(usd)

    fun short(usd: Int): String = USD_SYMBOL + DecimalFormat("#,##0", DecimalFormatSymbols(Locale.US)).format(usd)

    /**
     * A limit set in whole dollars on the other wallet, shown back from its BTC terms at today's rate:
     * rounded to the dollar so a small rate move does not turn $5 into $4.99.
     */
    fun limit(usd: BigDecimal): String =
        formatted(if (usd >= BigDecimal.ONE) usd.setScale(0, RoundingMode.HALF_UP) else usd)
}

private fun ULong.toDisplaySats(): Long = coerceAtMost(Long.MAX_VALUE.toULong()).toLong()

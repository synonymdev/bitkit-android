package to.bitkit.repositories

import com.synonym.bitkitcore.PaykitEndpoint
import com.synonym.bitkitcore.PaykitRoute
import com.synonym.bitkitcore.PaykitRouteRequest
import com.synonym.bitkitcore.ServiceRouteLeg
import com.synonym.bitkitcore.paykitRoutePayment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.toCoreNetwork
import to.bitkit.services.SparkError
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/**
 * Picks how to pay a Paykit contact through bitkit-core's Paykit router: directly from a balance the wallet holds,
 * or through a Spark withdrawal to the contact's on-chain endpoint when no balance reaches the contact. The router
 * makes no network calls; the app quotes each Spark leg only when no balance pays, and runs the one the router returns.
 */
@Singleton
class PaykitRouteRepo @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val sparkRepo: SparkRepo,
) {
    companion object {
        private const val TAG = "PaykitRouteRepo"
    }

    /** Whether Spark holds a balance it can send, so a Spark leg may reach a contact no other balance reaches. */
    val canRouteThroughSpark: Boolean
        get() = sparkRepo.state.value.let { it.isReady && it.sendableSats > 0uL }

    /**
     * Fails with [SparkError.WithdrawalOverBalance] when no route pays because Spark's balance covers the amount but
     * not the withdrawal fee on top, so the app tells a short balance from a contact it can't pay.
     */
    suspend fun route(
        amountSats: ULong,
        endpoints: List<PaykitEndpoint>,
        lightningSpendableSats: ULong,
        onchainSpendableSats: ULong,
        quoteServiceLegs: Boolean,
    ): Result<PaykitRoute> = withContext(ioDispatcher) {
        runSuspendCatching {
            val request = PaykitRouteRequest(
                amountSat = amountSats,
                contactEndpoints = endpoints,
                lightningSpendableSat = lightningSpendableSats,
                onchainSpendableSat = onchainSpendableSats,
                serviceLegs = emptyList(),
            )
            val network = Env.network.toCoreNetwork()
            // The router pays directly before it weighs any leg, so legs are quoted only when no balance pays.
            val route = paykitRoutePayment(request, network)
            if (!quoteServiceLegs || route !is PaykitRoute.None) return@runSuspendCatching route
            val quotes = serviceLegQuotes(endpoints, amountSats)
            val legs = quotes.mapNotNull { it.getOrNull() }
            if (legs.isNotEmpty()) {
                return@runSuspendCatching paykitRoutePayment(request.copy(serviceLegs = legs), network)
            }
            if (quotes.any { it.exceptionOrNull() is SparkError.WithdrawalOverBalance }) {
                throw SparkError.WithdrawalOverBalance()
            }
            route
        }
    }

    /** Spark withdrawals to the contact's on-chain endpoints, each quoted with its fee. */
    private suspend fun serviceLegQuotes(
        endpoints: List<PaykitEndpoint>,
        amountSats: ULong,
    ): List<Result<ServiceRouteLeg?>> =
        endpoints.filter { MethodId.fromRawValue(it.methodId)?.isOnchain == true }.map { endpoint ->
            sparkRepo.withdrawalRouteLeg(endpoint, amountSats)
                .onFailure { Logger.warn("No Spark route to '${endpoint.methodId}'", it, context = TAG) }
        }

    /**
     * Runs a service leg the router picked to the [contact] and returns the txid that pays them; [onBeforeSend] runs
     * once the leg is prepared, right before it sends; past [paymentDeadlineAt] it sends nothing.
     */
    suspend fun payServiceLeg(
        leg: ServiceRouteLeg,
        contact: String?,
        tags: List<String> = emptyList(),
        paymentDeadlineAt: Instant? = null,
        onBeforeSend: suspend () -> Boolean = { true },
    ): Result<String> = when (leg.service) {
        SparkRepo.ROUTE_SERVICE -> sparkRepo.withdraw(
            address = leg.endpoint.value,
            amountSats = leg.deliveredSat,
            contact = contact,
            maxDebitSats = leg.debitSat,
            tags = tags,
            paymentDeadlineAt = paymentDeadlineAt,
            onBeforeSend = onBeforeSend,
        )
        else -> Result.failure(AppError("Unsupported route service '${leg.service}'"))
    }
}

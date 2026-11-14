package to.bitkit.repositories

import com.synonym.bitkitcore.PaykitEndpoint
import com.synonym.bitkitcore.PaykitRoute
import com.synonym.bitkitcore.PaykitRouteRequest
import com.synonym.bitkitcore.PubkySwapException
import com.synonym.bitkitcore.PubkySwapRoutePayment
import com.synonym.bitkitcore.PubkySwapRouteState
import com.synonym.bitkitcore.PubkySwapSentPayment
import com.synonym.bitkitcore.ServiceRouteLeg
import com.synonym.bitkitcore.pubkySwapsAdvance
import com.synonym.bitkitcore.pubkySwapsCommitRoute
import com.synonym.bitkitcore.pubkySwapsRestore
import com.synonym.bitkitcore.pubkySwapsRoutePaykitPayment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import to.bitkit.async.appScope
import to.bitkit.data.CacheStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.models.toCoreNetwork
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Routes a Paykit payment through a Pubky Swaps provider when no balance the wallet holds reaches an endpoint the
 * contact published. bitkit-core picks the provider and checks its quote, contract and hold invoice; the wallet pays
 * the hold invoice from spending and bitkit-core claims the provider's lockup straight to the contact's address.
 */
@Singleton
class PubkySwapsRouteRepo @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val keychain: Keychain,
    private val pubkyRepo: PubkyRepo,
    private val lightningRepo: LightningRepo,
    private val cacheStore: CacheStore,
) {
    companion object {
        private const val TAG = "PubkySwapsRouteRepo"
        private const val CLAIM_FEE_RATE_SAT_VB = 2uL
        private val POLL_INTERVAL = 2.seconds
        private val CLAIM_TIMEOUT = 10.minutes

        /** How often a swap is advanced in the background, so bitkit-core rebroadcasts its claim until it confirms. */
        private val CONFIRMATION_POLL_INTERVAL = 30.seconds

        /** The most providers bitkit-core asks in one restore; it reports the others incomplete. */
        private const val MAX_RESTORE_PROVIDERS = 30

        /** How many times a restore asks the providers whose swap list it read only in part. */
        private const val RESTORE_ATTEMPTS = 3

        /** How long a restore waits before asking again the providers it read only in part. */
        private val RESTORE_RETRY_INTERVAL = 1.minutes
    }

    private val scope = appScope(ioDispatcher, TAG)

    /** Bumped by each wallet wipe, which ends the wiped wallet's restore. */
    private val wipes = MutableStateFlow(0L)

    /** Swaps a caller awaits the claim of, which no resume advances beside it. */
    private val claiming = ConcurrentHashMap.newKeySet<String>()

    /** The loop advancing each swap in the background, one per swap. */
    private val advancing = ConcurrentHashMap<String, Job>()

    val isEnabled: Boolean get() = Env.isPubkySwapsRouteEnabled

    /** The contact behind a provider's pubky, so the review shows who routes the payment. */
    fun providerContact(pubky: String): PubkyProfile? =
        pubkyRepo.contacts.value.firstOrNull { PubkyPublicKeyFormat.matches(it.publicKey, pubky) }

    suspend fun route(
        amountSats: ULong,
        endpoints: List<PaykitEndpoint>,
        lightningSpendableSats: ULong,
        onchainSpendableSats: ULong,
        serviceLegs: List<ServiceRouteLeg> = emptyList(),
    ): Result<PaykitRoute> = withSecrets { mnemonic, passphrase ->
        val candidates = candidates()
        Logger.debug("Routing Paykit payment via '${candidates.map(PubkyPublicKeyFormat::redacted)}'", context = TAG)
        pubkySwapsRoutePaykitPayment(
            request = PaykitRouteRequest(
                amountSat = amountSats,
                contactEndpoints = endpoints,
                lightningSpendableSat = lightningSpendableSats,
                onchainSpendableSat = onchainSpendableSats,
                providers = candidates,
                feeRateSatVb = CLAIM_FEE_RATE_SAT_VB,
                serviceLegs = serviceLegs,
            ),
            network = Env.network.toCoreNetwork(),
            mnemonic = mnemonic,
            bip39Passphrase = passphrase,
        )
    }

    /**
     * Rebuilds the swaps this wallet started from each provider's listing, after a restore from the seed, emitting the
     * swaps each restore found. It restores again whenever the contacts change, as a deferred Pubky session loads them
     * late, and a few times for providers whose list it read only in part; each restore asks only the providers not
     * yet read in full. It ends when the wallet is wiped.
     */
    fun restoredSwaps(): Flow<RestoredPubkySwaps> = channelFlow {
        val wallet = wipes.value
        val run = RestoreRun()
        val restore = launch {
            // A running restore finishes before the next contact list starts one, so none is cut short.
            val contactKeys = pubkyRepo.contacts.map { contacts -> contacts.map { it.publicKey }.toSet() }
            contactKeys.distinctUntilChanged().conflate().collect {
                repeat(RESTORE_ATTEMPTS) { attempt ->
                    if (attempt > 0) delay(RESTORE_RETRY_INTERVAL)
                    val swaps = restoreSwaps(run).getOrElse {
                        Logger.warn("Failed to restore Pubky Swaps routes", it, context = TAG)
                        return@repeat
                    }
                    if (swaps.restored.isNotEmpty() || swaps.known.isNotEmpty()) send(swaps)
                    if (swaps.incompleteProviders.isEmpty()) return@collect
                }
            }
        }
        val wiped = launch {
            wipes.first { it != wallet }
            restore.cancel()
        }
        restore.join()
        wiped.cancel()
    }

    /** Stops the wiped wallet's restore and background swaps, so a wallet restored next starts from nothing. */
    fun resetForWipe() {
        wipes.update { it + 1 }
        advancing.values.forEach { it.cancel() }
        advancing.clear()
    }

    /**
     * The listing does not carry the contact's address, so a restored swap claims to a fresh Savings address.
     * bitkit-core checks each listed lockup against the Lightning payment this node sent for its hash. Swaps already on
     * record keep the destination they were committed with.
     */
    private suspend fun restoreSwaps(
        run: RestoreRun
    ): Result<RestoredPubkySwaps> = withSecrets { mnemonic, passphrase ->
        val providers = candidates().filterNot { bare(it) in run.restoredProviders }
        if (providers.isEmpty()) return@withSecrets RestoredPubkySwaps(emptyList(), emptyList())
        // One address for the whole run, so restores asked again reveal no unused addresses.
        val payoutAddress = run.payoutAddress ?: lightningRepo.newAddress().getOrThrow().also { run.payoutAddress = it }
        val sentPayments = sentPayments()
        val restored = mutableListOf<String>()
        val known = mutableListOf<String>()
        val incomplete = mutableListOf<String>()
        val readInFull = mutableListOf<String>()
        // bitkit-core asks at most MAX_RESTORE_PROVIDERS providers at once, so a contact past them gets its own call.
        providers.chunked(MAX_RESTORE_PROVIDERS).forEach { chunk ->
            val report = pubkySwapsRestore(
                providers = chunk,
                sentPayments = sentPayments,
                electrumUrl = Env.electrumServerUrl,
                payoutAddress = payoutAddress,
                feeRateSatVb = CLAIM_FEE_RATE_SAT_VB,
                network = Env.network.toCoreNetwork(),
                mnemonic = mnemonic,
                bip39Passphrase = passphrase,
            )
            restored += report.restored
            known += report.known
            incomplete += report.incompleteProviders
            val incompleteBare = report.incompleteProviders.map(::bare).toSet()
            readInFull += chunk.map(::bare).filterNot { it in incompleteBare }
        }
        // Only a restore that returned its swaps marks their providers read, so a failed one asks them again.
        run.restoredProviders += readInFull
        if (incomplete.isNotEmpty()) Logger.warn("Swap lists incomplete from '$incomplete'", context = TAG)
        track(restored + known)
        RestoredPubkySwaps(
            restored = restored.filter(run.reportedSwaps::add),
            known = known.filter(run.reportedSwaps::add),
            incompleteProviders = incomplete,
        )
    }

    private fun bare(pubky: String): String {
        val key = pubky.trim().removePrefix("pubky://")
        return key.removePrefix("pubky").takeIf { it.length == PUBKY_KEY_LENGTH } ?: key
    }

    /** The Lightning payments this node sent, pending or settled, by hash and the amount paid to the payee. */
    private suspend fun sentPayments(): List<PubkySwapSentPayment> =
        lightningRepo.getPayments().getOrThrow().mapNotNull { payment ->
            val kind = payment.kind as? PaymentKind.Bolt11 ?: return@mapNotNull null
            val amountMsat = payment.amountMsat ?: return@mapNotNull null
            if (payment.direction != PaymentDirection.OUTBOUND || payment.status == PaymentStatus.FAILED) {
                return@mapNotNull null
            }
            PubkySwapSentPayment(
                paymentHashHex = kind.hash,
                amountSat = amountMsat / 1000u,
                settled = payment.status == PaymentStatus.SUCCEEDED,
            )
        }

    /** Providers come from the user's own graph: every contact is asked for its swap offer. */
    private fun candidates(): List<String> =
        (Env.pubkySwapsProviders + pubkyRepo.contacts.value.map { it.publicKey }).distinct()

    /** Commits the quote and keeps its swap until its claim confirms or it fails, so a later start resumes it. */
    suspend fun commit(quoteId: String): Result<PubkySwapRoutePayment> = withSecrets { mnemonic, passphrase ->
        pubkySwapsCommitRoute(quoteId, Env.electrumServerUrl, mnemonic, passphrase).also { track(listOf(it.swapId)) }
    }

    /**
     * Advances each swap a closed app left unfinished until its claim confirms or it fails: the provider settles the
     * hold invoice once it sees the claim, so a claim that never confirms could leave the contact unpaid.
     */
    fun resumeUnfinishedSwaps() {
        scope.launch {
            cacheStore.data.first().unfinishedPubkySwaps
                .filterNot { it in claiming }
                .forEach { advanceInBackground(it) }
        }
    }

    /**
     * Advance the swap until bitkit-core broadcasts the claim to the contact, returning the claim txid. The provider
     * settles the hold invoice once it sees the claim, so the payment is done; the claim is still advanced in the
     * background so bitkit-core rebroadcasts it until it confirms. [holdFailure] completing normally means the hold
     * payment failed: the provider never funds that swap, so advancing stops with [PubkySwapHoldFailedException].
     */
    // Runs on the app scope: the swap keeps advancing towards its claim when the caller, such as a cleared screen,
    // stops waiting.
    suspend fun awaitClaim(
        swapId: String,
        timeout: Duration = CLAIM_TIMEOUT,
        holdFailure: Job? = null,
    ): Result<String> = scope.async {
        claiming.add(swapId)
        try {
            awaitClaimOnce(swapId, timeout, holdFailure)
        } finally {
            claiming.remove(swapId)
        }
    }.await()

    private suspend fun awaitClaimOnce(swapId: String, timeout: Duration, holdFailure: Job?): Result<String> =
        coroutineScope {
            var lastFailure: Throwable? = null
            val poll = async {
                withTimeoutOrNull(timeout) {
                    pollClaim(swapId, POLL_INTERVAL, untilConfirmed = false) { lastFailure = it }
                }
            }
            // A cancelled watch, as on a cleared screen, says nothing about the hold payment, so the swap keeps
            // advancing.
            val holdFailed = CompletableDeferred<Unit>()
            val handle = holdFailure?.invokeOnCompletion { if (it == null) holdFailed.complete(Unit) }
            val isHoldFailed = select {
                poll.onJoin { false }
                holdFailed.onJoin { true }
            }
            handle?.dispose()
            if (isHoldFailed) {
                poll.cancel()
                untrack(swapId)
                return@coroutineScope Result.failure(PubkySwapHoldFailedException(swapId))
            }
            when (val claim = poll.await()) {
                null -> {
                    // The provider may still settle the hold payment, so the swap keeps advancing towards its claim.
                    advanceInBackground(swapId)
                    Result.failure(PubkySwapClaimTimeoutException(swapId, lastFailure))
                }
                else -> claim.onSuccess { advanceInBackground(swapId) }.onFailure { untrack(swapId) }
            }
        }

    private fun advanceInBackground(swapId: String) {
        if (advancing.containsKey(swapId)) return
        val loop = scope.launch(start = CoroutineStart.LAZY) {
            try {
                awaitConfirmation(swapId)
            } finally {
                advancing.remove(swapId, coroutineContext.job)
            }
        }
        if (advancing.putIfAbsent(swapId, loop) == null) loop.start() else loop.cancel()
    }

    /**
     * Stops a swap whose hold payment failed or never went out: the provider never funds it, so advancing it would
     * only poll until bitkit-core gives up.
     */
    suspend fun dropFailedSwap(swapId: String) {
        advancing.remove(swapId)?.cancel()
        untrack(swapId)
    }

    private suspend fun awaitConfirmation(swapId: String) {
        pollClaim(swapId, CONFIRMATION_POLL_INTERVAL, untilConfirmed = true) {}
            .onSuccess { Logger.info("Confirmed Pubky Swaps claim '$it' for '$swapId'", context = TAG) }
            .onFailure { Logger.warn("Failed to confirm Pubky Swaps claim for '$swapId'", it, context = TAG) }
        untrack(swapId)
    }

    // A failed write never fails the swap it records: the commit already happened.
    private suspend fun track(swapIds: List<String>) {
        if (swapIds.isEmpty()) return
        runSuspendCatching {
            cacheStore.update { it.copy(unfinishedPubkySwaps = (it.unfinishedPubkySwaps + swapIds).distinct()) }
        }.onFailure { Logger.warn("Failed to record unfinished Pubky Swaps routes '$swapIds'", it, context = TAG) }
    }

    private suspend fun untrack(swapId: String) {
        runSuspendCatching {
            cacheStore.update { it.copy(unfinishedPubkySwaps = it.unfinishedPubkySwaps - swapId) }
        }.onFailure { Logger.warn("Failed to drop finished Pubky Swaps route '$swapId'", it, context = TAG) }
    }

    private suspend fun pollClaim(
        swapId: String,
        interval: Duration,
        untilConfirmed: Boolean,
        onPending: (Throwable) -> Unit,
    ): Result<String> {
        while (true) {
            // A failed advance, such as a transient Electrum error, is retried: the swap is already funded. A swap
            // bitkit-core has no record of, as one carried over by a metadata backup, never advances, so it ends here.
            val advance = withSecrets { mnemonic, passphrase -> pubkySwapsAdvance(swapId, mnemonic, passphrase) }
            advance.exceptionOrNull()?.let { if (it is PubkySwapException.NotFound) return Result.failure(it) }
            val state = advance
                .onFailure {
                    Logger.warn("Failed to advance Pubky Swaps route '$swapId'", it, context = TAG)
                    onPending(it)
                }
                .getOrNull()?.state
            when (state) {
                is PubkySwapRouteState.Claimed -> return Result.success(state.txid)
                is PubkySwapRouteState.ClaimBroadcast if !untilConfirmed -> return Result.success(state.txid)
                is PubkySwapRouteState.Failed -> return Result.failure(AppError(state.reason))
                null -> Unit
                else -> {
                    Logger.debug("Pubky Swaps route '$swapId': $state", context = TAG)
                    onPending(AppError("Swap '$swapId' not claimed yet: '$state'"))
                }
            }
            delay(interval)
        }
    }

    private suspend fun <T> withSecrets(block: suspend (String, String?) -> T): Result<T> = withContext(ioDispatcher) {
        runSuspendCatching {
            val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)
                ?: throw ServiceError.MnemonicNotFound()
            val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)
            block(mnemonic, passphrase)
        }
    }
}

/** Length of a z-base-32 encoded pubky without its prefix. */
private const val PUBKY_KEY_LENGTH = 52

/**
 * The swaps a restore found: those rebuilt now claim to Savings, those already on record keep their destination. The
 * providers it read only in part are asked again.
 */
data class RestoredPubkySwaps(
    val restored: List<String>,
    val known: List<String>,
    val incompleteProviders: List<String> = emptyList(),
)

/** The swap's hold payment failed, so the provider never funds its lockup and the swap is not advanced further. */
class PubkySwapHoldFailedException(swapId: String) : AppError("Hold payment of swap '$swapId' failed")

/** A claim wait that ran out with the swap still open: its hold payment may yet settle. */
class PubkySwapClaimTimeoutException(swapId: String, cause: Throwable?) :
    AppError("Swap '$swapId' not claimed yet", cause)

private class RestoreRun {
    val restoredProviders = mutableSetOf<String>()
    val reportedSwaps = mutableSetOf<String>()
    var payoutAddress: String? = null
}

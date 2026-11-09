package to.bitkit.repositories

import com.synonym.bitkitcore.PaykitEndpoint
import com.synonym.bitkitcore.PaykitRoute
import com.synonym.bitkitcore.PaykitRouteRequest
import com.synonym.bitkitcore.PubkySwapRoutePayment
import com.synonym.bitkitcore.PubkySwapRouteState
import com.synonym.bitkitcore.PubkySwapSentPayment
import com.synonym.bitkitcore.ServiceRouteLeg
import com.synonym.bitkitcore.pubkySwapsAdvance
import com.synonym.bitkitcore.pubkySwapsCommitRoute
import com.synonym.bitkitcore.pubkySwapsRestore
import com.synonym.bitkitcore.pubkySwapsRoutePaykitPayment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import to.bitkit.async.appScope
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
) {
    companion object {
        private const val TAG = "PubkySwapsRouteRepo"
        private const val CLAIM_FEE_RATE_SAT_VB = 2uL
        private val POLL_INTERVAL = 2.seconds
        private val CLAIM_TIMEOUT = 10.minutes

        /** How often a broadcast claim is advanced, so bitkit-core rebroadcasts it until it confirms. */
        private val CONFIRMATION_POLL_INTERVAL = 30.seconds

        /** How long a broadcast claim is advanced in the background before polling stops. */
        private val CONFIRMATION_TIMEOUT = 60.minutes
    }

    private val scope = appScope(ioDispatcher, TAG)

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
     * Rebuilds the swaps this wallet started from each provider's listing, after a restore from the seed. The listing
     * does not carry the contact's address, so a restored swap claims to a fresh Savings address. bitkit-core checks
     * each listed lockup against the Lightning payment this node sent for its hash. Swaps already on record keep the
     * destination they were committed with.
     */
    suspend fun restoreSwaps(): Result<RestoredPubkySwaps> = withSecrets { mnemonic, passphrase ->
        val payoutAddress = lightningRepo.newAddress().getOrThrow()
        val report = pubkySwapsRestore(
            providers = candidates(),
            sentPayments = sentPayments(),
            electrumUrl = Env.electrumServerUrl,
            payoutAddress = payoutAddress,
            feeRateSatVb = CLAIM_FEE_RATE_SAT_VB,
            network = Env.network.toCoreNetwork(),
            mnemonic = mnemonic,
            bip39Passphrase = passphrase,
        )
        if (report.incompleteProviders.isNotEmpty()) {
            Logger.warn("Swap lists incomplete from '${report.incompleteProviders}'", context = TAG)
        }
        RestoredPubkySwaps(restored = report.restored, known = report.known)
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

    suspend fun commit(quoteId: String): Result<PubkySwapRoutePayment> = withSecrets { mnemonic, passphrase ->
        pubkySwapsCommitRoute(quoteId, Env.electrumServerUrl, mnemonic, passphrase)
    }

    /**
     * Advance the swap until bitkit-core broadcasts the claim to the contact, returning the claim txid. The provider
     * settles the hold invoice once it sees the claim, so the payment is done; the claim is still advanced in the
     * background so bitkit-core rebroadcasts it until it confirms.
     */
    suspend fun awaitClaim(swapId: String, timeout: Duration = CLAIM_TIMEOUT): Result<String> {
        var lastFailure: Throwable? = null
        return withTimeoutOrNull(timeout) {
            pollClaim(swapId, POLL_INTERVAL, untilConfirmed = false) { lastFailure = it }
        }?.onSuccess {
            scope.launch { withTimeoutOrNull(CONFIRMATION_TIMEOUT) { awaitConfirmation(swapId) } }
        } ?: Result.failure(lastFailure ?: AppError("Swap '$swapId' not claimed yet"))
    }

    private suspend fun awaitConfirmation(swapId: String) {
        pollClaim(swapId, CONFIRMATION_POLL_INTERVAL, untilConfirmed = true) {}
            .onSuccess { Logger.info("Confirmed Pubky Swaps claim '$it' for '$swapId'", context = TAG) }
            .onFailure { Logger.warn("Failed to confirm Pubky Swaps claim for '$swapId'", it, context = TAG) }
    }

    private suspend fun pollClaim(
        swapId: String,
        interval: Duration,
        untilConfirmed: Boolean,
        onPending: (Throwable) -> Unit,
    ): Result<String> {
        while (true) {
            // A failed advance, such as a transient Electrum error, is retried: the swap is already funded.
            val state = withSecrets { mnemonic, passphrase -> pubkySwapsAdvance(swapId, mnemonic, passphrase) }
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

/** The swaps a restore found: those rebuilt now claim to Savings, those already on record keep their destination. */
data class RestoredPubkySwaps(
    val restored: List<String>,
    val known: List<String>,
)

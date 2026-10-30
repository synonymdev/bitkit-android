package to.bitkit.repositories

import com.synonym.bitkitcore.PaykitEndpoint
import com.synonym.bitkitcore.PaykitRoute
import com.synonym.bitkitcore.PaykitRouteRequest
import com.synonym.bitkitcore.PubkySwapRoutePayment
import com.synonym.bitkitcore.PubkySwapRouteState
import com.synonym.bitkitcore.pubkySwapsAdvance
import com.synonym.bitkitcore.pubkySwapsCommitRoute
import com.synonym.bitkitcore.pubkySwapsRoutePaykitPayment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.nowMillis
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
) {
    companion object {
        private const val TAG = "PubkySwapsRouteRepo"
        private const val CLAIM_FEE_RATE_SAT_VB = 2uL
        private val POLL_INTERVAL = 2.seconds
        private val CLAIM_TIMEOUT = 10.minutes
    }

    val isEnabled: Boolean get() = Env.isPubkySwapsRouteEnabled

    /** The contact behind a provider's pubky, so the review shows who routes the payment. */
    fun providerContact(pubky: String): PubkyProfile? =
        pubkyRepo.contacts.value.firstOrNull { PubkyPublicKeyFormat.matches(it.publicKey, pubky) }

    suspend fun route(
        amountSats: ULong,
        endpoints: List<PaykitEndpoint>,
        lightningSpendableSats: ULong,
        onchainSpendableSats: ULong,
    ): Result<PaykitRoute> = withSecrets { mnemonic, passphrase ->
        // Providers come from the user's own graph: every contact is asked for its swap offer.
        val candidates = pubkyRepo.contacts.value.map { it.publicKey } + Env.pubkySwapsProviders
        Logger.debug("Routing Paykit payment via '${candidates.map(PubkyPublicKeyFormat::redacted)}'", context = TAG)
        pubkySwapsRoutePaykitPayment(
            request = PaykitRouteRequest(
                amountSat = amountSats,
                contactEndpoints = endpoints,
                lightningSpendableSat = lightningSpendableSats,
                onchainSpendableSat = onchainSpendableSats,
                providers = candidates,
                feeRateSatVb = CLAIM_FEE_RATE_SAT_VB,
            ),
            network = Env.network.toCoreNetwork(),
            mnemonic = mnemonic,
            bip39Passphrase = passphrase,
        )
    }

    suspend fun commit(quoteId: String): Result<PubkySwapRoutePayment> = withSecrets { mnemonic, passphrase ->
        pubkySwapsCommitRoute(quoteId, Env.electrumServerUrl, mnemonic, passphrase)
    }

    /** Advance the swap until bitkit-core claims the lockup to the contact, returning the claim txid. */
    suspend fun awaitClaim(swapId: String, timeout: Duration = CLAIM_TIMEOUT): Result<String> {
        val deadline = nowMillis() + timeout.inWholeMilliseconds
        while (true) {
            val status = withSecrets { mnemonic, passphrase -> pubkySwapsAdvance(swapId, mnemonic, passphrase) }
                .getOrElse { return Result.failure(it) }
            when (val state = status.state) {
                is PubkySwapRouteState.Claimed -> return Result.success(state.txid)
                is PubkySwapRouteState.Failed -> return Result.failure(AppError(state.reason))
                else -> Logger.debug("Pubky Swaps route '$swapId': $state", context = TAG)
            }
            if (nowMillis() > deadline) {
                return Result.failure(AppError("Swap '$swapId' not claimed yet: '${status.state}'"))
            }
            delay(POLL_INTERVAL)
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

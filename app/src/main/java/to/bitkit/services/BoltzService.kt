package to.bitkit.services

import android.content.Context
import com.synonym.bitkitcore.BoltzEventListener
import com.synonym.bitkitcore.BoltzNetwork
import com.synonym.bitkitcore.BoltzPairInfo
import com.synonym.bitkitcore.BoltzSwap
import com.synonym.bitkitcore.BoltzSwapEvent
import com.synonym.bitkitcore.PubkyDeliveryOperation
import com.synonym.bitkitcore.PubkyDeliveryStatus
import com.synonym.bitkitcore.PubkySendTerms
import com.synonym.bitkitcore.PubkySwapConfig
import com.synonym.bitkitcore.ReverseSwapResponse
import com.synonym.bitkitcore.SubmarineSwapResponse
import com.synonym.bitkitcore.boltzClaimReverseSwap
import com.synonym.bitkitcore.boltzConfigurePubky
import com.synonym.bitkitcore.boltzCreateReverseSwap
import com.synonym.bitkitcore.boltzCreateSubmarineSwap
import com.synonym.bitkitcore.boltzDisconnectPubky
import com.synonym.bitkitcore.boltzGetReverseLimits
import com.synonym.bitkitcore.boltzGetSubmarineLimits
import com.synonym.bitkitcore.boltzGetSwap
import com.synonym.bitkitcore.boltzHasPendingRecovery
import com.synonym.bitkitcore.boltzListPendingSwaps
import com.synonym.bitkitcore.boltzListSwaps
import com.synonym.bitkitcore.boltzMarkPaymentStarted
import com.synonym.bitkitcore.boltzPreparePubkySwitch
import com.synonym.bitkitcore.boltzPubkyDeliveryStatus
import com.synonym.bitkitcore.boltzRefundSubmarineSwap
import com.synonym.bitkitcore.boltzRetryPubkyDelivery
import com.synonym.bitkitcore.boltzStartSwapUpdates
import com.synonym.bitkitcore.boltzStopSwapUpdates
import com.synonym.bitkitcore.pubkyCreateSendSwap
import com.synonym.bitkitcore.pubkyGetSendTerms
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import org.lightningdevkit.ldknode.Network
import to.bitkit.data.SettingsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.env.Env
import to.bitkit.models.PubkySwapNetwork
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around the bitkit-core swaps FFI (submarine + reverse swaps between onchain Bitcoin and
 * Lightning). New swaps run only through a connected Pubky swap provider; swaps created earlier with Boltz
 * keep recovering through the updates stream. A Hilt singleton that bridges the [BoltzEventListener] callback
 * to a [SharedFlow]; swap keys are re-derived from the wallet mnemonic on demand, never persisted.
 */
@Suppress("TooManyFunctions")
@Singleton
class BoltzService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keychain: Keychain,
    private val settingsStore: SettingsStore,
) {
    private val _events = MutableSharedFlow<BoltzSwapEvent>(extraBufferCapacity = 64)

    /** Swap lifecycle events emitted while the updates stream is running. */
    val events: SharedFlow<BoltzSwapEvent> = _events.asSharedFlow()

    /** Configuration of the Pubky swap provider the bridge is connected to, or null while detached. */
    @Volatile
    var activePubkyConfig: PubkySwapConfig? = null
        private set

    private val listener = object : BoltzEventListener {
        override fun onEvent(event: BoltzSwapEvent) {
            Logger.info("Boltz event: $event", context = TAG)
            _events.tryEmit(event)
        }
    }

    // region Pubky provider

    /**
     * Connect the bridge to the provider in [config] as the identity of [secretKeyHex]. Configuring the active
     * configuration again is a no-op; a different one needs [preparePubkySwitch] or [disconnectPubky] first.
     */
    suspend fun configurePubky(config: PubkySwapConfig, secretKeyHex: String) {
        if (!PubkySwapInit.ensureInitialized(context)) throw PubkySwapUnavailableError()
        boltzConfigurePubky(config = config, secretKeyHex = secretKeyHex)
        activePubkyConfig = config
        Logger.info("Configured Pubky swap provider '${config.provider}' on '${config.network}'", context = TAG)
    }

    /** Detach the bridge, failing while a swap operation runs or Pubky swaps of this binding are pending. */
    suspend fun preparePubkySwitch() {
        boltzPreparePubkySwitch()
        activePubkyConfig = null
        Logger.info("Detached Pubky swap provider for a switch", context = TAG)
    }

    /** Detach the bridge unconditionally. Pending swaps recover once the same binding is configured again. */
    suspend fun disconnectPubky() {
        boltzDisconnectPubky()
        activePubkyConfig = null
        Logger.info("Disconnected Pubky swap provider", context = TAG)
    }

    /** Whether any swap, or an interrupted swap creation, still needs recovery. */
    suspend fun hasPendingRecovery(): Boolean = boltzHasPendingRecovery()

    /** Record that the hold invoice of [swapId] is about to be paid. Pay only when this returns true. */
    suspend fun markPaymentStarted(swapId: String): Boolean = boltzMarkPaymentStarted(swapId = swapId)

    suspend fun pubkyDeliveryStatus(): List<PubkyDeliveryStatus> = boltzPubkyDeliveryStatus()

    suspend fun retryPubkyDelivery(id: String, operation: PubkyDeliveryOperation) =
        boltzRetryPubkyDelivery(id = id, operation = operation)

    // endregion

    // region Limits

    suspend fun submarineLimits(): BoltzPairInfo = boltzGetSubmarineLimits(network = requireActiveConfig().network)

    suspend fun reverseLimits(): BoltzPairInfo = boltzGetReverseLimits(network = requireActiveConfig().network)

    /** Raw terms of the provider for paying an external address; the client fee policy is not applied. */
    suspend fun sendTerms(): PubkySendTerms = pubkyGetSendTerms(network = requireActiveConfig().network)

    // endregion

    // region Create

    /** Submarine swap: onchain BTC -> Lightning. Fund the returned lockup address. */
    suspend fun createSubmarineSwap(invoice: String): SubmarineSwapResponse {
        val config = requireActiveConfig()
        val (mnemonic, passphrase) = credentials()
        return boltzCreateSubmarineSwap(
            network = config.network,
            electrumUrl = config.electrumUrl,
            invoice = invoice,
            mnemonic = mnemonic,
            bip39Passphrase = passphrase,
        ).also { Logger.info("Created submarine swap '${it.id}'", context = TAG) }
    }

    /** Reverse swap: Lightning -> onchain BTC. Pay the returned hold invoice. */
    suspend fun createReverseSwap(amountSat: ULong, claimAddress: String): ReverseSwapResponse {
        val config = requireActiveConfig()
        val (mnemonic, passphrase) = credentials()
        return boltzCreateReverseSwap(
            network = config.network,
            electrumUrl = config.electrumUrl,
            amountSat = amountSat,
            claimAddress = claimAddress,
            mnemonic = mnemonic,
            bip39Passphrase = passphrase,
        ).also { Logger.info("Created reverse swap '${it.id}'", context = TAG) }
    }

    /**
     * Reverse swap that pays [recipientSat] to an external [claimAddress]. Pay the returned hold invoice of
     * [invoiceSat]; the claim spends everything above [recipientSat] as its fee.
     */
    suspend fun createSendSwap(
        invoiceSat: ULong,
        recipientSat: ULong,
        claimAddress: String,
        pairHash: String,
    ): ReverseSwapResponse {
        val config = requireActiveConfig()
        val (mnemonic, passphrase) = credentials()
        return pubkyCreateSendSwap(
            network = config.network,
            electrumUrl = config.electrumUrl,
            invoiceSat = invoiceSat,
            recipientSat = recipientSat,
            claimAddress = claimAddress,
            pairHash = pairHash,
            mnemonic = mnemonic,
            bip39Passphrase = passphrase,
        ).also { Logger.info("Created send swap '${it.id}'", context = TAG) }
    }

    // endregion

    // region Query

    suspend fun listSwaps(): List<BoltzSwap> = boltzListSwaps()

    suspend fun listPendingSwaps(): List<BoltzSwap> = boltzListPendingSwaps()

    suspend fun getSwap(id: String): BoltzSwap? = boltzGetSwap(swapId = id)

    // endregion

    // region Manual claim / refund

    suspend fun claimReverseSwap(id: String, feeRateSatPerVb: Double? = null): String {
        val (mnemonic, passphrase) = credentials()
        return boltzClaimReverseSwap(
            swapId = id,
            mnemonic = mnemonic,
            bip39Passphrase = passphrase,
            feeRateSatPerVb = feeRateSatPerVb,
        )
    }

    suspend fun refundSubmarineSwap(id: String, refundAddress: String, feeRateSatPerVb: Double? = null): String {
        val (mnemonic, passphrase) = credentials()
        return boltzRefundSubmarineSwap(
            swapId = id,
            refundAddress = refundAddress,
            mnemonic = mnemonic,
            bip39Passphrase = passphrase,
            feeRateSatPerVb = feeRateSatPerVb,
        )
    }

    // endregion

    // region Updates stream

    /**
     * Start tracking every pending swap and auto-claim reverse swaps. Pubky swaps are reconciled while their
     * provider is connected; swaps created earlier with Boltz use its updates WebSocket. [feeRateSatPerVb] is
     * the rate used for those auto-claims (Bitkit owns fee estimation). [acceptZeroConf] lets a Boltz reverse
     * swap claim as soon as its lockup hits the mempool. Replaces any running stream.
     */
    suspend fun startUpdates(
        feeRateSatPerVb: Double?,
        acceptZeroConf: Boolean,
        network: BoltzNetwork = boltzNetwork(),
    ) {
        val (mnemonic, passphrase) = credentials()
        boltzStartSwapUpdates(
            network = network,
            listener = listener,
            mnemonic = mnemonic,
            bip39Passphrase = passphrase,
            feeRateSatPerVb = feeRateSatPerVb,
            acceptZeroConf = acceptZeroConf,
        )
        Logger.info("Started swap updates stream on '$network'", context = TAG)
    }

    suspend fun stopUpdates() {
        boltzStopSwapUpdates()
        Logger.info("Stopped swap updates stream", context = TAG)
    }

    // endregion

    // region Helpers

    /** Whether swaps can run on the configured network. See [Env.isSwapSupported]. */
    val isSwapSupported: Boolean get() = Env.isSwapSupported

    /**
     * Whether new swaps may start: the network must support them, the swap flow must be switched on, and a
     * Pubky swap provider must be connected.
     */
    suspend fun isSwapEnabled(): Boolean = isSwapSupported &&
        settingsStore.data.first().isSavingsSwapEnabled &&
        settingsStore.pubkySwapProvider.first() != null

    /** The swap network matching the app's configured network. Only mainnet and regtest are supported. */
    fun boltzNetwork(network: Network = Env.network): BoltzNetwork =
        checkNotNull(PubkySwapNetwork.from(network).boltzNetwork) { "Swaps are not supported on '$network'" }

    private fun requireActiveConfig(): PubkySwapConfig = activePubkyConfig ?: throw PubkySwapNotConfiguredError()

    private suspend fun credentials(): Pair<String, String?> {
        val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)
            ?: throw ServiceError.MnemonicNotFound()
        val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)
            ?.takeIf { it.isNotEmpty() }
        return mnemonic to passphrase
    }

    // endregion

    companion object {
        private const val TAG = "BoltzService"
    }
}

class PubkySwapNotConfiguredError : AppError("No Pubky swap provider is connected")

class PubkySwapUnavailableError : AppError("Pubky swap networking could not be initialized")

package to.bitkit.services

import com.synonym.bitkitcore.BoltzEventListener
import com.synonym.bitkitcore.BoltzNetwork
import com.synonym.bitkitcore.BoltzPairInfo
import com.synonym.bitkitcore.BoltzSwap
import com.synonym.bitkitcore.BoltzSwapEvent
import com.synonym.bitkitcore.ReverseSwapResponse
import com.synonym.bitkitcore.SubmarineSwapResponse
import com.synonym.bitkitcore.boltzClaimReverseSwap
import com.synonym.bitkitcore.boltzCreateReverseSwap
import com.synonym.bitkitcore.boltzCreateSubmarineSwap
import com.synonym.bitkitcore.boltzGetReverseLimits
import com.synonym.bitkitcore.boltzGetSubmarineLimits
import com.synonym.bitkitcore.boltzGetSwap
import com.synonym.bitkitcore.boltzListPendingSwaps
import com.synonym.bitkitcore.boltzListSwaps
import com.synonym.bitkitcore.boltzRefundSubmarineSwap
import com.synonym.bitkitcore.boltzStartSwapUpdates
import com.synonym.bitkitcore.boltzStopSwapUpdates
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import org.lightningdevkit.ldknode.Network
import to.bitkit.data.SettingsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.env.Env
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around the bitkit-core Boltz swaps FFI (submarine + reverse swaps
 * between onchain Bitcoin and Lightning).
 *
 * Mirrors the existing service pattern (e.g. [TrezorService]): a Hilt singleton
 * that wraps the FFI and bridges the [BoltzEventListener] foreign callback to a
 * [SharedFlow]. bitkit-core persists only a derivation index, never key material;
 * swap keys are re-derived on demand from the wallet mnemonic.
 *
 * The Lightning side (issuing/paying invoices, fresh onchain addresses) is owned
 * by [LightningService]; this service only talks to Boltz + the chain.
 */
@Suppress("TooManyFunctions")
@Singleton
class BoltzService @Inject constructor(
    private val keychain: Keychain,
    private val settingsStore: SettingsStore,
) {
    private val _events = MutableSharedFlow<BoltzSwapEvent>(extraBufferCapacity = 64)

    /** Swap lifecycle events emitted while the updates stream is running. */
    val events: SharedFlow<BoltzSwapEvent> = _events.asSharedFlow()

    private val listener = object : BoltzEventListener {
        override fun onEvent(event: BoltzSwapEvent) {
            Logger.info("Boltz event: $event", context = TAG)
            _events.tryEmit(event)
        }
    }

    // region Limits

    suspend fun submarineLimits(network: BoltzNetwork = boltzNetwork()): BoltzPairInfo =
        boltzGetSubmarineLimits(network = network)

    suspend fun reverseLimits(network: BoltzNetwork = boltzNetwork()): BoltzPairInfo =
        boltzGetReverseLimits(network = network)

    // endregion

    // region Create

    /** Submarine swap: onchain BTC -> Lightning. Fund the returned lockup address. */
    suspend fun createSubmarineSwap(
        invoice: String,
        network: BoltzNetwork = boltzNetwork(),
        electrumUrl: String? = null,
    ): SubmarineSwapResponse {
        val (mnemonic, passphrase) = credentials()
        return boltzCreateSubmarineSwap(
            network = network,
            electrumUrl = electrumUrl ?: electrumUrl(),
            invoice = invoice,
            mnemonic = mnemonic,
            bip39Passphrase = passphrase,
        ).also { Logger.info("Created Boltz submarine swap ${it.id}", context = TAG) }
    }

    /** Reverse swap: Lightning -> onchain BTC. Pay the returned hold invoice. */
    suspend fun createReverseSwap(
        amountSat: ULong,
        claimAddress: String,
        network: BoltzNetwork = boltzNetwork(),
        electrumUrl: String? = null,
    ): ReverseSwapResponse {
        val (mnemonic, passphrase) = credentials()
        return boltzCreateReverseSwap(
            network = network,
            electrumUrl = electrumUrl ?: electrumUrl(),
            amountSat = amountSat,
            claimAddress = claimAddress,
            mnemonic = mnemonic,
            bip39Passphrase = passphrase,
        ).also { Logger.info("Created Boltz reverse swap ${it.id}", context = TAG) }
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
     * Open the Boltz updates WebSocket, subscribe all pending swaps and auto-claim
     * reverse swaps. [feeRateSatPerVb] is the rate used for those auto-claims
     * (Bitkit owns fee estimation). [acceptZeroConf] claims a reverse swap as soon
     * as its lockup hits the mempool instead of waiting for its confirmation.
     * Replaces any running stream.
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
        Logger.info("Started Boltz updates stream on $network", context = TAG)
    }

    suspend fun stopUpdates() {
        boltzStopSwapUpdates()
        Logger.info("Stopped Boltz updates stream", context = TAG)
    }

    // endregion

    // region Helpers

    /** Whether the configured network has a reachable Boltz backend. See [Env.isSwapSupported]. */
    val isSwapSupported: Boolean get() = Env.isSwapSupported

    /** The Boltz network matching the app's configured network. */
    fun boltzNetwork(network: Network = Env.network): BoltzNetwork = when (network) {
        Network.BITCOIN -> BoltzNetwork.MAINNET
        Network.TESTNET -> BoltzNetwork.TESTNET
        Network.REGTEST -> BoltzNetwork.REGTEST
        // Boltz does not operate on signet; fall back to testnet for development.
        else -> BoltzNetwork.TESTNET
    }

    /** Current Electrum URL, used by Boltz for claim/refund broadcasting. */
    suspend fun electrumUrl(): String = settingsStore.data.first().electrumServer

    private suspend fun credentials(): Pair<String, String?> {
        val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)
            ?: error("Mnemonic not found")
        val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)
            ?.takeIf { it.isNotEmpty() }
        return mnemonic to passphrase
    }

    // endregion

    companion object {
        private const val TAG = "BoltzService"
    }
}

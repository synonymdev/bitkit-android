package to.bitkit.repositories

import com.synonym.bitkitcore.BoltzException
import com.synonym.bitkitcore.BoltzPairInfo
import com.synonym.bitkitcore.PubkyDeliveryOperation
import com.synonym.bitkitcore.PubkyDeliveryStatus
import com.synonym.bitkitcore.PubkySwapConfig
import com.synonym.bitkitcore.ReverseSwapResponse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.lightningdevkit.ldknode.PaymentId
import to.bitkit.async.appScope
import to.bitkit.data.SettingsStore
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.models.PubkySwapProvider
import to.bitkit.services.BoltzService
import to.bitkit.services.PubkySwapNotConfiguredError
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps the bitkit-core swap bridge connected to the Pubky swap provider the user scanned, as the signed-in Pubky
 * identity. It reconnects when the identity, the provider or the Electrum server changes, and never detaches a
 * provider while its swaps are pending, so their recovery continues. Payments of swap hold invoices go through
 * [payHoldInvoice], which records the payment before it is sent.
 */
@Suppress("TooManyFunctions")
@Singleton
class PubkySwapRepo @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val boltzService: BoltzService,
    private val pubkyRepo: PubkyRepo,
    private val settingsStore: SettingsStore,
    private val lightningRepo: LightningRepo,
) {
    companion object {
        private const val TAG = "PubkySwapRepo"

        /** Fee and amount limits the bridge applies to every swap offer. */
        val POLICY = PubkySwapPolicy(maxFeeBps = 2_000u, maxAmountSat = 1_000_000uL)

        /** Upper bound for connecting to a provider and reading its terms. */
        private val CONNECT_TIMEOUT = 60.seconds

        /** Base backoff between reconnection attempts; scales linearly per attempt. */
        private val RECONNECT_RETRY_DELAY = 5.seconds

        /** Upper bound for the backoff between reconnection attempts. */
        private val RECONNECT_RETRY_CAP = 60.seconds

        /** Reconnection attempts per change before waiting for the next change. */
        private const val RECONNECT_MAX_ATTEMPTS = 10
    }

    private val scope = appScope(ioDispatcher, TAG)
    private val mutex = Mutex()
    private var reconnectJob: Job? = null

    @Volatile
    private var pendingConnection: PubkySwapProvider? = null

    private val _state = MutableStateFlow<PubkySwapState>(PubkySwapState.NoProvider)

    /** Connection state of the saved provider. */
    val state: StateFlow<PubkySwapState> = _state.asStateFlow()

    /** Starts following the identity, provider and Electrum server. Idempotent. */
    @Synchronized
    fun start() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            combine(
                pubkyRepo.publicKey,
                settingsStore.pubkySwapProvider,
                settingsStore.data.map { it.electrumServer },
                ::Binding,
            ).distinctUntilChanged().collectLatest(::reconnectWithRetry)
        }
    }

    /** Whether a provider is connected, or can be, for new swaps right now. */
    suspend fun isAvailable(): Boolean = boltzService.isSwapEnabled() && pubkyRepo.publicKey.value != null

    /** Connects the bridge to the saved provider if it is not connected yet. */
    suspend fun ensureConfigured(): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            if (boltzService.activePubkyConfig != null) return@runSuspendCatching
            start()
            mutex.withLock { reconnectLocked(currentBinding()) }
            if (boltzService.activePubkyConfig == null) throw PubkySwapNotConfiguredError()
        }
    }

    /**
     * Makes [provider] the swap provider: connects to it, checks it answers with its terms, then saves it and
     * switches swaps on. A provider whose swaps are still pending is kept, and anything that fails leaves the
     * previous provider connected.
     */
    suspend fun connect(provider: PubkySwapProvider): Result<Unit> = withContext(ioDispatcher) {
        start()
        runSuspendCatching {
            if (!Env.isSwapSupported || !provider.network.matches(Env.network)) {
                throw PubkySwapError.UnsupportedNetwork
            }
            mutex.withLock {
                val identity = pubkyRepo.publicKey.value ?: throw PubkySecretAccessError.NotSignedIn
                val electrumServer = settingsStore.data.first().electrumServer
                val config = configOf(identity, provider, electrumServer) ?: throw PubkySwapError.UnsupportedNetwork
                runSuspendCatching {
                    configureLocked(config, provider)
                    withTimeoutOrNull(CONNECT_TIMEOUT) { boltzService.reverseLimits() }
                        ?: throw PubkySwapError.Timeout
                    settingsStore.setPubkySwapProvider(provider)
                    settingsStore.update { it.copy(isSavingsSwapEnabled = true) }
                    _state.update { PubkySwapState.Connected(provider, identity) }
                }.onFailure {
                    if (boltzService.activePubkyConfig == config) boltzService.disconnectPubky()
                    runSuspendCatching { reconnectLocked(currentBinding()) }
                }.getOrThrow()
            }
            Logger.info("Connected swap provider '${provider.publicKey}'", context = TAG)
        }.recoverCatching { throw it.toPubkySwapError() }
    }

    /** Forgets the saved provider. Refused while its swaps are pending. */
    suspend fun disconnectProvider(): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            mutex.withLock {
                if (boltzService.activePubkyConfig != null) boltzService.preparePubkySwitch()
                settingsStore.setPubkySwapProvider(null)
                _state.update { PubkySwapState.NoProvider }
            }
        }.recoverCatching { throw it.toPubkySwapError() }
    }

    /** Stops following the provider and detaches the bridge before the wallet is wiped. Swap data is kept. */
    suspend fun disconnectForWipe(): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            synchronized(this@PubkySwapRepo) { reconnectJob?.cancel() }
            pendingConnection = null
            mutex.withLock {
                boltzService.disconnectPubky()
                _state.update { PubkySwapState.NoProvider }
            }
        }
    }

    /** Whether the swap updates stream should run: swaps are enabled, or swaps from before still need recovery. */
    suspend fun shouldRunUpdates(): Boolean = boltzService.isSwapEnabled() ||
        runSuspendCatching { boltzService.hasPendingRecovery() }.getOrDefault(true)

    suspend fun reverseLimits(): Result<BoltzPairInfo> = withContext(ioDispatcher) {
        runSuspendCatching {
            ensureConfigured().getOrThrow()
            boltzService.reverseLimits()
        }
    }

    suspend fun createReverseSwap(amountSat: ULong, claimAddress: String): Result<ReverseSwapResponse> =
        withContext(ioDispatcher) {
            runSuspendCatching {
                ensureConfigured().getOrThrow()
                boltzService.createReverseSwap(amountSat = amountSat, claimAddress = claimAddress)
            }
        }

    /**
     * Pays the hold invoice of [swapId] once. The payment is recorded in bitkit-core right before it is sent, so
     * a swap whose payment may already have started is never paid again; that case returns
     * [HoldInvoicePayment.AlreadyStarted] and the swap resolves through the updates stream.
     */
    suspend fun payHoldInvoice(swapId: String, bolt11: String): Result<HoldInvoicePayment> =
        withContext(ioDispatcher) {
            lightningRepo.payInvoice(bolt11, onBeforeSend = { boltzService.markPaymentStarted(swapId) })
                .map<HoldInvoicePayment, PaymentId> { HoldInvoicePayment.Dispatched(it) }
                .recoverCatching { if (it is PaymentAbortedBeforeSend) HoldInvoicePayment.AlreadyStarted else throw it }
        }

    suspend fun deliveryStatus(): Result<List<PubkyDeliveryStatus>> = withContext(ioDispatcher) {
        runSuspendCatching { boltzService.pubkyDeliveryStatus() }
    }

    suspend fun retryDelivery(id: String, operation: PubkyDeliveryOperation): Result<Unit> =
        withContext(ioDispatcher) {
            runSuspendCatching { boltzService.retryPubkyDelivery(id, operation) }
        }

    /** Holds a scanned provider while the user sets up the Pubky identity it needs. */
    fun setPendingConnection(provider: PubkySwapProvider) = run { pendingConnection = provider }

    fun takePendingConnection(): PubkySwapProvider? = pendingConnection.also { pendingConnection = null }

    private suspend fun reconnectWithRetry(binding: Binding) {
        var attempt = 0
        while (attempt < RECONNECT_MAX_ATTEMPTS) {
            val result = runSuspendCatching { mutex.withLock { reconnectLocked(binding) } }
            if (result.isSuccess) return
            val error = result.exceptionOrNull()?.toPubkySwapError()
            Logger.warn("Failed to connect swap provider, attempt '${attempt + 1}'", error, context = TAG)
            if (error is PubkySwapError.PendingSwaps || error is PubkySecretAccessError) return
            attempt++
            delay((RECONNECT_RETRY_DELAY * attempt).coerceAtMost(RECONNECT_RETRY_CAP))
        }
    }

    private suspend fun reconnectLocked(binding: Binding) {
        val provider = binding.provider ?: return detachLocked(PubkySwapState.NoProvider)
        val identity = binding.identity ?: return detachLocked(PubkySwapState.IdentityMissing(provider))
        val config = configOf(identity, provider, binding.electrumServer)
            ?: return detachLocked(PubkySwapState.Failed(provider, PubkySwapError.UnsupportedNetwork.message))
        if (boltzService.activePubkyConfig == config) {
            _state.update { PubkySwapState.Connected(provider, identity) }
            return
        }
        runSuspendCatching { configureLocked(config, provider) }
            .onSuccess { _state.update { PubkySwapState.Connected(provider, identity) } }
            .onFailure {
                val error = it.toPubkySwapError()
                _state.update {
                    when (error) {
                        is PubkySwapError.PendingSwaps -> PubkySwapState.SwitchBlocked(provider)
                        else -> PubkySwapState.Failed(provider, error.message)
                    }
                }
            }
            .getOrThrow()
    }

    private suspend fun configureLocked(config: PubkySwapConfig, provider: PubkySwapProvider) {
        val active = boltzService.activePubkyConfig
        if (active == config) return
        when {
            active == null -> Unit
            active.dataDir == config.dataDir -> boltzService.disconnectPubky()
            else -> boltzService.preparePubkySwitch()
        }
        _state.update { PubkySwapState.Connecting(provider) }
        pubkyRepo.withActiveSecretKey { _, secretKeyHex -> boltzService.configurePubky(config, secretKeyHex) }
            .getOrThrow()
    }

    private suspend fun detachLocked(state: PubkySwapState) {
        if (boltzService.activePubkyConfig != null) {
            val detached = runSuspendCatching { boltzService.preparePubkySwitch() }
            if (detached.isFailure) {
                // Swaps of the connected provider are pending, so it stays connected to finish them.
                Logger.warn("Kept swap provider connected for pending swaps", detached.exceptionOrNull(), context = TAG)
                _state.update { PubkySwapState.SwitchBlocked(null) }
                return
            }
        }
        _state.update { state }
    }

    private suspend fun currentBinding() = Binding(
        identity = pubkyRepo.publicKey.value,
        provider = settingsStore.pubkySwapProvider.first(),
        electrumServer = settingsStore.data.first().electrumServer,
    )

    private fun configOf(identity: String, provider: PubkySwapProvider, electrumServer: String): PubkySwapConfig? {
        val network = provider.network.boltzNetwork?.takeIf { provider.network.matches(Env.network) } ?: return null
        val identityKey = PubkyPublicKeyFormat.bareKey(identity) ?: return null
        return PubkySwapConfig(
            network = network,
            provider = provider.publicKey,
            electrumUrl = electrumServer.trim(),
            dataDir = Env.pubkySwapStoragePath(identityKey, network.name.lowercase(), provider.publicKey),
            maxFeeBps = POLICY.maxFeeBps,
            maxAmountSat = POLICY.maxAmountSat,
        )
    }

    private data class Binding(
        val identity: String?,
        val provider: PubkySwapProvider?,
        val electrumServer: String,
    )
}

/** Fee and amount limits applied to every Pubky swap. */
data class PubkySwapPolicy(
    val maxFeeBps: UShort,
    val maxAmountSat: ULong,
)

sealed interface PubkySwapState {
    data object NoProvider : PubkySwapState
    data class IdentityMissing(val provider: PubkySwapProvider) : PubkySwapState
    data class Connecting(val provider: PubkySwapProvider) : PubkySwapState
    data class Connected(val provider: PubkySwapProvider, val identity: String) : PubkySwapState

    /** A previous provider stays connected, or a switch is refused, until its pending swaps finish. */
    data class SwitchBlocked(val provider: PubkySwapProvider?) : PubkySwapState
    data class Failed(val provider: PubkySwapProvider, val message: String?) : PubkySwapState
}

sealed interface HoldInvoicePayment {
    data class Dispatched(val paymentHash: PaymentId) : HoldInvoicePayment

    /** The payment may already have been sent before, so it was not sent again. */
    data object AlreadyStarted : HoldInvoicePayment
}

sealed class PubkySwapError(message: String) : AppError(message) {
    data object PendingSwaps : PubkySwapError("Pending Pubky swaps must finish before changing provider or identity")
    data object Busy : PubkySwapError("A swap operation is active")
    data object UnsupportedNetwork : PubkySwapError("Pubky swaps are not supported on this network")
    data object Timeout : PubkySwapError("The swap provider did not respond in time")
}

/** Maps bitkit-core configuration refusals, which only carry text, to [PubkySwapError]. */
fun Throwable.toPubkySwapError(): Throwable = when (this) {
    is BoltzException.InvalidInput if errorDetails.contains("pending Pubky swaps") -> PubkySwapError.PendingSwaps
    is BoltzException.InvalidInput if errorDetails.contains("swap operation is active") -> PubkySwapError.Busy
    else -> this
}

package to.bitkit.services

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.bitkit.async.BaseCoroutineScope
import to.bitkit.async.ServiceQueue
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.runSuspendCatching
import to.bitkit.utils.BarkError
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import uniffi.bark.ArkInfo
import uniffi.bark.Balance
import uniffi.bark.Config
import uniffi.bark.FeeEstimate
import uniffi.bark.LightningInvoice
import uniffi.bark.LightningReceive
import uniffi.bark.LightningSendStatus
import uniffi.bark.Movement
import uniffi.bark.Network
import uniffi.bark.OnchainBalance
import uniffi.bark.OnchainWallet
import uniffi.bark.PendingBoard
import uniffi.bark.Vtxo
import uniffi.bark.Wallet
import uniffi.bark.WalletNotification
import uniffi.bark.WalletOpenArgs
import uniffi.bark.generateMnemonic
import uniffi.bark.validateArkAddress
import javax.inject.Inject
import javax.inject.Singleton
import org.lightningdevkit.ldknode.Network as LdkNetwork

/**
 * Thin wrapper over bark's UniFFI [Wallet], shaped after [LightningService] so both backends read
 * the same way. Every call into rust runs on the dedicated [ServiceQueue.ARK] thread.
 *
 * bark keeps its own BDK on-chain wallet, separate from the ldk-node wallet that backs savings.
 * That wallet is only used to fund boards, so it is exposed through [onchainAddress] and
 * [onchainBalance] rather than being surfaced as a user-facing balance.
 */
@Suppress("TooManyFunctions")
@Singleton
class BarkService @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val keychain: Keychain,
) : BaseCoroutineScope(bgDispatcher, TAG) {

    companion object {
        private const val TAG = "BarkService"
    }

    @Volatile
    var wallet: Wallet? = null
        private set

    @Volatile
    private var onchain: OnchainWallet? = null

    val isRunning: Boolean get() = wallet != null

    // region lifecycle

    suspend fun start(walletIndex: Int) = withContext(bgDispatcher) {
        if (wallet != null) {
            Logger.debug("Ark wallet already started", context = TAG)
            return@withContext
        }
        val serverUrl = Env.arkServerUrl ?: throw BarkError.NotSupported(Env.network.name)
        val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)
            ?: throw ServiceError.MnemonicNotFound()
        if (!keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name).isNullOrBlank()) {
            throw BarkError.PassphraseUnsupported()
        }

        val datadir = Env.arkStoragePath(walletIndex)
        val config = barkConfig(serverUrl)
        val network = Env.network.toBarkNetwork()

        Logger.info("Starting Ark wallet on '${network.name}' via '$serverUrl'", context = TAG)
        ServiceQueue.ARK.background {
            val onchainWallet = OnchainWallet.default(network, mnemonic, config, datadir)
            onchain = onchainWallet
            wallet = Wallet.open(
                network = network,
                mnemonicOrSeed = mnemonic,
                config = config,
                args = WalletOpenArgs(datadir = datadir, onchain = onchainWallet),
            )
        }
        Logger.info("Started Ark wallet", context = TAG)
    }

    suspend fun stop() = withContext(bgDispatcher) {
        val current = wallet ?: run {
            Logger.debug("Ark wallet already stopped", context = TAG)
            return@withContext
        }
        Logger.debug("Stopping Ark wallet…", context = TAG)
        ServiceQueue.ARK.background {
            runSuspendCatching { current.stopDaemon() }
                .onFailure { Logger.warn("Failed to stop Ark daemon", it, context = TAG) }
        }
        wallet = null
        onchain = null
        Logger.info("Stopped Ark wallet", context = TAG)
    }

    // endregion

    // region sync

    suspend fun sync() = call { it.sync() }

    suspend fun maintenance() = call { it.maintenance() }

    /**
     * The mobile refresh path: co-signers refresh on the wallet's behalf, so the app does not have
     * to be online during a round window.
     */
    suspend fun maintenanceDelegated() = call { it.maintenanceDelegated() }

    suspend fun syncPendingBoards() = call { it.syncPendingBoards() }

    suspend fun syncExits() = call { it.syncExits() }

    // endregion

    // region balance and vtxos

    suspend fun balance(): Balance = call { it.balance() }

    suspend fun spendableVtxos(): List<Vtxo> = call { it.spendableVtxos() }

    suspend fun vtxosToRefresh(): List<Vtxo> = call { it.getVtxosToRefresh() }

    suspend fun nextRequiredRefreshBlockheight(): UInt? = call { it.getNextRequiredRefreshBlockheight() }

    suspend fun firstExpiringVtxoBlockheight(): UInt? = call { it.getFirstExpiringVtxoBlockheight() }

    suspend fun hasPendingExits(): Boolean = call { it.hasPendingExits() }

    // endregion

    // region receive

    suspend fun newArkAddress(): String = call { it.newAddress() }

    suspend fun bolt11Invoice(amountSats: ULong, description: String): LightningInvoice =
        call { it.bolt11Invoice(amountSats, description, null) }

    suspend fun pendingLightningReceives(): List<LightningReceive> = call { it.pendingLightningReceives() }

    suspend fun tryClaimAllLightningReceives(wait: Boolean = false): List<LightningReceive> =
        call { it.tryClaimAllLightningReceives(wait) }

    // endregion

    // region send

    suspend fun payLightningInvoice(invoice: String, amountSats: ULong?, wait: Boolean = true): LightningSendStatus =
        call { it.payLightningInvoice(invoice, amountSats, wait) }

    suspend fun sendArkoorPayment(arkAddress: String, amountSats: ULong) =
        call { it.sendArkoorPayment(arkAddress, amountSats) }

    suspend fun sendOnchain(address: String, amountSats: ULong): String =
        call { it.sendOnchain(address, amountSats) }

    // endregion

    // region board and offboard

    suspend fun boardAmount(amountSats: ULong): PendingBoard = call { it.boardAmount(amountSats) }

    suspend fun pendingBoards(): List<PendingBoard> = call { it.pendingBoards() }

    suspend fun offboardAll(bitcoinAddress: String): String = call { it.offboardAll(bitcoinAddress).roundId }

    suspend fun estimateBoardFee(amountSats: ULong): FeeEstimate = call { it.estimateBoardFee(amountSats) }

    suspend fun estimateOffboardAllFee(bitcoinAddress: String): FeeEstimate =
        call { it.estimateOffboardAllFee(bitcoinAddress) }

    // endregion

    // region bark's own onchain wallet (board funding only)

    suspend fun onchainAddress(): String = onchainCall { it.newAddress() }

    suspend fun onchainBalance(): OnchainBalance = onchainCall { it.balance() }

    suspend fun syncOnchain() = onchainCall { it.sync() }

    // endregion

    // region info

    suspend fun arkInfo(): ArkInfo? = call { it.arkInfo() }

    // endregion

    // region history

    suspend fun history(): List<Movement> = call { it.history() }

    // endregion

    // region notifications

    /**
     * bark exposes notifications as a single-consumer pull loop rather than a callback, so the
     * holder is drained on the Ark queue and republished as a flow.
     */
    fun notifications(): Flow<WalletNotification> = callbackFlow {
        val holder = (wallet ?: throw BarkError.NotStarted()).notifications()
        val job = launch {
            while (isActive) {
                val next = runSuspendCatching { ServiceQueue.ARK.background { holder.nextNotification() } }
                    .onFailure { Logger.warn("Failed to read Ark notification", it, context = TAG) }
                    .getOrNull() ?: continue
                send(next)
            }
        }
        awaitClose {
            holder.cancelNextNotificationWait()
            job.cancel()
        }
    }

    // endregion

    private suspend fun <T> call(block: suspend (Wallet) -> T): T {
        val current = wallet ?: throw BarkError.NotStarted()
        return ServiceQueue.ARK.background { block(current) }
    }

    private suspend fun <T> onchainCall(block: suspend (OnchainWallet) -> T): T {
        val current = onchain ?: throw BarkError.NotStarted()
        return ServiceQueue.ARK.background { block(current) }
    }
}

/**
 * bark's generated [Config] has no Kotlin defaults, so every optional knob has to be passed. All
 * nulls fall back to `bark::Config::network_default`, which is what we want for the POC.
 */
private fun barkConfig(serverUrl: String) = Config(
    serverAddress = serverUrl,
    serverAccessToken = null,
    esploraAddress = Env.arkEsploraUrl,
    bitcoindAddress = null,
    bitcoindCookiefile = null,
    bitcoindUser = null,
    bitcoindPass = null,
    vtxoRefreshExpiryThreshold = null,
    vtxoExitMargin = null,
    htlcRecvClaimDelta = null,
    fallbackFeeRate = null,
    roundTxRequiredConfirmations = null,
    daemonSyncIntervalSecs = null,
    offboardRequiredConfirmations = null,
    daemonManualSync = null,
    lightningReceiveClaimRetries = null,
    userAgent = "bitkit-android/${Env.version}",
)

fun newBarkMnemonic(): String = generateMnemonic()

fun isValidArkAddress(address: String): Boolean = runCatching { validateArkAddress(address) }.getOrDefault(false)

fun LdkNetwork.toBarkNetwork(): Network = when (this) {
    LdkNetwork.BITCOIN -> Network.BITCOIN
    LdkNetwork.TESTNET -> Network.TESTNET
    LdkNetwork.SIGNET -> Network.SIGNET
    LdkNetwork.REGTEST -> Network.REGTEST
}

package to.bitkit.services

import com.synonym.bitkitcore.ArkadeEventListener
import com.synonym.bitkitcore.ArkadeNetwork
import com.synonym.bitkitcore.ArkadeStartParams
import com.synonym.bitkitcore.arkadeBalance
import com.synonym.bitkitcore.arkadeExportState
import com.synonym.bitkitcore.arkadeHistory
import com.synonym.bitkitcore.arkadeInfo
import com.synonym.bitkitcore.arkadeLightningAddress
import com.synonym.bitkitcore.arkadeOffboard
import com.synonym.bitkitcore.arkadePay
import com.synonym.bitkitcore.arkadeQuotePay
import com.synonym.bitkitcore.arkadeReceive
import com.synonym.bitkitcore.arkadeRenew
import com.synonym.bitkitcore.arkadeSetListener
import com.synonym.bitkitcore.arkadeSettle
import com.synonym.bitkitcore.arkadeStart
import com.synonym.bitkitcore.arkadeStop
import com.synonym.bitkitcore.arkadeSwaps
import com.synonym.bitkitcore.arkadeVtxos
import com.synonym.bitkitcore.arkadeWipe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.lightningdevkit.ldknode.Network
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import javax.inject.Inject
import javax.inject.Singleton
import com.synonym.bitkitcore.ArkadeBalance as CoreArkadeBalance
import com.synonym.bitkitcore.ArkadeHistoryEntry as CoreArkadeHistoryEntry
import com.synonym.bitkitcore.ArkadeInfo as CoreArkadeInfo
import com.synonym.bitkitcore.ArkadeSwap as CoreArkadeSwap
import com.synonym.bitkitcore.ArkadeVtxo as CoreArkadeVtxo

/**
 * Arkade's wallet and Lightning client for Bitkit, run by bitkit-core's Rust Arkade module (ark-rs plus the Arkade
 * Intents Lightning client). The wallet derives its keys from the Bitkit seed on BIP86 account 1 (`m/86'/0'/1'`),
 * since account 0 holds Savings' Taproot keys.
 */
@Singleton
class ArkadeService @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val keychain: Keychain,
) {
    companion object {
        private const val TAG = "ArkadeService"

        /** The network name core reports in [ArkadeInfo.network] and backups record. */
        private const val NETWORK_NAME = "bitcoin"
    }

    private val mutex = Mutex()
    private var info: ArkadeInfo? = null
    private var dataDir: String? = null
    private var isListening = false

    private val _events = MutableSharedFlow<ArkadeEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ArkadeEvent> = _events.asSharedFlow()

    private val listener = object : ArkadeEventListener {
        override fun onHistory(entries: List<CoreArkadeHistoryEntry>, balance: CoreArkadeBalance) {
            _events.tryEmit(ArkadeEvent.History(entries.map { it.toApp() }))
        }

        override fun onSwap(swap: CoreArkadeSwap) {
            _events.tryEmit(ArkadeEvent.Swap(swap.toApp()))
        }
    }

    /**
     * Arkade runs on mainnet against Ark Labs' operator; bitkit-core has no Lightning swap client or LNURL server on
     * mutinynet, so other networks keep their channel paths.
     */
    val isSupported: Boolean get() = Env.network == Network.BITCOIN

    val isConnected: Boolean get() = info != null

    val networkName: String? get() = if (isSupported) NETWORK_NAME else null

    /**
     * Starts the wallet in [walletIndex]'s storage: [restore] scans the operator for the wallet's past outputs and
     * swaps, which a wallet restored from its phrase needs; [backup] is a state export to import before the wallet
     * opens its storage.
     */
    suspend fun connect(walletIndex: Int, restore: Boolean, backup: String?): ArkadeInfo = mutex.withLock {
        info?.let { return@withLock it }
        if (!isSupported) throw ArkadeError.Unsupported()
        val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name) ?: throw ServiceError.MnemonicNotFound()
        val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)
        if (!isListening) {
            arkadeSetListener(listener)
            isListening = true
        }
        val path = Env.arkadeStoragePath(walletIndex)
        val params = ArkadeStartParams(
            dataDir = path,
            mnemonic = mnemonic,
            passphrase = passphrase,
            network = ArkadeNetwork.BITCOIN,
            restore = restore,
            backup = backup,
        )
        val started = arkadeStart(params).toApp()
        dataDir = path
        info = started
        Logger.info("Connected Arkade wallet on '$NETWORK_NAME'", context = TAG)
        started
    }

    suspend fun disconnect() = mutex.withLock {
        arkadeStop()
        info = null
        dataDir = null
        Logger.info("Disconnected Arkade wallet", context = TAG)
    }

    /** Stops the wallet and deletes its storage for [walletIndex]. */
    suspend fun wipeStorage(walletIndex: Int) = mutex.withLock {
        if (!isSupported) return@withLock
        arkadeWipe(Env.arkadeStoragePath(walletIndex))
        info = null
        dataDir = null
    }

    suspend fun info(): ArkadeInfo = arkadeInfo().toApp()

    suspend fun balance(): ArkadeBalance = arkadeBalance().toApp()

    suspend fun vtxos(): List<ArkadeVtxo> = arkadeVtxos().map { it.toApp() }

    suspend fun history(): List<ArkadeHistoryEntry> = arkadeHistory().map { it.toApp() }

    suspend fun swaps(): List<ArkadeSwap> = arkadeSwaps().map { it.toApp() }

    /** An invoice for exactly [amountSats]: the payer pays it, and the wallet gets it less the solver's fee. */
    suspend fun receive(amountSats: ULong): ArkadeSwap = arkadeReceive(amountSats).toApp()

    suspend fun quotePay(bolt11: String): ArkadePayQuote = arkadeQuotePay(bolt11).let {
        ArkadePayQuote(feeSat = it.feeSat.toLong(), giveSat = it.giveSat.toLong(), takeSat = it.takeSat.toLong())
    }

    /** Funds the lockup for [bolt11] and returns at once; the outcome arrives as a swap event. */
    suspend fun pay(bolt11: String, maxFeeSats: ULong?): ArkadeSwap = arkadePay(bolt11, maxFeeSats).toApp()

    /** Boards confirmed on-chain deposits now, instead of on the wallet's next one-minute pass. */
    suspend fun settle(): String? = arkadeSettle()

    /** Renews outputs nearing their batch expiry; the wallet also does it by itself 3 days before expiry. */
    suspend fun renew(): String? = arkadeRenew()

    /** Moves [amountSats], or everything when null, to the on-chain [address] in the next batch. */
    suspend fun offboard(address: String, amountSats: ULong? = null): String = arkadeOffboard(address, amountSats)

    /** The wallet's Lightning address on Ark Labs' LNURL server, claimed on first use and found again from the seed. */
    suspend fun lightningAddress(): ArkadeLightningAddress? = arkadeLightningAddress().let {
        ArkadeLightningAddress(handle = it.handle, lightningAddress = it.lightningAddress, lnurl = it.lnurl)
    }

    /** Everything the wallet keeps locally, as one JSON document, for the VSS backup. */
    suspend fun exportState(): String = withContext(ioDispatcher) {
        arkadeExportState(dataDir ?: throw ArkadeError.NotConnected())
    }
}

private fun CoreArkadeInfo.toApp() = ArkadeInfo(
    arkAddress = arkAddress,
    boardingAddress = boardingAddress,
    network = network,
    dust = dust.toLong(),
    unilateralExitDelay = unilateralExitDelay.toLong(),
    boardingExitDelay = boardingExitDelay.toLong(),
    delegate = delegate,
)

private fun CoreArkadeBalance.toApp() = ArkadeBalance(
    available = available.toLong(),
    settled = settled.toLong(),
    preconfirmed = preconfirmed.toLong(),
    recoverable = recoverable.toLong(),
    boardingConfirmed = boardingConfirmed.toLong(),
    boardingUnconfirmed = boardingUnconfirmed.toLong(),
    total = total.toLong(),
)

private fun CoreArkadeVtxo.toApp() = ArkadeVtxo(
    txid = txid,
    vout = vout.toInt(),
    value = value.toLong(),
    state = state,
    batchExpiry = batchExpiry?.toLong(),
    createdAt = createdAt?.toLong(),
    isPreconfirmed = isPreconfirmed,
    isSwept = isSwept,
)

private fun CoreArkadeHistoryEntry.toApp() = ArkadeHistoryEntry(
    key = key,
    boardingTxid = boardingTxid,
    commitmentTxid = commitmentTxid,
    arkTxid = arkTxid,
    type = txType,
    tag = tag,
    amount = amount.toLong(),
    settled = settled,
    createdAt = createdAt.toLong(),
)

private fun CoreArkadeSwap.toApp() = ArkadeSwap(
    id = id,
    direction = direction,
    outcome = outcome,
    giveSat = giveSat.toLong(),
    takeSat = takeSat.toLong(),
    feeSat = feeSat.toLong(),
    bolt11 = bolt11.ifEmpty { null },
    paymentHash = paymentHash.ifEmpty { null },
    createdAt = createdAt.toLong().takeIf { it > 0 },
    updatedAt = updatedAt.toLong().takeIf { it > 0 },
    blockedReason = blockedReason,
    expiresAt = expiresAt.toLong().takeIf { it > 0 },
)

@Serializable
data class ArkadeInfo(
    val arkAddress: String,
    val boardingAddress: String,
    val network: String,
    val dust: Long = 0,
    val unilateralExitDelay: Long = 0,
    val boardingExitDelay: Long = 0,
    val delegate: Boolean = false,
)

@Serializable
data class ArkadeBalance(
    val available: Long = 0,
    val settled: Long = 0,
    val preconfirmed: Long = 0,
    val recoverable: Long = 0,
    val boardingConfirmed: Long = 0,
    val boardingUnconfirmed: Long = 0,
    val total: Long = 0,
)

@Serializable
data class ArkadeVtxo(
    val txid: String,
    val vout: Int,
    val value: Long,
    val state: String? = null,
    /** Unix seconds after which the operator may sweep this output unless it is renewed. */
    val batchExpiry: Long? = null,
    val createdAt: Long? = null,
    val isPreconfirmed: Boolean? = null,
    val isSwept: Boolean? = null,
)

@Serializable
data class ArkadeHistoryEntry(
    val key: String,
    val boardingTxid: String? = null,
    val commitmentTxid: String? = null,
    val arkTxid: String? = null,
    /** `SENT` or `RECEIVED`. */
    val type: String,
    val tag: String? = null,
    val amount: Long,
    val settled: Boolean,
    val createdAt: Long,
)

/** A Lightning swap through Arkade Intents: `receive` pays into the wallet, `send` pays an invoice out of it. */
@Serializable
data class ArkadeSwap(
    val id: String,
    val direction: String,
    val outcome: String,
    val giveSat: Long? = null,
    val takeSat: Long? = null,
    val feeSat: Long? = null,
    val bolt11: String? = null,
    val paymentHash: String? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val blockedReason: String? = null,
    val expiresAt: Long? = null,
) {
    val isReceive: Boolean get() = direction == "receive"
    val isSend: Boolean get() = direction == "send"
}

@Serializable
data class ArkadePayQuote(val feeSat: Long, val giveSat: Long, val takeSat: Long)

@Serializable
data class ArkadeLightningAddress(val handle: String, val lightningAddress: String? = null, val lnurl: String)

sealed interface ArkadeEvent {
    data class Swap(val swap: ArkadeSwap) : ArkadeEvent
    data class History(val entries: List<ArkadeHistoryEntry>) : ArkadeEvent
}

sealed class ArkadeError(message: String) : AppError(message) {
    class Unsupported : ArkadeError("Arkade is not available on this network")
    class NotConnected : ArkadeError("Arkade wallet is not connected")
    class Bridge(message: String) : ArkadeError(message)
}

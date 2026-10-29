package to.bitkit.services

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import to.bitkit.utils.AppError
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Arkade's wallet and Lightning client for Bitkit. The engine is a Rust binding on `arkade-os/rust-sdk` (ark-rs) over
 * UniFFI, which adds the Arkade Intents Lightning client the Rust SDK lacks; until it lands, Arkade stays off and
 * every flow takes its channel path. The wallet derives its keys from the Bitkit seed on BIP86 account 1
 * (`m/86'/0'/1'`), since account 0 holds Savings' Taproot keys.
 */
@Singleton
class ArkadeService @Inject constructor() {
    private val _events = MutableSharedFlow<ArkadeEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ArkadeEvent> = _events.asSharedFlow()

    val isSupported: Boolean get() = false

    val isConnected: Boolean get() = false

    val networkName: String? get() = null

    /**
     * Starts the wallet: [restore] scans the operator for the wallet's past outputs and swaps, which a wallet
     * restored from its phrase needs; [backup] is a state export to import before the wallet opens its storage.
     */
    @Suppress("UnusedParameter")
    suspend fun connect(restore: Boolean, backup: String?): ArkadeInfo = throw ArkadeError.Unsupported()

    suspend fun disconnect() = Unit

    suspend fun wipeStorage() = Unit

    suspend fun reconnect() = Unit

    suspend fun info(): ArkadeInfo = throw ArkadeError.Unsupported()

    suspend fun balance(): ArkadeBalance = throw ArkadeError.Unsupported()

    suspend fun vtxos(): List<ArkadeVtxo> = throw ArkadeError.Unsupported()

    suspend fun history(): List<ArkadeHistoryEntry> = throw ArkadeError.Unsupported()

    suspend fun swaps(): List<ArkadeSwap> = throw ArkadeError.Unsupported()

    /** An invoice for exactly [amountSats]: the payer pays it, and the wallet gets it less the solver's fee. */
    @Suppress("UnusedParameter")
    suspend fun receive(amountSats: ULong, maxFeeSats: ULong? = null): ArkadeSwap = throw ArkadeError.Unsupported()

    @Suppress("UnusedParameter")
    suspend fun quotePay(bolt11: String): ArkadePayQuote = throw ArkadeError.Unsupported()

    /** Funds the lockup for [bolt11] and returns at once; the outcome arrives as a swap event. */
    @Suppress("UnusedParameter")
    suspend fun pay(bolt11: String, maxFeeSats: ULong?): ArkadeSwap = throw ArkadeError.Unsupported()

    /** Boards confirmed on-chain deposits now, instead of on the wallet's next one-minute pass. */
    suspend fun settle(): String? = throw ArkadeError.Unsupported()

    /** Renews outputs nearing their batch expiry; the wallet also does it by itself 3 days before expiry. */
    suspend fun renew(): String? = throw ArkadeError.Unsupported()

    /** Moves [amountSats], or everything when null, to the on-chain [address] in the next batch. */
    @Suppress("UnusedParameter")
    suspend fun offboard(address: String, amountSats: ULong? = null): String = throw ArkadeError.Unsupported()

    /** The wallet's Lightning address, claimed on first use and found again from the seed. */
    suspend fun lightningAddress(): ArkadeLightningAddress? = throw ArkadeError.Unsupported()

    /** Everything the wallet keeps locally, as one JSON document, for the VSS backup. */
    suspend fun exportState(): String = throw ArkadeError.Unsupported()
}

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
    data object Restarted : ArkadeEvent
}

sealed class ArkadeError(message: String) : AppError(message) {
    class Unsupported : ArkadeError("Arkade is not available on this network")
    class NotConnected : ArkadeError("Arkade wallet is not connected")
    class Bridge(message: String) : ArkadeError(message)
}

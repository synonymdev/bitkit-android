package to.bitkit.services

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.lightningdevkit.ldknode.Network
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.json
import to.bitkit.env.Env
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs Ark Labs' TypeScript Arkade wallet, swap client and LNURL client (`@arkade-os/sdk`, `@arkade-os/swap`,
 * `@arkade-os/lnurl-client`) in a hidden WebView, since Arkade's Lightning client exists only in TypeScript. The
 * bundle is built from `arkade-bridge/` into `assets/arkade/` and answers over one JSON message channel. The Arkade
 * wallet derives its keys from the Bitkit seed on its own BIP86 account (`m/86'/0'/1'`), so there is no new phrase.
 */
@Singleton
class ArkadeService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keychain: Keychain,
) {
    companion object {
        private const val TAG = "ArkadeService"
        private const val ASSET_HOST = "appassets.androidplatform.net"
        private const val BRIDGE_URL = "https://$ASSET_HOST/assets/arkade/index.html"
        private const val CALL_TIMEOUT_MS = 120_000L
        private const val LOAD_TIMEOUT_MS = 30_000L
    }

    private val mutex = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Result<JsonElement>>>()
    private val callIds = AtomicLong()
    private var webView: WebView? = null
    private var loaded = CompletableDeferred<Unit>()
    private var startedWith: StartParams? = null
    private var info: ArkadeInfo? = null

    private val _events = MutableSharedFlow<ArkadeEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ArkadeEvent> = _events.asSharedFlow()

    /** Arkade runs on mainnet against Ark Labs' operator, and on mutinynet, Arkade's test network, for dev builds. */
    val isSupported: Boolean get() = config() != null

    val isConnected: Boolean get() = info != null

    val networkName: String? get() = config()?.network

    /**
     * Starts the wallet: [restore] scans the operator for the wallet's past outputs and swaps, which a wallet
     * restored from its phrase needs; [backup] is a state export to import before the wallet opens its databases.
     */
    suspend fun connect(restore: Boolean, backup: String?): ArkadeInfo = mutex.withLock {
        info?.let { return@withLock it }
        val config = config() ?: throw ArkadeError.Unsupported()
        val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name) ?: throw ServiceError.MnemonicNotFound()
        val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)
        ensureWebView()
        val params = StartParams(
            mnemonic = mnemonic,
            passphrase = passphrase,
            network = config.network,
            arkServerUrl = config.arkServerUrl,
            delegateUrl = config.delegateUrl,
            lnurlUrl = config.lnurlUrl,
            restore = restore,
            backup = backup,
        )
        val started = decode<ArkadeInfo>(call("start", json.encodeToJsonElement(StartParams.serializer(), params)))
        startedWith = params.copy(restore = false, backup = null)
        info = started
        Logger.info("Connected Arkade wallet on '${config.network}'", context = TAG)
        started
    }

    suspend fun disconnect() = mutex.withLock {
        withContext(Dispatchers.Main) {
            webView?.destroy()
            webView = null
        }
        failPending(ArkadeError.NotConnected())
        info = null
        startedWith = null
        Logger.info("Disconnected Arkade wallet", context = TAG)
    }

    suspend fun wipeStorage() {
        runCatching {
            ensureWebView()
            call("wipe")
        }.onFailure { Logger.warn("Failed to wipe Arkade storage", it, context = TAG) }
        disconnect()
    }

    suspend fun info(): ArkadeInfo = decode(call("info"))

    suspend fun balance(): ArkadeBalance = decode(call("balance"))

    suspend fun vtxos(): List<ArkadeVtxo> = decode(call("vtxos"))

    suspend fun history(): List<ArkadeHistoryEntry> = decode(call("history"))

    suspend fun swaps(): List<ArkadeSwap> = decode(call("swaps"))

    /** An invoice for exactly [amountSats]: the payer pays it, and the wallet gets it less the solver's fee. */
    suspend fun receive(amountSats: ULong, maxFeeSats: ULong? = null): ArkadeSwap =
        decode(
            call(
                "receive",
                buildJsonObject {
                    put("amountSat", amountSats.toLong())
                    maxFeeSats?.let { put("maxFeeSat", it.toLong()) }
                },
            ),
        )

    suspend fun quotePay(bolt11: String): ArkadePayQuote =
        decode(call("quotePay", buildJsonObject { put("bolt11", bolt11) }))

    /** Funds the lockup for [bolt11] and returns at once; the outcome arrives as a swap event. */
    suspend fun pay(bolt11: String, maxFeeSats: ULong?): ArkadeSwap =
        decode(
            call(
                "pay",
                buildJsonObject {
                    put("bolt11", bolt11)
                    maxFeeSats?.let { put("maxFeeSat", it.toLong()) }
                },
            ),
        )

    /** Boards confirmed on-chain deposits now, instead of on the wallet's next one-minute pass. */
    suspend fun settle(): String? = call(
        "settle"
    ).jsonObject["txid"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    /** Renews outputs nearing their batch expiry; the wallet also does it by itself 3 days before expiry. */
    suspend fun renew(): String? = call(
        "renew"
    ).jsonObject["txid"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    /** Moves [amountSats], or everything when null, to the on-chain [address] in the next batch. */
    suspend fun offboard(address: String, amountSats: ULong? = null): String =
        call(
            "offboard",
            buildJsonObject {
                put("address", address)
                amountSats?.let { put("amountSat", it.toLong()) }
            },
        ).jsonObject.getValue("txid").jsonPrimitive.content

    /** The wallet's Lightning address on Ark Labs' LNURL server, claimed on first use and found again from the seed. */
    suspend fun lightningAddress(): ArkadeLightningAddress? =
        call("lightningAddress").takeUnless { it is JsonNull }?.let { decode(it) }

    /** Everything the wallet keeps locally, as one JSON document, for the VSS backup. */
    suspend fun exportState(): String = call("exportState").jsonPrimitive.content

    private suspend fun call(method: String, params: JsonElement = JsonObject(emptyMap())): JsonElement {
        ensureWebView()
        val id = "k${callIds.incrementAndGet()}"
        val deferred = CompletableDeferred<Result<JsonElement>>()
        pending[id] = deferred
        val script = "window.bitkitArk.call(${quote(id)}, ${quote(method)}, ${quote(params.toString())})"
        withContext(Dispatchers.Main) { requireNotNull(webView).evaluateJavascript(script, null) }
        return try {
            withTimeout(CALL_TIMEOUT_MS) { deferred.await() }.getOrThrow()
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun ensureWebView() {
        if (webView != null) {
            withTimeout(LOAD_TIMEOUT_MS) { loaded.await() }
            return
        }
        loaded = CompletableDeferred()
        withContext(Dispatchers.Main) { webView = createWebView() }
        withTimeout(LOAD_TIMEOUT_MS) { loaded.await() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain(ASSET_HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        return WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            addJavascriptInterface(Bridge(), "BitkitArkNative")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    assetLoader.shouldInterceptRequest(request.url)

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    Logger.error("Arkade WebView renderer gone, crashed: '${detail.didCrash()}'", context = TAG)
                    onRendererGone(view)
                    return true
                }
            }
            loadUrl(BRIDGE_URL)
        }
    }

    // The renderer took the wallet with it: start it again with the parameters it last ran with.
    private fun onRendererGone(view: WebView) {
        if (webView === view) webView = null
        view.destroy()
        failPending(ArkadeError.NotConnected())
        info = null
        _events.tryEmit(ArkadeEvent.Restarted)
    }

    suspend fun reconnect() {
        val params = startedWith ?: return
        connect(restore = params.restore, backup = null)
    }

    private fun failPending(error: Throwable) {
        pending.values.forEach { it.complete(Result.failure(error)) }
        pending.clear()
    }

    private inner class Bridge {
        @JavascriptInterface
        fun postMessage(message: String) {
            runCatching { onMessage(message) }
                .onFailure { Logger.error("Failed to handle Arkade message", it, context = TAG) }
        }
    }

    private fun onMessage(message: String) {
        val obj = json.parseToJsonElement(message).jsonObject
        val id = obj["id"]?.jsonPrimitive?.content
        if (id != null) {
            val ok = obj["ok"]?.jsonPrimitive?.content == "true"
            val result = if (ok) {
                Result.success(obj["result"] ?: JsonNull)
            } else {
                Result.failure(ArkadeError.Bridge(obj["error"]?.jsonPrimitive?.content ?: "unknown error"))
            }
            pending[id]?.complete(result)
            return
        }
        when (obj["event"]?.jsonPrimitive?.content) {
            "ready" -> loaded.complete(Unit)
            "log" -> Logger.debug("bridge: ${obj["message"]?.jsonPrimitive?.content}", context = TAG)
            "swap" -> obj["swap"]?.let { _events.tryEmit(ArkadeEvent.Swap(decode(it))) }
            "history" -> _events.tryEmit(ArkadeEvent.History(decode(obj.getValue("entries"))))
        }
    }

    private inline fun <reified T> decode(element: JsonElement): T = json.decodeFromJsonElement(element)

    private fun quote(value: String): String = json.encodeToString(value)

    private fun config(): ArkadeConfig? = when (Env.network) {
        Network.BITCOIN -> ArkadeConfig(
            network = "bitcoin",
            arkServerUrl = "https://arkade.computer",
            delegateUrl = "https://delegate.arkade.money",
            lnurlUrl = "https://lnurl.arkade.sh",
        )
        Network.REGTEST -> ArkadeConfig(
            network = "mutinynet",
            arkServerUrl = "https://mutinynet.arkade.sh",
            delegateUrl = "https://delegator.mutinynet.arkade.sh",
            lnurlUrl = null,
        )
        else -> null
    }
}

private data class ArkadeConfig(
    val network: String,
    val arkServerUrl: String,
    val delegateUrl: String?,
    val lnurlUrl: String?,
)

@Serializable
private data class StartParams(
    val mnemonic: String,
    val passphrase: String?,
    val network: String,
    val arkServerUrl: String,
    val delegateUrl: String?,
    val lnurlUrl: String?,
    val restore: Boolean,
    val backup: String?,
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
    data object Restarted : ArkadeEvent
}

sealed class ArkadeError(message: String) : AppError(message) {
    class Unsupported : ArkadeError("Arkade is not available on this network")
    class NotConnected : ArkadeError("Arkade wallet is not connected")
    class Bridge(message: String) : ArkadeError(message)
}

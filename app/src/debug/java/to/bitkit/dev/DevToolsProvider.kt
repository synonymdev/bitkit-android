package to.bitkit.dev

import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import androidx.core.os.bundleOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import to.bitkit.async.ServiceQueue
import to.bitkit.models.msatCeilOf
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.ProbeOutcome
import to.bitkit.utils.Logger
import kotlin.time.Duration.Companion.seconds
import to.bitkit.repositories.ProbeReadiness as NodeProbeReadiness

private const val TAG = "DevToolsProvider"
private val DEV_JSON = Json { encodeDefaults = true }

class DevToolsProvider : ContentProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun lightningRepo(): LightningRepo
    }

    private val deps: Dependencies by lazy {
        val ctx = requireNotNull(context) { "DevToolsProvider context is null" }
        EntryPointAccessors.fromApplication(ctx, Dependencies::class.java)
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == Process.SHELL_UID) { "Only ADB shell callers are allowed" }
        return runCatching {
            val command = requireNotNull(DevCommand.parse(method, arg)) { "Unknown command: '$method'" }
            ServiceQueue.LDK.blocking { command.execute(deps) }
        }.getOrElse {
            Logger.error("Failed to execute command '$method'", it, context = TAG)
            DevResult.Error(it.message)
        }.toBundle()
    }

    override fun onCreate() = true
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, sel: String?, args: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, sel: String?, args: Array<String>?) = 0
    override fun query(uri: Uri, proj: Array<String>?, sel: String?, args: Array<String>?, sort: String?) = null
}

private sealed interface DevCommand {

    companion object {
        fun parse(method: String, arg: String?): DevCommand? = when (method) {
            CreateInvoice.METHOD -> CreateInvoice.parse(arg)
            ProbeInvoice.METHOD -> ProbeInvoice.parse(arg)
            ProbeNode.METHOD -> ProbeNode.parse(arg)
            ProbeReadiness.METHOD -> ProbeReadiness
            ResetScores.METHOD -> ResetScores
            else -> null
        }
    }

    suspend fun execute(deps: DevToolsProvider.Dependencies): DevResult

    data class CreateInvoice(val args: Args) : DevCommand {
        companion object {
            const val METHOD = "createInvoice"
            fun parse(arg: String?) = CreateInvoice(arg.deserialize<Args>())
        }

        @Serializable
        data class Args(val amount: ULong? = null, val description: String = "dev-invoice")

        override suspend fun execute(deps: DevToolsProvider.Dependencies) =
            deps.lightningRepo().createInvoice(args.amount, args.description).fold(
                onSuccess = { DevResult.Invoice(it) },
                onFailure = {
                    Logger.error("Failed to create invoice", it, context = TAG)
                    DevResult.Error(it.message)
                },
            )
    }

    data class ProbeInvoice(val args: Args) : DevCommand {
        companion object {
            const val METHOD = "probeInvoice"
            fun parse(arg: String?) = ProbeInvoice(arg.deserialize<Args>())
        }

        @Serializable
        data class Args(
            val targetName: String? = null,
            val bolt11: String,
            val amountMsat: ULong? = null,
            val amountSats: ULong? = null,
            val timeoutSeconds: Long = 90,
        )

        override suspend fun execute(deps: DevToolsProvider.Dependencies): DevResult {
            val amountSats = args.amountSats ?: args.amountMsat?.let { msatCeilOf(it) }
            val timeout = args.timeoutSeconds.coerceAtLeast(1).seconds

            Logger.info(
                "Sending probe for target '${args.targetName ?: "unknown"}' amountSats='${amountSats ?: "invoice"}'",
                context = TAG,
            )

            return deps.lightningRepo().sendProbeForInvoice(args.bolt11, amountSats)
                .fold(
                    onSuccess = {
                        deps.lightningRepo().waitForProbeOutcome(it.paymentIds, timeout)
                            .fold(
                                onSuccess = { outcome -> outcome.toDevResult(it.paymentIds) },
                                onFailure = { error -> DevResult.ProbeFailure.from(error, it.paymentIds) },
                            )
                    },
                    onFailure = { DevResult.ProbeFailure.from(it) },
                )
        }
    }

    data class ProbeNode(val args: Args) : DevCommand {
        companion object {
            const val METHOD = "probeNode"
            fun parse(arg: String?) = ProbeNode(arg.deserialize<Args>())
        }

        @Serializable
        data class Args(
            val targetName: String? = null,
            val nodeId: String,
            val amountMsat: ULong? = null,
            val amountSats: ULong? = null,
            val timeoutSeconds: Long = 90,
        )

        override suspend fun execute(deps: DevToolsProvider.Dependencies): DevResult {
            val amountSats = args.amountSats ?: args.amountMsat?.let { msatCeilOf(it) }
                ?: return DevResult.Error("Probe node requires amountSats or amountMsat")
            val timeout = args.timeoutSeconds.coerceAtLeast(1).seconds

            Logger.info(
                "Sending keysend probe for target '${args.targetName ?: "unknown"}' nodeId='${args.nodeId}' amountSats='$amountSats'",
                context = TAG,
            )

            return deps.lightningRepo().sendProbeForNode(args.nodeId, amountSats)
                .fold(
                    onSuccess = {
                        deps.lightningRepo().waitForProbeOutcome(it.paymentIds, timeout)
                            .fold(
                                onSuccess = { outcome -> outcome.toDevResult(it.paymentIds) },
                                onFailure = { error -> DevResult.ProbeFailure.from(error, it.paymentIds) },
                            )
                    },
                    onFailure = { DevResult.ProbeFailure.from(it) },
                )
        }
    }

    data object ProbeReadiness : DevCommand {
        const val METHOD = "probeReadiness"

        override suspend fun execute(deps: DevToolsProvider.Dependencies): DevResult =
            DevResult.ProbeReadiness.from(deps.lightningRepo().probeReadiness())
    }

    data object ResetScores : DevCommand {
        const val METHOD = "resetScores"

        override suspend fun execute(deps: DevToolsProvider.Dependencies): DevResult {
            Logger.info("Resetting pathfinding scores via devtools", context = TAG)
            return deps.lightningRepo().resetPathfindingScores().fold(
                onSuccess = { DevResult.Ack() },
                onFailure = {
                    Logger.error("Failed to reset pathfinding scores", it, context = TAG)
                    DevResult.Error(it.message)
                },
            )
        }
    }
}

@Serializable
private sealed interface DevResult {

    companion object {
        private const val KEY_RESULT = "result"
    }

    @Serializable data class Invoice(val bolt11: String) : DevResult

    @Serializable data class Ack(val success: Boolean = true) : DevResult

    @Serializable
    data class ProbeSuccess(
        val success: Boolean = true,
        val paymentId: String,
        val paymentHash: String,
        val paymentIds: List<String>,
    ) : DevResult

    @Serializable
    data class ProbeFailure(
        val success: Boolean = false,
        val message: String? = null,
        val paymentId: String? = null,
        val paymentHash: String? = null,
        val shortChannelId: ULong? = null,
        val paymentIds: List<String> = emptyList(),
    ) : DevResult {
        companion object {
            fun from(error: Throwable, paymentIds: Set<String> = emptySet()) = ProbeFailure(
                message = error.message,
                paymentIds = paymentIds.toList(),
            )
        }
    }

    @Serializable
    data class ProbeReadiness(
        val ready: Boolean,
        val nodeRunning: Boolean,
        val lifecycle: String,
        val peers: Int,
        val connectedPeers: Int,
        val channels: Int,
        val readyChannels: Int,
        val usableChannels: Int,
        val outboundCapacitySats: ULong,
        val syncHealthy: Boolean,
        val nodeId: String? = null,
        val graphNodeCount: Int? = null,
        val graphChannelCount: Int? = null,
        val latestRgsSyncTimestamp: ULong? = null,
        val latestPathfindingScoresSyncTimestamp: ULong? = null,
    ) : DevResult {
        companion object {
            fun from(readiness: NodeProbeReadiness) = ProbeReadiness(
                ready = readiness.ready,
                nodeRunning = readiness.nodeRunning,
                lifecycle = readiness.lifecycle,
                peers = readiness.peers,
                connectedPeers = readiness.connectedPeers,
                channels = readiness.channels,
                readyChannels = readiness.readyChannels,
                usableChannels = readiness.usableChannels,
                outboundCapacitySats = readiness.outboundCapacitySats,
                syncHealthy = readiness.syncHealthy,
                nodeId = readiness.nodeId,
                graphNodeCount = readiness.graphNodeCount,
                graphChannelCount = readiness.graphChannelCount,
                latestRgsSyncTimestamp = readiness.latestRgsSyncTimestamp,
                latestPathfindingScoresSyncTimestamp = readiness.latestPathfindingScoresSyncTimestamp,
            )
        }
    }

    @Serializable data class Error(val message: String? = null) : DevResult

    fun toBundle() = bundleOf(KEY_RESULT to DEV_JSON.encodeToString(this))
}

private fun ProbeOutcome.toDevResult(paymentIds: Set<String>): DevResult = when (this) {
    is ProbeOutcome.Success -> DevResult.ProbeSuccess(
        paymentId = paymentId,
        paymentHash = paymentHash,
        paymentIds = paymentIds.toList(),
    )
    is ProbeOutcome.Failure -> DevResult.ProbeFailure(
        message = "Probe failed",
        paymentId = paymentId,
        paymentHash = paymentHash,
        shortChannelId = shortChannelId,
        paymentIds = paymentIds.toList(),
    )
}

private inline fun <reified T> String?.deserialize(): T =
    if (isNullOrBlank()) Json.decodeFromString("{}") else Json.decodeFromString(this)

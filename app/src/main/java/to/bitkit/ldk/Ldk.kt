package to.bitkit.ldk

import android.util.Log
import org.ldk.batteries.ChannelManagerConstructor
import org.ldk.batteries.NioPeerHandler
import org.ldk.structs.BroadcasterInterface
import org.ldk.structs.ChainMonitor
import org.ldk.structs.ChannelHandshakeConfig
import org.ldk.structs.ChannelHandshakeLimits
import org.ldk.structs.ChannelManager
import org.ldk.structs.FeeEstimator
import org.ldk.structs.Filter
import org.ldk.structs.Logger
import org.ldk.structs.MultiThreadedLockableScore
import org.ldk.structs.NetworkGraph
import org.ldk.structs.Option_FilterZ
import org.ldk.structs.PeerManager
import org.ldk.structs.Persist
import org.ldk.structs.ProbabilisticScorer
import org.ldk.structs.ProbabilisticScoringDecayParameters
import org.ldk.structs.ProbabilisticScoringFeeParameters
import org.ldk.structs.Result_NetworkGraphDecodeErrorZ
import org.ldk.structs.Result_ProbabilisticScorerDecodeErrorZ
import org.ldk.structs.UserConfig
import org.ldk.structs.WatchedOutput
import to.bitkit.LDK_NETWORK
import to.bitkit._LDK
import to.bitkit.bdk.Bdk
import to.bitkit.data.WatchedTransaction
import to.bitkit.ext.toByteArray
import java.io.File
import java.net.InetSocketAddress

@JvmField
var ldkDir: String = ""

object Ldk {
    lateinit var channelManager: ChannelManager
    lateinit var keysManager: LdkKeysManager
    lateinit var chainMonitor: ChainMonitor
    lateinit var channelManagerConstructor: ChannelManagerConstructor
    lateinit var nioPeerHandler: NioPeerHandler
    lateinit var peerManager: PeerManager
    lateinit var networkGraph: NetworkGraph
    lateinit var scorer: MultiThreadedLockableScore

    object Channel {
        var temporaryId: ByteArray? = null
        var counterpartyNodeId: ByteArray? = null
    }

    object Relevant {
        val txs = arrayListOf<WatchedTransaction>()
        val outputs = arrayListOf<WatchedOutput>()
    }

    object Events {
        var fundingGenerationReady = arrayOf<String>()
        var channelClosed = arrayOf<String>()
        var registerTx = arrayOf<String>()
        var registerOutput = arrayOf<String>()
    }
}

fun Ldk.init(
    entropy: ByteArray,
    latestBlockHeight: Int,
    latestBlockHash: String,
    serializedChannelManager: ByteArray?,
    serializedChannelMonitors: Array<ByteArray>,
): Boolean {
    Log.d(_LDK, "Starting LDK version: ${org.ldk.impl.version.get_ldk_java_bindings_version()}")

    val feeEstimator: FeeEstimator = FeeEstimator.new_impl(LdkFeeEstimator)
    val logger: Logger = Logger.new_impl(LdkLogger)
    val txBroadcaster: BroadcasterInterface = BroadcasterInterface.new_impl(LdkBroadcaster)
    val persister: Persist = Persist.new_impl(LdkPersister)

    initNetworkGraph(logger)

    val filter = Filter.new_impl(LdkFilter)
    chainMonitor = ChainMonitor.of(
        Option_FilterZ.some(filter),
        txBroadcaster,
        logger,
        feeEstimator,
        persister,
    )

    initKeysManager(entropy)
    initProbabilisticScorer(logger)

    val channelHandShakeConfig = ChannelHandshakeConfig.with_default().apply {
        _minimum_depth = 1
        _announced_channel = false
    }
    val channelHandshakeLimits = ChannelHandshakeLimits.with_default().apply {
        _max_minimum_depth = 1
    }
    val userConfig = UserConfig.with_default().apply {
        _channel_handshake_config = channelHandShakeConfig
        _channel_handshake_limits = channelHandshakeLimits
        _accept_inbound_channels = true
    }

    try {
        val entropySource = keysManager.inner.as_EntropySource()
        val nodeSigner = keysManager.inner.as_NodeSigner()
        val signerProvider = keysManager.inner.as_SignerProvider()
        val pScoringDecayParams = ProbabilisticScoringDecayParameters.with_default()
        val pScoringFeeParams = ProbabilisticScoringFeeParameters.with_default()

        val constructor = if (serializedChannelManager?.isNotEmpty() == true) {
            // Restore from disk
            ChannelManagerConstructor(
                serializedChannelManager,
                serializedChannelMonitors,
                userConfig,
                entropySource,
                nodeSigner,
                signerProvider,
                feeEstimator,
                chainMonitor,
                filter,
                networkGraph.write(),
                pScoringDecayParams,
                pScoringFeeParams,
                scorer.write(),
                null,
                txBroadcaster,
                logger,
            )
        } else {
            // Start from scratch
            ChannelManagerConstructor(
                LDK_NETWORK,
                userConfig,
                latestBlockHash.toByteArray(),
                latestBlockHeight,
                entropySource,
                nodeSigner,
                signerProvider,
                feeEstimator,
                chainMonitor,
                networkGraph,
                pScoringDecayParams,
                pScoringFeeParams,
                null,
                txBroadcaster,
                logger,
            )
        }
        channelManagerConstructor = constructor
        channelManager = constructor.channel_manager
        peerManager = constructor.peer_manager
        nioPeerHandler = constructor.nio_peer_handler
        networkGraph = constructor.net_graph

        constructor.chain_sync_completed(LdkEventHandler, true)
        nioPeerHandler.bind_listener(InetSocketAddress("127.0.0.1", 9777))

        return true
    } catch (e: Exception) {
        Log.d(_LDK, "Error starting LDK:\n" + e.message)
        return false
    }
}

private fun initKeysManager(entropy: ByteArray) {
    val timeMillis = System.currentTimeMillis()
    val startTimeSecs = timeMillis / 1000
    val startTimeNano = (timeMillis * 1000).toInt()
    Ldk.keysManager = LdkKeysManager(
        entropy,
        startTimeSecs,
        startTimeNano,
        Bdk.wallet,
    )
}

private fun initNetworkGraph(logger: Logger) {
    val graphFile = File("$ldkDir/network-graph.bin")
    if (graphFile.exists()) {
        when (val graph = NetworkGraph.read(graphFile.readBytes(), logger)) {
            is Result_NetworkGraphDecodeErrorZ.Result_NetworkGraphDecodeErrorZ_OK -> {
                Ldk.networkGraph = graph.res
            }
        }
        Log.d(_LDK, "Network graph found and loaded from disk.")
    } else {
        Ldk.networkGraph = NetworkGraph.of(LDK_NETWORK, logger)
        Log.d(_LDK, "Network graph not found on disk, synced from scratch.")
    }
}

private fun initProbabilisticScorer(logger: Logger) {
    val decayParams = ProbabilisticScoringDecayParameters.with_default()
    val scorerFile = File("$ldkDir/scorer.bin")
    val scorer = if (scorerFile.exists()) {
        val read = ProbabilisticScorer
            .read(scorerFile.readBytes(), decayParams, Ldk.networkGraph, logger)
        if (read.is_ok) {
            check(read is Result_ProbabilisticScorerDecodeErrorZ.Result_ProbabilisticScorerDecodeErrorZ_OK)
            Log.d(_LDK, "Probabilistic Scorer found and loaded from disk.")
            // return:
            read.res
        } else {
            Log.d(_LDK, "Error loading Probabilistic Scorer, started from scratch.")
            // return:
            ProbabilisticScorer.of(decayParams, Ldk.networkGraph, logger)
        }
    } else {
        Log.d(_LDK, "Probabilistic Scorer not found on disk, started from scratch.")
        // return:
        ProbabilisticScorer.of(decayParams, Ldk.networkGraph, logger)
    }
    Ldk.scorer = MultiThreadedLockableScore.of(scorer.as_Score())
}

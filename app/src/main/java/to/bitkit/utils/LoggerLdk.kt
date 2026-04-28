package to.bitkit.utils

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.lightningdevkit.ldknode.Bolt11Invoice
import org.lightningdevkit.ldknode.LogRecord
import org.lightningdevkit.ldknode.LogWriter
import org.lightningdevkit.ldknode.Node
import org.lightningdevkit.ldknode.PeerDetails
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import org.lightningdevkit.ldknode.LogLevel as LdkLogLevel

private const val LDK = "LDK"

@Singleton
class LoggerLdk @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    companion object {
        private const val TAG = "LogDumperLdk"
    }

    @Suppress("LongMethod")
    fun dumpNetworkGraphInfo(node: Node, trustedPeers: List<PeerDetails>, bolt11: String) {
        val nodeIdPreviewLen = 20

        val sb = StringBuilder()
        sb.appendLine("\n\n=== ROUTE NOT FOUND - NETWORK GRAPH DUMP ===\n")

        runCatching {
            val invoice = Bolt11Invoice.fromStr(bolt11)
            sb.appendLine("Invoice Info:")
            sb.appendLine("  - Payment Hash: ${invoice.paymentHash()}")
            sb.appendLine("  - Invoice: $bolt11")
        }.getOrElse {
            sb.appendLine("Failed to parse bolt11 invoice: $it")
        }

        sb.appendLine("\nOur Node Info:")
        sb.appendLine("  - Node ID: ${node.nodeId()}")

        sb.appendLine("\nOur Channels:")
        val channels = node.listChannels()
        sb.appendLine("  Total channels: ${channels.size}")

        var totalOutboundMsat = 0UL
        var totalInboundMsat = 0UL
        var usableChannels = 0
        var announcedChannels = 0

        channels.forEachIndexed { index, channel ->
            totalOutboundMsat += channel.outboundCapacityMsat
            totalInboundMsat += channel.inboundCapacityMsat
            if (channel.isUsable) usableChannels++
            if (channel.isAnnounced) announcedChannels++

            sb.appendLine("  Channel ${index + 1}:")
            sb.appendLine("    - Channel ID: ${channel.channelId}")
            sb.appendLine("    - Counterparty: ${channel.counterpartyNodeId}")
            sb.appendLine(
                "    - Ready: ${channel.isChannelReady}, Usable: ${channel.isUsable}, " +
                    "Announced: ${channel.isAnnounced}"
            )
            sb.appendLine(
                "    - Outbound: ${channel.outboundCapacityMsat} msat, " +
                    "Inbound: ${channel.inboundCapacityMsat} msat"
            )
        }

        sb.appendLine("\n  Channel Summary:")
        sb.appendLine("    - Usable channels: $usableChannels/${channels.size}")
        sb.appendLine("    - Announced channels: $announcedChannels/${channels.size}")
        sb.appendLine("    - Total Outbound: $totalOutboundMsat msat")
        sb.appendLine("    - Total Inbound: $totalInboundMsat msat")

        sb.appendLine("\nOur Peers:")
        val peers = node.listPeers()
        sb.appendLine("  Total peers: ${peers.size}")

        peers.forEachIndexed { index, peer ->
            sb.appendLine("  Peer ${index + 1}: ${peer.nodeId.take(nodeIdPreviewLen)}... @ ${peer.address}")
            sb.appendLine("    - Connected: ${peer.isConnected}, Persisted: ${peer.isPersisted}")
        }

        sb.appendLine("\nRGS Configuration:")
        sb.appendLine("  - RGS Server URL: ${Env.ldkRgsServerUrl ?: "Not configured"}")

        val nodeStatus = node.status()
        nodeStatus.latestRgsSnapshotTimestamp?.let { rgsTimestamp ->
            val date = java.util.Date(rgsTimestamp.toLong() * 1000)
            val timeAgoMs = System.currentTimeMillis() - date.time
            val hoursAgo = (timeAgoMs / 3600000).toInt()
            val minutesAgo = ((timeAgoMs % 3600000) / 60000).toInt()

            sb.appendLine("  - Last RGS Snapshot: $date")
            if (hoursAgo > 0) {
                sb.appendLine("  - Time since update: ${hoursAgo}h ${minutesAgo}m ago")
            } else {
                sb.appendLine("  - Time since update: ${minutesAgo}m ago")
            }
            sb.appendLine("  - Timestamp: $rgsTimestamp")
        } ?: run {
            sb.appendLine("  - Last RGS Snapshot: Never synced")
            sb.appendLine("  - WARNING: Network graph may be empty or stale!")
        }

        sb.appendLine("\nRGS Network Graph Data:")
        val networkGraph = node.networkGraph()
        val allNodes = networkGraph.listNodes()
        val allChannels = networkGraph.listChannels()

        sb.appendLine("  Total nodes: ${allNodes.size}")
        sb.appendLine("  Total channels: ${allChannels.size}")

        // Payee and route hints check
        runCatching {
            val invoice = Bolt11Invoice.fromStr(bolt11)
            val payeeNodeId = invoice.recoverPayeePubKey()
            sb.appendLine("\nInvoice Payee & Route Hints:")
            sb.appendLine("  - Amount: ${invoice.amountMilliSatoshis()} msat")
            sb.appendLine("  - Payee Node ID: $payeeNodeId")
            sb.appendLine("  - Payee in graph: ${allNodes.any { it == payeeNodeId }}")

            val routeHints = invoice.routeHints()
            sb.appendLine("  - Route hints: ${routeHints.size} group(s)")
            routeHints.forEachIndexed { groupIdx, hops ->
                hops.forEachIndexed { hopIdx, hop ->
                    val hopInGraph = allNodes.any { it == hop.srcNodeId }
                    sb.appendLine(
                        "    Hint[$groupIdx][$hopIdx]: src=${hop.srcNodeId.take(nodeIdPreviewLen)}... " +
                            "scid=${hop.shortChannelId} inGraph=$hopInGraph"
                    )
                }
            }
        }

        sb.appendLine("\n  Checking for trusted peers in network graph:")
        var foundTrustedNodes = 0
        trustedPeers.forEach { peer ->
            val nodeId = peer.nodeId
            if (allNodes.any { it == nodeId }) {
                foundTrustedNodes++
                sb.appendLine("    OK: ${nodeId.take(nodeIdPreviewLen)}... found in graph")
            } else {
                sb.appendLine("    MISSING: ${nodeId.take(nodeIdPreviewLen)}... NOT in graph")
            }
        }
        sb.appendLine("  Summary: $foundTrustedNodes/${trustedPeers.size} trusted peers found in graph")

        val nodesToShow = minOf(10, allNodes.size)
        sb.appendLine("\n  First $nodesToShow nodes:")
        allNodes.take(nodesToShow).forEachIndexed { index, nodeId ->
            sb.appendLine("    ${index + 1}. $nodeId")
        }
        if (allNodes.size > nodesToShow) {
            sb.appendLine("    ... and ${allNodes.size - nodesToShow} more nodes")
        }

        sb.appendLine("\n=== END NETWORK GRAPH DUMP ===\n")

        Logger.info(sb.toString(), context = TAG)
    }

    suspend fun exportNetworkGraphToFile(
        node: Node,
        outputDir: String,
        fileName: String = "network_graph_nodes.txt",
    ): Result<File> = withContext(ioDispatcher) {
        runCatching {
            val graph = node.networkGraph()
            val nodes = graph.listNodes()

            val outputFile = File(outputDir, fileName)
            outputFile.bufferedWriter().use { writer ->
                writer.write("Network Graph Nodes Export\n")
                writer.write("Total nodes: ${nodes.size}\n")
                writer.write("Exported at: ${System.currentTimeMillis()}\n")
                writer.write("---\n")
                nodes.forEachIndexed { index, nodeId ->
                    writer.write("${index + 1}. $nodeId\n")
                }
            }

            Logger.info("Exported ${nodes.size} nodes to ${outputFile.absolutePath}", context = TAG)
            outputFile
        }.onFailure {
            Logger.error("Failed to export network graph to file", it, context = TAG)
        }
    }

    suspend fun exportPathfindingScoresToFile(
        node: Node,
        outputDir: String,
        fileName: String = "pathfinding_scores.bin",
    ): Result<File> = withContext(ioDispatcher) {
        runCatching {
            val bytes = node.exportPathfindingScores()
            val outputFile = File(outputDir, fileName)
            outputFile.writeBytes(bytes)
            Logger.info(
                "Exported pathfinding scores '${bytes.size}' bytes to '${outputFile.absolutePath}'",
                context = TAG,
            )
            outputFile
        }.onFailure {
            Logger.error("Failed to export pathfinding scores to file", it, context = TAG)
        }
    }
}

class LdkLogWriter(
    private val maxLogLevel: LdkLogLevel = Env.ldkLogLevel,
    saver: LogSaver = AppLogger.getOrCreateSaver(),
) : LogWriter {
    private val delegate: LoggerImpl = LoggerImpl(LDK, saver)

    override fun log(record: LogRecord) {
        if (record.level < maxLogLevel) return

        val msg = record.args
        val path = record.modulePath
        val line = record.line.toInt()

        when (record.level) {
            LdkLogLevel.GOSSIP -> delegate.verbose(msg, path = path, line = line, level = LogLevel.GOSSIP)
            LdkLogLevel.TRACE -> delegate.verbose(msg, path = path, line = line, level = LogLevel.TRACE)
            LdkLogLevel.DEBUG -> delegate.debug(msg, path = path, line = line)
            LdkLogLevel.INFO -> delegate.info(msg, path = path, line = line)
            LdkLogLevel.WARN -> delegate.warn(msg, path = path, line = line)
            LdkLogLevel.ERROR -> delegate.error(msg, path = path, line = line)
        }
    }
}

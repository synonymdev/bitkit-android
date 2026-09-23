package to.bitkit.services.offline.ldk

import org.lightningdevkit.ldknode.Builder
import org.lightningdevkit.ldknode.OfflineReceiveConfig
import org.lightningdevkit.ldknode.OfflineReceiveWitnessConfig
import to.bitkit.services.NodeBuilderCustomizer
import to.bitkit.services.offline.OfflineReceiveSettings
import to.bitkit.services.offline.OfflineReceiveSettingsSource
import to.bitkit.utils.Logger
import javax.inject.Inject

/** Applies `setOfflineReceiveConfig` when the dev toggle is on and a settlement peer is known. */
class LdkOfflineReceiveNodeCustomizer @Inject constructor(
    private val settingsSource: OfflineReceiveSettingsSource,
) : NodeBuilderCustomizer {

    override suspend fun customize(builder: Builder) {
        val settings = settingsSource.current()
        if (!settings.isConfigured) {
            Logger.debug("Offline receive not configured for this node", context = TAG)
            return
        }
        val config = settings.toConfig()
        builder.setOfflineReceiveConfig(config)
        Logger.info(
            "Offline receive configured: settlement=${config.settlementNodeId} witnesses=${config.witnesses.size}",
            context = TAG,
        )
    }

    companion object {
        private const val TAG = "LdkOfflineReceiveNodeCustomizer"

        // Development defaults from the ldk-node offline receive work; tune before any wider rollout.
        internal const val INVOICE_EXPIRY_SECONDS = 3_600u
        internal const val INVOICE_SAFETY_MARGIN_SECONDS = 120u
        internal const val SETTLEMENT_DEADLINE_BLOCKS = 144u
        internal const val DEADLINE_SAFETY_MARGIN_BLOCKS = 6u
        internal const val CLAIM_MARGIN_BLOCKS = 20u
        internal const val VOUCHER_EXPIRY_BLOCKS = 288u
        internal const val WITNESS_RETENTION_BLOCKS = VOUCHER_EXPIRY_BLOCKS
        internal const val WITNESS_MINIMUM_RECEIPTS: UByte = 0u
        internal const val FEE_BASE_MSAT = 0u
        internal const val FEE_PROPORTIONAL_MILLIONTHS = 0u
        internal const val POLL_INTERVAL_SECS = 5uL

        internal fun OfflineReceiveSettings.toConfig(): OfflineReceiveConfig = OfflineReceiveConfig(
            settlementNodeId = requireNotNull(settlementNodeId) { "Offline receive needs a settlement node id" },
            witnesses = witnessNodeIds.map {
                OfflineReceiveWitnessConfig(
                    nodeId = it,
                    retentionBlocks = WITNESS_RETENTION_BLOCKS,
                    minimumReceipts = WITNESS_MINIMUM_RECEIPTS,
                )
            },
            invoiceExpirySeconds = INVOICE_EXPIRY_SECONDS,
            invoiceSafetyMarginSeconds = INVOICE_SAFETY_MARGIN_SECONDS,
            settlementDeadlineBlocks = SETTLEMENT_DEADLINE_BLOCKS,
            deadlineSafetyMarginBlocks = DEADLINE_SAFETY_MARGIN_BLOCKS,
            claimMarginBlocks = CLAIM_MARGIN_BLOCKS,
            voucherExpiryBlocks = VOUCHER_EXPIRY_BLOCKS,
            feeBaseMsat = FEE_BASE_MSAT,
            feeProportionalMillionths = FEE_PROPORTIONAL_MILLIONTHS,
            pollIntervalSecs = POLL_INTERVAL_SECS,
        )
    }
}

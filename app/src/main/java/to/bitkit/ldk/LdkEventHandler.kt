package to.bitkit.ldk

import android.util.Log
import org.bitcoindevkit.Address
import org.ldk.batteries.ChannelManagerConstructor
import org.ldk.structs.ClosureReason
import org.ldk.structs.Event
import org.ldk.structs.Result_NoneAPIErrorZ
import org.ldk.structs.Result_TransactionNoneZ
import org.ldk.structs.TxOut
import org.ldk.util.UInt128
import to.bitkit._LDK
import to.bitkit.bdk.BitcoinService
import to.bitkit.ext.toHex
import to.bitkit.ldk.Ldk.channelManager
import kotlin.random.Random
import kotlin.reflect.typeOf

object LdkEventHandler : ChannelManagerConstructor.EventHandler {
    override fun handle_event(event: Event) {
        Log.d(_LDK, "LdkEventHandler: handle_event: $event")
        handleEvent(event)
    }

    override fun persist_manager(channelManagerBytes: ByteArray?) {
        if (channelManagerBytes != null) {
            Log.d(_LDK, "LdkEventHandler: persist_manager")
            persist("channel-manager.bin", channelManagerBytes)
        }
    }

    override fun persist_network_graph(networkGraph: ByteArray?) {
        if (networkGraph !== null) {
            Log.d(_LDK, "LdkEventHandler: persist_network_graph")
            persist("network-graph.bin", networkGraph)
        }
    }

    override fun persist_scorer(scorer: ByteArray?) {
        if (scorer !== null) {
            Log.d(_LDK, "LdkEventHandler: persist_scorer")
            persist("scorer.bin", scorer)
        }
    }
}

@OptIn(ExperimentalUnsignedTypes::class)
private fun handleEvent(event: Event) {
    when (event) {
        is Event.FundingGenerationReady -> {
            Log.d(_LDK, "event: FundingGenerationReady")
            if (event.output_script.size == 34 &&
                event.output_script[0].toInt() == 0 &&
                event.output_script[1].toInt() == 32
            ) {
                val rawTx = BitcoinService.shared.buildFundingTx(event.channel_value_satoshis, event.output_script)
                try {
                    val fundingTx = channelManager.funding_transaction_generated(
                        event.temporary_channel_id,
                        event.counterparty_node_id,
                        rawTx.serialize().toUByteArray().toByteArray(),
                    )
                    when (fundingTx) {
                        is Result_NoneAPIErrorZ.Result_NoneAPIErrorZ_OK ->
                            Log.d(_LDK, "Funding tx generated")

                        is Result_NoneAPIErrorZ.Result_NoneAPIErrorZ_Err ->
                            Log.d(_LDK, "Funding tx error: ${fundingTx.err}")
                    }
                } catch (e: Exception) {
                    Log.d(_LDK, "FundingGenerationReady error: ${e.message}")
                }
            }
        }

        is Event.OpenChannelRequest -> {
            Log.d(_LDK, "event: OpenChannelRequest")
            val json = JsonBuilder()
                .put("counterparty_node_id", event.counterparty_node_id.toHex())
                .put("temporary_channel_id", event.temporary_channel_id.toHex())
                .put("push_sat", (event.push_msat.toInt() / 1000).toString())
                .put("funding_satoshis", event.funding_satoshis.toString())
                .put("channel_type", event.channel_type.toString())
            json.persist("$ldkDir/events_open_channel_request")
            Ldk.Events.fundingGenerationReady += json.toString()
            Ldk.Channel.temporaryId = event.temporary_channel_id
            Ldk.Channel.counterpartyNodeId = event.counterparty_node_id
            val userChannelId = UInt128(Random.nextLong(0, 100))
            val res = channelManager.accept_inbound_channel(
                event.temporary_channel_id,
                event.counterparty_node_id,
                userChannelId,
            )
            res?.let {
                if (it.is_ok) {
                    Log.d(_LDK, "OpenChannelRequest accepted")
                } else {
                    Log.d(_LDK, "OpenChannelRequest rejected")
                }
            }
        }

        is Event.ChannelClosed -> {
            Log.d(_LDK, "event: ChannelClosed")
            val json = JsonBuilder()
                .put("channel_id", event.channel_id.toHex())
                .put("user_channel_id", event.user_channel_id.toString())

            val reason = event.reason
            if (reason is ClosureReason.CommitmentTxConfirmed) {
                json.put("reason", "CommitmentTxConfirmed")
            }
            if (reason is ClosureReason.CooperativeClosure) {
                json.put("reason", "CooperativeClosure")
            }
            if (reason is ClosureReason.CounterpartyForceClosed) {
                json.put("reason", "CounterpartyForceClosed")
                json.put("text", reason.peer_msg.toString())
            }
            if (reason is ClosureReason.DisconnectedPeer) {
                json.put("reason", typeOf<ClosureReason.DisconnectedPeer>().toString())
            }
            if (reason is ClosureReason.HolderForceClosed) {
                json.put("reason", "HolderForceClosed")
            }
            if (reason is ClosureReason.OutdatedChannelManager) {
                json.put("reason", "OutdatedChannelManager")
            }
            if (reason is ClosureReason.ProcessingError) {
                json.put("reason", "ProcessingError")
                json.put("text", reason.err)
            }
            json.persist("$ldkDir/events_channel_closed")
            Ldk.Events.channelClosed += json.toString()
        }

        is Event.ChannelPending -> {
            Log.d(_LDK, "event: ChannelPending")
            val json = JsonBuilder()
                .put("channel_id", event.channel_id.toHex())
                .put("tx_id", event.funding_txo._txid.toHex())
                .put("user_channel_id", event.user_channel_id.toString())
            json.persist("$ldkDir/events_channel_pending")
        }

        is Event.ChannelReady -> {
            Log.d(_LDK, "event: ChannelReady")
            val json = JsonBuilder()
                .put("channel_id", event.channel_id.toHex())
                .put("user_channel_id", event.user_channel_id.toString())
            json.persist("$ldkDir/events_channel_ready")
        }

        is Event.PaymentSent -> {
            Log.d(_LDK, "event: PaymentSent")
        }

        is Event.PaymentFailed -> {
            Log.d(_LDK, "event: PaymentFailed")
        }

        is Event.PaymentPathFailed -> {
            Log.d(_LDK, "event: PaymentPathFailed: ${event.failure}")
        }

        is Event.PendingHTLCsForwardable -> {
            Log.d(_LDK, "event: PendingHTLCsForwardable")
            channelManager.process_pending_htlc_forwards()
        }

        is Event.SpendableOutputs -> {
            Log.d(_LDK, "event: SpendableOutputs")
            val outputs = event.outputs
            try {
                val address = BitcoinService.shared.newAddress()
                val script = Address(address).scriptPubkey().toBytes().toUByteArray().toByteArray()
                val txOut: Array<TxOut> = arrayOf()
                val res = Ldk.keysManager.inner.spend_spendable_outputs(
                    outputs,
                    txOut,
                    script,
                    1000,
                    null
                )
                if (res != null) {
                    if (res.is_ok) {
                        val tx = (res as Result_TransactionNoneZ.Result_TransactionNoneZ_OK).res
                        val txs: Array<ByteArray> = arrayOf()
                        txs.plus(tx)

                        LdkBroadcaster.broadcast_transactions(txs)
                    }
                }
            } catch (e: Exception) {
                Log.d(_LDK, "PaymentClaimable Error: ${e.message}")
            }
        }

        is Event.PaymentClaimable -> {
            Log.d(_LDK, "event: PaymentClaimable")
            if (event.payment_hash != null) {
                channelManager.claim_funds(event.payment_hash)
            }
        }

        is Event.PaymentClaimed -> {
            Log.d(_LDK, "event ClaimedPayment: ${event.payment_hash}")
        }
    }
}

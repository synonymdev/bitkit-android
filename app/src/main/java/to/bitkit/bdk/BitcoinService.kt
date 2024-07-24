package to.bitkit.bdk

import android.util.Log
import org.bitcoindevkit.AddressIndex
import org.bitcoindevkit.Blockchain
import org.bitcoindevkit.BlockchainConfig
import org.bitcoindevkit.DatabaseConfig
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.DescriptorSecretKey
import org.bitcoindevkit.EsploraConfig
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.Mnemonic
import org.bitcoindevkit.PartiallySignedTransaction
import org.bitcoindevkit.Progress
import org.bitcoindevkit.Script
import org.bitcoindevkit.Transaction
import org.bitcoindevkit.TxBuilder
import org.bitcoindevkit.Wallet
import org.ldk.structs.Result_NoneAPIErrorZ
import org.ldk.structs.Result_ThirtyTwoBytesAPIErrorZ
import org.ldk.structs.UserConfig
import org.ldk.util.UInt128
import to.bitkit.Env
import to.bitkit.REST
import to.bitkit.SEED
import to.bitkit._BDK
import to.bitkit._LDK
import to.bitkit.ext.toByteArray
import to.bitkit.ext.toHex
import to.bitkit.ldk.Ldk

internal class BitcoinService {
    companion object {
        val shared by lazy {
            BitcoinService()
        }
    }

    val wallet by lazy {
        val network = Env.Network.bdk
        val mnemonic = Mnemonic.fromString(SEED)
        val key = DescriptorSecretKey(network, mnemonic, null)

        Wallet(
            descriptor = Descriptor.newBip84(key, KeychainKind.INTERNAL, network),
            changeDescriptor = Descriptor.newBip84(key, KeychainKind.EXTERNAL, network),
            network = network,
            databaseConfig = DatabaseConfig.Memory,
        )
    }
    private val blockchain by lazy {
        Blockchain(
            BlockchainConfig.Esplora(
                EsploraConfig(
                    baseUrl = REST,
                    proxy = null,
                    concurrency = 5u,
                    stopGap = 20u,
                    timeout = null,
                )
            )
        )
    }

    fun sync() {
        wallet.sync(
            blockchain = blockchain,
            progress = object : Progress {
                override fun update(progress: Float, message: String?) {
                    Log.d(_BDK, "Updating wallet: $progress $message")
                }
            }
        )
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    fun buildFundingTx(value: Long, script: ByteArray): Transaction {
        sync()
        val rawOutputScript = script.toUByteArray().asList()
        val outputScript = Script(rawOutputScript)
        val feeRate = 4.0F
        val (psbt, _) = TxBuilder()
            .addRecipient(outputScript, value.toULong())
            .feeRate(feeRate)
            .finish(wallet)
        sign(psbt)
        val rawTx = psbt.extractTx().serialize().toUByteArray().toByteArray()

        Log.d(_BDK, "Raw funding tx: ${rawTx.toHex()}")

        return psbt.extractTx()
    }

    private fun sign(psbt: PartiallySignedTransaction) {
        wallet.sign(psbt, null)
    }

    fun broadcastRawTx(tx: Transaction) {
        blockchain.broadcast(tx)
        Log.d(_BDK, "Broadcasted transaction ID: ${tx.txid()}")
    }

    fun newAddress() = wallet.getAddress(AddressIndex.New).address.asString()

    // region State
    fun balance() = wallet.getBalance()
    fun address() = wallet.getAddress(AddressIndex.LastUnused).address.asString()
}

internal object Channel {
    fun open(pubKey: String) {
        Ldk.Channel.temporaryId = null

        val amount: Long = 100000
        val pushMSat: Long = 0
        val userId = UInt128(42L)

        val userConfig = UserConfig.with_default().apply {
            // set the following to false to open a private channel
            // _channel_handshake_config = ChannelHandshakeConfig.with_default().apply {
            //     _announced_channel = false
            // }
        }

        val result = Ldk.channelManager.create_channel(
            pubKey.toByteArray(),
            amount,
            pushMSat,
            userId,
            userConfig,
        )

        if (result !is Result_ThirtyTwoBytesAPIErrorZ) {
            Log.d(_LDK, "ERROR: failed to open channel with: $pubKey")
        }

        if (result.is_ok) {
            Log.d(_LDK, "EVENT: initiated channel with peer: $pubKey")
        }
    }

    fun close(channelId: String, pubKey: String) {
        val res = Ldk.channelManager.close_channel(
            channelId.toByteArray(),
            pubKey.toByteArray(),
        )

        if (res is Result_NoneAPIErrorZ.Result_NoneAPIErrorZ_Err) {
            Log.d(_LDK, "ERROR: failed to close channel with: $pubKey")
        }

        if (res.is_ok) {
            Log.d(_LDK, "EVENT: initiated channel close with peer: $pubKey")
        }
    }
}

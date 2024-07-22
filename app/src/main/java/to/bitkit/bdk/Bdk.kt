package to.bitkit.bdk

import android.util.Log
import org.bitcoindevkit.AddressIndex
import org.bitcoindevkit.Blockchain
import org.bitcoindevkit.BlockchainConfig
import org.bitcoindevkit.DatabaseConfig
import org.bitcoindevkit.DerivationPath
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
import org.bitcoindevkit.WordCount
import org.ldk.structs.Result_NoneAPIErrorZ
import org.ldk.structs.Result_ThirtyTwoBytesAPIErrorZ
import org.ldk.structs.UserConfig
import org.ldk.util.UInt128
import to.bitkit.NETWORK
import to.bitkit._BDK
import to.bitkit._LDK
import to.bitkit.bdk.Bdk.wallet
import to.bitkit.ext.toByteArray
import to.bitkit.ext.toHex
import to.bitkit.ldk.Ldk
import to.bitkit.ldk.ldkDir
import to.bitkit.ui.REST
import java.io.File

object Bdk {
    lateinit var wallet: Wallet
    private val blockchain = createBlockchain()

    init {
        initWallet()
    }

    private fun initWallet() {
        val mnemonic = loadMnemonic()
        val key = DescriptorSecretKey(NETWORK, Mnemonic.fromString(mnemonic), null)

        wallet = Wallet(
            Descriptor.newBip84(key, KeychainKind.INTERNAL, NETWORK),
            Descriptor.newBip84(key, KeychainKind.EXTERNAL, NETWORK),
            NETWORK,
            DatabaseConfig.Memory,
        )

        Log.d(_BDK, "Created/restored wallet with mnemonic $mnemonic")
    }

    fun sync() {
        wallet.sync(
            blockchain = blockchain,
            progress = object : Progress {
                override fun update(progress: Float, message: String?) {
                    Log.d(_BDK, "updating wallet $progress $message")
                }
            }
        )
    }

    fun getHeight(): UInt {
        try {
            return blockchain.getHeight()
        } catch (ex: Exception) {
            throw Error("Esplora server is not running.", ex)
        }
    }

    fun getBlockHash(height: UInt): String {
        try {
            return blockchain.getBlockHash(height)
        } catch (ex: Exception) {
            throw Error("Esplora server is not running.", ex)
        }
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    fun getLdkEntropy(): ByteArray {
        val mnemonic = loadMnemonic()
        val key = DescriptorSecretKey(
            network = NETWORK,
            mnemonic = Mnemonic.fromString(mnemonic),
            password = null,
        )
        val derivationPath = DerivationPath("m/535h")
        val child = key.derive(derivationPath)
        val entropy = child.secretBytes().toUByteArray().toByteArray()

        Log.d(_LDK, "LDK entropy: ${entropy.toHex()}")
        return entropy
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
        val blockchain = createBlockchain()
        blockchain.broadcast(tx)

        Log.d(_BDK, "Broadcasted transaction ID: ${tx.txid()}")
    }

    private fun createBlockchain(): Blockchain {
        return Blockchain(
            BlockchainConfig.Esplora(
                EsploraConfig(REST, null, 5u, 20u, null)
            )
        )
    }

    private fun loadMnemonic(): String {
        return try {
            mnemonicPhrase()
        } catch (e: Throwable) {
            // if mnemonic doesn't exist, generate one and save it
            Log.d(_BDK, "No mnemonic backup, we'll create a new wallet")
            val mnemonic = Mnemonic(WordCount.WORDS12)
            mnemonicFile.writeText(mnemonic.asString())
            mnemonic.asString()
        }
    }
}

private val mnemonicFile = File("$ldkDir/mnemonic.txt")

internal fun mnemonicPhrase(): String {
    return mnemonicFile.readText()
}

internal fun btcAddress(): String {
    return wallet.getAddress(AddressIndex.LastUnused).address.asString()
}

internal fun newAddress(): String {
    val new = wallet.getAddress(AddressIndex.New).address.asString()
    Log.d(_BDK, "New bitcoin address: $new")
    return new
}

internal fun btcBalance(): String {
    val balance = wallet.getBalance()
    Log.d(_BDK, "BTC balance: $balance")
    return "${balance.total}"
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
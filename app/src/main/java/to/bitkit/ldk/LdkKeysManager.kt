package to.bitkit.ldk

import org.bitcoindevkit.AddressIndex
import org.bitcoindevkit.Payload
import org.bitcoindevkit.Wallet
import org.ldk.structs.KeysManager
import org.ldk.structs.Option_u32Z
import org.ldk.structs.Result_CVec_u8ZNoneZ
import org.ldk.structs.Result_ShutdownScriptInvalidShutdownScriptZ
import org.ldk.structs.Result_ShutdownScriptInvalidShutdownScriptZ.Result_ShutdownScriptInvalidShutdownScriptZ_OK
import org.ldk.structs.Result_ShutdownScriptNoneZ
import org.ldk.structs.Result_TransactionNoneZ
import org.ldk.structs.Result_WriteableEcdsaChannelSignerDecodeErrorZ
import org.ldk.structs.ShutdownScript
import org.ldk.structs.SignerProvider
import org.ldk.structs.SpendableOutputDescriptor
import org.ldk.structs.TxOut
import org.ldk.structs.WriteableEcdsaChannelSigner
import org.ldk.util.UInt128
import org.ldk.util.WitnessVersion
import to.bitkit.ext.convertToByteArray

@Suppress("unused")
class LdkKeysManager(
    seed: ByteArray,
    startTimeSecs: Long,
    startTimeNano: Int,
    var wallet: Wallet,
) {
    var inner: KeysManager = KeysManager.of(seed, startTimeSecs, startTimeNano)
    var signerProvider = LdkSignerProvider()

    fun spendSpendableOutputs(
        descriptors: Array<SpendableOutputDescriptor>,
        outputs: Array<TxOut>,
        changeDestinationScript: ByteArray,
        feerateSatPer1000Weight: Int,
        locktime: Option_u32Z,
    ): Result_TransactionNoneZ {
        return inner.spend_spendable_outputs(
            descriptors,
            outputs,
            changeDestinationScript,
            feerateSatPer1000Weight,
            locktime,
        )
    }

    inner class LdkSignerProvider : SignerProvider.SignerProviderInterface {
        override fun generate_channel_keys_id(p0: Boolean, p1: Long, p2: UInt128?): ByteArray {
            return inner.as_SignerProvider().generate_channel_keys_id(p0, p1, p2)
        }

        override fun derive_channel_signer(p0: Long, p1: ByteArray?): WriteableEcdsaChannelSigner {
            return inner.as_SignerProvider().derive_channel_signer(p0, p1)
        }

        override fun read_chan_signer(p0: ByteArray?): Result_WriteableEcdsaChannelSignerDecodeErrorZ {
            return inner.as_SignerProvider().read_chan_signer(p0)
        }

        /**
         * Returns the destination and shutdown scripts derived by the BDK wallet.
         */
        override fun get_destination_script(): Result_CVec_u8ZNoneZ {
            val address = wallet.getAddress(AddressIndex.New).address
            val res = Result_CVec_u8ZNoneZ.ok(convertToByteArray(address.scriptPubkey()))
            if (res.is_ok) {
                return res
            }
            return Result_CVec_u8ZNoneZ.err()
        }

        @OptIn(ExperimentalUnsignedTypes::class)
        override fun get_shutdown_scriptpubkey(): Result_ShutdownScriptNoneZ {
            val address = wallet.getAddress(AddressIndex.New).address

            return when (val payload = address.payload()) {
                is Payload.WitnessProgram -> {
                    val result: Result_ShutdownScriptInvalidShutdownScriptZ =
                        ShutdownScript.new_witness_program(
                            WitnessVersion(payload.version.name.toByte()),
                            payload.program.toUByteArray().toByteArray()
                        )
                    Result_ShutdownScriptNoneZ.ok((result as Result_ShutdownScriptInvalidShutdownScriptZ_OK).res)
                }

                else -> {
                    Result_ShutdownScriptNoneZ.err()
                }
            }
        }
    }
}


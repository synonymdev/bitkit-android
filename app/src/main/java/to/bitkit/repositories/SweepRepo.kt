package to.bitkit.repositories

import com.synonym.bitkitcore.FeeRates
import com.synonym.bitkitcore.broadcastSweepTransaction
import com.synonym.bitkitcore.checkSweepableBalances
import com.synonym.bitkitcore.prepareSweepTransaction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import to.bitkit.async.ServiceQueue
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.models.toCoreNetwork
import to.bitkit.services.CoreService
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import to.bitkit.viewmodels.SweepResult
import to.bitkit.viewmodels.SweepTransactionPreview
import to.bitkit.viewmodels.SweepableBalances
import javax.inject.Inject
import javax.inject.Singleton
import com.synonym.bitkitcore.SweepResult as BitkitCoreSweepResult
import com.synonym.bitkitcore.SweepTransactionPreview as BitkitCoreSweepTransactionPreview
import com.synonym.bitkitcore.SweepableBalances as BitkitCoreSweepableBalances

@Singleton
class SweepRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val keychain: Keychain,
    private val coreService: CoreService,
) {
    suspend fun checkSweepableBalances(): Result<SweepableBalances> = withContext(bgDispatcher) {
        runCatching {
            val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)
                ?: throw ServiceError.MnemonicNotFound()
            val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)

            Logger.debug("Checking sweepable balances...", context = TAG)

            val balances = ServiceQueue.CORE.background {
                checkSweepableBalances(
                    mnemonicPhrase = mnemonic,
                    network = Env.network.toCoreNetwork(),
                    bip39Passphrase = passphrase,
                    electrumUrl = Env.electrumServerUrl,
                )
            }

            balances.toSweepableBalances()
        }
    }

    suspend fun prepareSweepTransaction(
        destinationAddress: String,
        feeRateSatsPerVbyte: UInt,
    ): Result<SweepTransactionPreview> = withContext(bgDispatcher) {
        runCatching {
            val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)
                ?: throw ServiceError.MnemonicNotFound()
            val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)

            Logger.debug("Preparing sweep transaction...", context = TAG)

            val preview = ServiceQueue.CORE.background {
                prepareSweepTransaction(
                    mnemonicPhrase = mnemonic,
                    network = Env.network.toCoreNetwork(),
                    bip39Passphrase = passphrase,
                    electrumUrl = Env.electrumServerUrl,
                    destinationAddress = destinationAddress,
                    feeRateSatsPerVbyte = feeRateSatsPerVbyte,
                )
            }

            preview.toSweepTransactionPreview()
        }
    }

    suspend fun broadcastSweepTransaction(psbt: String): Result<SweepResult> = withContext(bgDispatcher) {
        runCatching {
            val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)
                ?: throw ServiceError.MnemonicNotFound()
            val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)

            Logger.debug("Broadcasting sweep transaction...", context = TAG)

            val result = ServiceQueue.CORE.background {
                broadcastSweepTransaction(
                    psbt = psbt,
                    mnemonicPhrase = mnemonic,
                    network = Env.network.toCoreNetwork(),
                    bip39Passphrase = passphrase,
                    electrumUrl = Env.electrumServerUrl,
                )
            }

            result.toSweepResult()
        }
    }

    suspend fun getFeeRates(): Result<FeeRates> = coreService.blocktank.getFees()

    suspend fun hasSweepableFunds(): Result<Boolean> = checkSweepableBalances().map { balances ->
        val hasFunds = balances.totalBalance > 0u
        if (hasFunds) {
            Logger.info("Found ${balances.totalBalance} sats to sweep", context = TAG)
        } else {
            Logger.debug("No sweepable funds found", context = TAG)
        }
        hasFunds
    }

    companion object {
        private const val TAG = "SweepRepo"
    }
}

private fun BitkitCoreSweepableBalances.toSweepableBalances() = SweepableBalances(
    legacyBalance = legacyBalance,
    legacyUtxosCount = legacyUtxosCount,
    p2shBalance = p2shBalance,
    p2shUtxosCount = p2shUtxosCount,
    taprootBalance = taprootBalance,
    taprootUtxosCount = taprootUtxosCount,
)

private fun BitkitCoreSweepTransactionPreview.toSweepTransactionPreview() = SweepTransactionPreview(
    psbt = psbt,
    estimatedFee = estimatedFee,
    amountAfterFees = amountAfterFees,
    estimatedVsize = estimatedVsize,
)

private fun BitkitCoreSweepResult.toSweepResult() = SweepResult(
    txid = txid,
    amountSwept = amountSwept,
)

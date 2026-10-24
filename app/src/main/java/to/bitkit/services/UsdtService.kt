package to.bitkit.services

import com.synonym.bitkitcore.UsdtBackup
import com.synonym.bitkitcore.UsdtDepositClient
import com.synonym.bitkitcore.UsdtDepositNetwork
import com.synonym.bitkitcore.UsdtException
import com.synonym.bitkitcore.UsdtPaymentProofBinding
import com.synonym.bitkitcore.UsdtQuote
import com.synonym.bitkitcore.UsdtWallet
import com.synonym.bitkitcore.usdtAddress
import com.synonym.bitkitcore.usdtParseAmount
import to.bitkit.data.keychain.Keychain
import to.bitkit.env.Env
import to.bitkit.repositories.BackupRepo
import to.bitkit.utils.Logger
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.DecimalFormatSymbols
import java.util.Locale
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.time.Instant

@Singleton
class UsdtService @Inject constructor(
    private val keychain: Keychain,
    private val backupRepo: Provider<BackupRepo>,
) {
    private var wallet: UsdtWallet? = null
    private var credentialFingerprint: ByteArray? = null
    private var addressFingerprint: ByteArray? = null
    private var cachedPaymentAddress: String? = null

    val isRecoveryRestorePending: Boolean
        get() = keychain.exists(Keychain.Key.USDT_PENDING_BACKUP_RESTORE.name)

    @get:Synchronized
    val paymentAddress: String
        get() {
            val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)
                ?: throw UsdtException.InvalidCredentials()
            val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)
            val fingerprint = MessageDigest.getInstance("SHA-256")
                .digest("${mnemonic.length}:$mnemonic${passphrase.orEmpty()}".encodeToByteArray())
            if (addressFingerprint?.contentEquals(fingerprint) == true) cachedPaymentAddress?.let { return it }
            cachedPaymentAddress = null
            addressFingerprint = null
            return usdtAddress(mnemonic, passphrase).also {
                cachedPaymentAddress = it
                addressFingerprint = fingerprint
            }
        }

    @Synchronized
    fun wallet(requiresNetwork: Boolean = true): UsdtWallet {
        if (requiresNetwork && isRecoveryRestorePending) throw UsdtException.BackupUnavailable()
        if (requiresNetwork && !Env.isUsdtEnabled) throw UsdtException.NotConfigured()
        val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name) ?: throw UsdtException.InvalidCredentials()
        val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest("${mnemonic.length}:$mnemonic${passphrase.orEmpty()}".encodeToByteArray())
        if (credentialFingerprint?.contentEquals(fingerprint) != true) {
            wallet?.close()
            wallet = null
        }
        wallet?.let { return it }
        val address = usdtAddress(mnemonic, passphrase)
        return UsdtWallet(
            address = address,
            storagePath = File(Env.bitkitCoreStoragePath(0), "usdt-$address.sqlite").path,
            // Local backup access needs a wallet even when network configuration is disabled.
            rpcUrl = Env.usdtRpcUrl ?: "http://127.0.0.1:1",
            bundlerUrl = Env.usdtBundlerUrl.ifEmpty { "http://127.0.0.1:1" },
            bridgeUrl = Env.usdtBridgesUrl,
            backup = object : UsdtBackup {
                override suspend fun persist(snapshot: String) {
                    backupRepo.get().persistUsdtRecoveryBackup(snapshot).getOrElse {
                        Logger.error(
                            "Failed to persist USDT recovery backup with '${it.javaClass.simpleName}'",
                            context = TAG,
                        )
                        throw UsdtException.BackupUnavailable()
                    }
                }
            },
        ).also {
            wallet = it
            credentialFingerprint = fingerprint
        }
    }

    suspend fun depositNetworks(): List<UsdtDepositNetwork> {
        if (Env.usdtDepositsUrl == null) return emptyList()
        return withDeposits { client, _, _ -> client.networks() }
    }

    suspend fun prepareDeposit(network: UsdtDepositNetwork, amount: String, locale: Locale = Locale.getDefault()) =
        withDeposits { client, mnemonic, passphrase ->
            val value = usdtParseAmount(amount.replace(DecimalFormatSymbols.getInstance(locale).decimalSeparator, '.'))
            client.receive(network, value, mnemonic, passphrase)
        }

    suspend fun depositHistory(offset: UInt) = withDeposits { client, mnemonic, passphrase ->
        client.history(offset, mnemonic, passphrase)
    }

    suspend fun depositDetail(id: String, offset: UInt) = withDeposits { client, mnemonic, passphrase ->
        client.detail(id, offset, mnemonic, passphrase)
    }

    suspend fun refundDeposit(id: String, offset: UInt, address: String, network: UsdtDepositNetwork) =
        withDeposits { client, mnemonic, passphrase ->
            client.requestRefund(id, offset, address, network, mnemonic, passphrase)
        }

    private suspend fun <T> withDeposits(block: suspend (UsdtDepositClient, String, String?) -> T): T {
        val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name) ?: throw UsdtException.InvalidCredentials()
        val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)
        val owner = usdtAddress(mnemonic, passphrase)
        val client = UsdtDepositClient(owner, requireNotNull(Env.usdtDepositsUrl))
        return try { block(client, mnemonic, passphrase) } finally { client.close() }
    }

    val hasStoredWallet: Boolean
        get() {
            val directory = File(Env.bitkitCoreStoragePath(0))
            if (!directory.exists()) return false
            val files = directory.listFiles() ?: throw IOException("Cannot read USDT storage")
            return files.any { it.name.startsWith("usdt-") && it.extension == "sqlite" }
        }

    @Synchronized
    fun backupSnapshot(): String? = if (hasStoredWallet) wallet(requiresNetwork = false).exportBackup() else null

    @Synchronized
    fun wipe() {
        wallet?.close()
        wallet = null
        credentialFingerprint = null
        addressFingerprint = null
        cachedPaymentAddress = null
        val directory = File(Env.bitkitCoreStoragePath(0))
        if (!directory.exists()) return
        val files = directory.listFiles() ?: throw IOException("Cannot read USDT storage")
        files.filter { it.name.startsWith("usdt-") && it.extension in setOf("sqlite", "sqlite-wal", "sqlite-shm") }
            .forEach { if (!it.delete()) throw IOException("Cannot delete USDT storage") }
    }

    suspend fun paymentProof(quoteId: String, binding: UsdtPaymentProofBinding) = wallet().createPaymentProof(
        quoteId,
        binding,
        keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name) ?: throw UsdtException.InvalidCredentials(),
        keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)
    )

    suspend fun send(quote: UsdtQuote, submissionDeadline: Instant? = null) = wallet().sendWithDeadline(
        quote.id,
        keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name) ?: throw UsdtException.InvalidCredentials(),
        keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name),
        submissionDeadline?.toString(),
    )

    companion object {
        private const val TAG = "UsdtService"
    }
}

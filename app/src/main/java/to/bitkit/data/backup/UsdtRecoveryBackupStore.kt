package to.bitkit.data.backup

import com.synonym.bitkitcore.UsdtException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import to.bitkit.di.json
import to.bitkit.models.PaykitUsdtStateBackup
import java.security.MessageDigest
import java.util.Locale

@Serializable
data class UsdtRecoveryBackupV1(
    val version: Int,
    val usdtWallet: String,
    val paykitUsdtState: PaykitUsdtStateBackup,
)

/**
 * Immutable encrypted VSS records preserve concurrent writers' signed operations. Retrying a lost
 * acknowledgement writes the same content-addressed record. Pruning needs a compaction protocol that
 * proves every writer's recovery data remains acknowledged; an unconditional VSS replacement cannot.
 */
class UsdtRecoveryBackupStore(
    private val put: suspend (String, ByteArray) -> Unit,
    private val keys: suspend () -> List<String>,
    private val get: suspend (String) -> ByteArray?,
) {
    constructor(client: VssBackupClient) : this(
        put = { key, data ->
            client.setup().getOrThrow()
            client.putObject(key, data).getOrThrow()
        },
        keys = {
            client.setup().getOrThrow()
            client.listKeys().getOrThrow().map { it.key }
        },
        get = { key -> client.getObject(key).getOrThrow()?.value },
    )

    suspend fun persist(snapshot: String, paymentState: PaykitUsdtStateBackup) {
        val data = json.encodeToString(UsdtRecoveryBackupV1(1, snapshot, paymentState)).encodeToByteArray()
        put(recoveryKey(data), data)
    }

    suspend fun snapshots(): List<UsdtRecoveryBackupV1> = keys().filter { it.startsWith(PREFIX) }.sorted().map { key ->
        currentCoroutineContext().ensureActive()
        if (!key.startsWith(VERSION_PREFIX)) throw UsdtException.InvalidBackup()
        val data = get(key) ?: throw UsdtException.InvalidBackup()
        if (recoveryKey(data) != key) throw UsdtException.InvalidBackup()
        json.decodeFromString<UsdtRecoveryBackupV1>(data.decodeToString()).also {
            if (it.version != 1) throw UsdtException.InvalidBackup()
        }
    }

    suspend fun restore(
        legacyWallet: String?,
        legacyPaymentState: PaykitUsdtStateBackup?,
        restoreWallet: suspend (String) -> Unit,
        restorePayments: suspend (PaykitUsdtStateBackup) -> Unit,
    ) {
        val records = snapshots()
        legacyWallet?.let { restoreWallet(it) }
        legacyPaymentState?.let { restorePayments(it) }
        for (record in records) {
            restoreWallet(record.usdtWallet)
            restorePayments(record.paykitUsdtState)
        }
    }

    private fun recoveryKey(data: ByteArray) = VERSION_PREFIX + MessageDigest.getInstance("SHA-256").digest(data)
        .joinToString("") { "%02x".format(Locale.ROOT, it) }

    companion object {
        /** Filter decrypted names locally: VSS obfuscates whole keys, not their path segments. */
        const val PREFIX = "USDT_RECOVERY_"
        private const val VERSION_PREFIX = "${PREFIX}V1_"
    }
}

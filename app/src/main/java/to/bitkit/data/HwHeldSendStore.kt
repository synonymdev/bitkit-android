package to.bitkit.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import to.bitkit.di.IoDispatcher
import to.bitkit.di.json
import to.bitkit.utils.AppError
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Signed hardware sends whose broadcast may have gone out, in their own file. Unlike the other hardware wallet data, a
 * file this cannot read fails every read and write instead of starting over, and a record it cannot read is kept as it
 * is and refuses sends from its wallet, so a hold is never dropped silently. Only [clearUnreadable] discards them.
 */
@Singleton
class HwHeldSendStore internal constructor(
    produceFile: () -> File,
    private val ioDispatcher: CoroutineDispatcher,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this({ context.dataStoreFile(FILE_NAME) }, ioDispatcher)

    private companion object {
        const val FILE_NAME = "hw_held_sends.json"
    }

    @Volatile
    private var isResetRequested = false

    private val store: DataStore<HwHeldSendData> = DataStoreFactory.create(
        serializer = HwHeldSendDataSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { if (isResetRequested) HwHeldSendData() else throw it },
        produceFile = produceFile,
    )

    /** Fails with [HwHeldSendsUnreadableError] when the file cannot be read. */
    suspend fun load(): HwHeldSendData = withContext(ioDispatcher) {
        readOrFail { store.data.first() }
    }

    /** Stores [send], replacing a held send of the same transaction. */
    suspend fun holdSend(send: HeldHwSend) = withContext(ioDispatcher) {
        readOrFail {
            store.updateData { data ->
                data.copy(heldSends = data.heldSends.filterNot { it.txId.equals(send.txId, ignoreCase = true) } + send)
            }
        }
        Unit
    }

    suspend fun releaseHeldSends(txIds: Set<String>) = withContext(ioDispatcher) {
        if (txIds.isEmpty()) return@withContext
        val released = txIds.mapTo(mutableSetOf()) { it.lowercase() }
        readOrFail {
            store.updateData { data ->
                data.copy(heldSends = data.heldSends.filterNot { it.txId.lowercase() in released })
            }
        }
        Unit
    }

    /**
     * Discards the records that cannot be read for [walletId] and those whose wallet cannot be read, or the whole file
     * when it cannot be read at all. Whatever they held may then be signed again.
     */
    suspend fun clearUnreadable(walletId: String) = withContext(ioDispatcher) {
        isResetRequested = true
        try {
            store.updateData { data ->
                data.copy(unreadable = data.unreadable.filterNot { it.mayBelongTo(walletId) })
            }
        } finally {
            isResetRequested = false
        }
        Unit
    }

    suspend fun reset() = withContext(ioDispatcher) {
        isResetRequested = true
        try {
            store.updateData { HwHeldSendData() }
        } finally {
            isResetRequested = false
        }
        Unit
    }

    private suspend fun <T> readOrFail(block: suspend () -> T): T = try {
        block()
    } catch (error: CorruptionException) {
        throw HwHeldSendsUnreadableError(walletId = null, cause = error)
    }
}

data class HwHeldSendData(
    val heldSends: List<HeldHwSend> = emptyList(),
    /** Records this version could not read, kept as they are so they keep refusing sends from their wallet. */
    val unreadable: List<JsonElement> = emptyList(),
) {
    /** Whether a record this cannot read may belong to [walletId], including one whose wallet cannot be read. */
    fun hasUnreadableFor(walletId: String): Boolean = unreadable.any { it.mayBelongTo(walletId) }

    /**
     * The error refusing [walletId]'s sends, with what can still be read of its unreadable records; null when none
     * may belong to it.
     */
    fun unreadableErrorFor(walletId: String): HwHeldSendsUnreadableError? {
        val records = unreadable.filter { it.mayBelongTo(walletId) }
        if (records.isEmpty()) return null
        return HwHeldSendsUnreadableError(
            walletId = walletId,
            records = records.map { it.toUnreadableHeldSend() },
            clearsAllWallets = records.any { it.walletIdOrNull() == null },
        )
    }
}

/** What can still be read of a held send record that cannot be read whole. */
data class UnreadableHeldSend(
    val amountSats: ULong? = null,
    val address: String? = null,
    val txId: String? = null,
    val heldAt: Long? = null,
)

/**
 * A signed hardware send whose broadcast may have reached the network, so the same payment is never signed again
 * beside it: it is only broadcast again as these bytes. Fields added later need defaults, so older records still read.
 */
@Serializable
data class HeldHwSend(
    val walletId: String,
    val txId: String,
    val address: String,
    val amountSats: ULong,
    val satsPerVByte: ULong,
    val tags: List<String> = emptyList(),
    val serializedTx: String,
    val miningFeeSats: ULong,
    val feeRate: ULong,
    val totalSpent: ULong,
    /** When the send was last held, in seconds since the epoch. */
    val heldAt: Long? = null,
) {
    fun isPaymentOf(walletId: String, address: String, amountSats: ULong): Boolean =
        this.walletId == walletId && this.address.equals(address, ignoreCase = true) && this.amountSats == amountSats
}

private fun JsonElement.mayBelongTo(walletId: String): Boolean = walletIdOrNull().let { it == null || it == walletId }

private fun JsonElement.walletIdOrNull(): String? = stringField("walletId")

private fun JsonElement.stringField(name: String): String? =
    ((this as? JsonObject)?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonElement.numberField(name: String): String? =
    ((this as? JsonObject)?.get(name) as? JsonPrimitive)?.contentOrNull

private fun JsonElement.toUnreadableHeldSend() = UnreadableHeldSend(
    amountSats = numberField("amountSats")?.toULongOrNull(),
    address = stringField("address"),
    txId = stringField("txId"),
    heldAt = numberField("heldAt")?.toLongOrNull(),
)

internal object HwHeldSendDataSerializer : Serializer<HwHeldSendData> {
    private const val VERSION = 1

    override val defaultValue = HwHeldSendData()

    override suspend fun readFrom(input: InputStream): HwHeldSendData = try {
        val root = json.parseToJsonElement(input.readBytes().decodeToString()).jsonObject
        val held = root["heldSends"] as? JsonArray ?: throw SerializationException("No held sends")
        // Records an earlier version could not read are tried again, as a newer version may read them
        val records = held + (root["unreadable"] as? JsonArray).orEmpty()
        val (readable, unreadable) = records.map { record ->
            record to runCatching { json.decodeFromJsonElement<HeldHwSend>(record) }.getOrNull()
        }.partition { it.second != null }
        HwHeldSendData(
            heldSends = readable.mapNotNull { it.second },
            unreadable = unreadable.map { it.first },
        )
    } catch (error: SerializationException) {
        throw CorruptionException("Failed to read held hardware sends", error)
    } catch (error: IllegalArgumentException) {
        throw CorruptionException("Failed to read held hardware sends", error)
    }

    override suspend fun writeTo(t: HwHeldSendData, output: OutputStream) {
        val root = JsonObject(
            mapOf(
                "version" to JsonPrimitive(VERSION),
                "heldSends" to JsonArray(t.heldSends.map { json.encodeToJsonElement(it) }),
                "unreadable" to JsonArray(t.unreadable),
            )
        )
        output.write(json.encodeToString(JsonElement.serializer(), root).encodeToByteArray())
    }
}

/**
 * The held hardware sends could not be read, so no send of [walletId] is signed, or none at all when
 * [clearsAllWallets]. [records] holds what can still be read of the records concerned.
 */
class HwHeldSendsUnreadableError(
    val walletId: String?,
    val records: List<UnreadableHeldSend> = emptyList(),
    val clearsAllWallets: Boolean = walletId == null,
    cause: Throwable? = null,
) : AppError("Held hardware sends could not be read", cause)

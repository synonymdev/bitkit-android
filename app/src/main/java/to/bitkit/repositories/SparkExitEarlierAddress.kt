package to.bitkit.repositories

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.lightningdevkit.ldknode.Network
import to.bitkit.models.SparkExitEarlierAddressBackupV1
import to.bitkit.models.safe
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.days

/**
 * A funding address an earlier exit left coins at, or may still receive a payment at, kept past its record so a later
 * exit can replace that record: the address and the derivation path that spends it.
 */
@Serializable
data class SparkExitEarlierAddress(
    val address: String,
    val path: String,
    val kind: Kind,
    /** What the address holds, when known. */
    val sats: ULong? = null,
    val atMillis: Long,
) {
    /** [backupName] is how both apps' metadata backups name the kind. */
    enum class Kind(val backupName: String) {
        /** Fee funding left at the funding address for good: change too small to send, or a refused sweep's. */
        KEPT_CHANGE("keptChange"),

        /** The funding address of a cancelled exit that was forgotten: a late payment can still land there. */
        CANCELLED_FUNDING("cancelledFunding"),
    }

    fun toBackup() = SparkExitEarlierAddressBackupV1(address, path, kind.backupName, atMillis)

    /**
     * Read in the foreground for what it holds: kept change always, so change spent by hand shows as spent, and a
     * cancelled exit's address for about four weeks, for a late payment.
     */
    fun isWatched(nowMillis: Long): Boolean =
        kind == Kind.KEPT_CHANGE || nowMillis - atMillis < WATCH_PERIOD.inWholeMilliseconds

    companion object {
        val WATCH_PERIOD = 28.days

        /** The backed-up entry, with what it holds read again later; null for a kind this app does not know. */
        fun fromBackup(backup: SparkExitEarlierAddressBackupV1): SparkExitEarlierAddress? {
            val kind = Kind.entries.firstOrNull { it.backupName == backup.kind } ?: return null
            return SparkExitEarlierAddress(backup.address, backup.path, kind, atMillis = backup.atMillis)
        }

        /** The leftover an ended exit kept at its funding address, or null when it kept none. */
        fun keptChange(record: SparkExitRecord, network: Network, atMillis: Long): SparkExitEarlierAddress? {
            if (!record.isLeftoverKept) return null
            val address = record.funding.firstOrNull()?.address ?: return null
            return SparkExitEarlierAddress(
                address = address,
                path = SparkExitFundingKey.path(record.fundingIndex.toInt(), network),
                kind = Kind.KEPT_CHANGE,
                sats = record.leftover.fold(0uL) { sum, leftover -> sum.safe() + leftover.valueSats.safe() },
                atMillis = atMillis,
            )
        }

        /** The funding address of a cancelled exit being forgotten. */
        fun cancelledFunding(record: SparkExitRecord, network: Network, atMillis: Long): SparkExitEarlierAddress? {
            val address = record.funding.firstOrNull()?.address ?: return null
            return SparkExitEarlierAddress(
                address = address,
                path = SparkExitFundingKey.path(record.fundingIndex.toInt(), network),
                kind = Kind.CANCELLED_FUNDING,
                atMillis = atMillis,
            )
        }
    }
}

/** The earlier exits' addresses, in their own file next to the exit record. A wipe deletes it. */
class SparkExitEarlierAddressStore(private val file: File) {
    companion object {
        /** Counts the writes of every wallet's list, so the metadata backup that carries them runs again. */
        val writes = MutableStateFlow(0L)

        /** One lock per file, shared by every store over it, so no writer saves a list read before another's write. */
        private val locks = ConcurrentHashMap<String, Any>()
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = locks.getOrPut(file.canonicalPath) { Any() }

    fun load(): List<SparkExitEarlierAddress> {
        if (!file.exists()) return emptyList()
        return json.decodeFromString(file.readText())
    }

    /**
     * Adds [entry], once per address: a known address keeps its entry, with [entry]'s amount when it has one. Kept
     * change at a cancelled exit's address, which a later exit reused, replaces that entry, so it is read for good.
     */
    fun add(entry: SparkExitEarlierAddress): List<SparkExitEarlierAddress> {
        synchronized(lock) {
            val known = runCatching { load() }.getOrDefault(emptyList())
            val existing = known.firstOrNull { it.address == entry.address }
            val updated = when {
                existing == null -> known + entry
                entry.kind == SparkExitEarlierAddress.Kind.KEPT_CHANGE && existing.kind != entry.kind ->
                    known.map { if (it.address == entry.address) entry.copy(sats = entry.sats ?: it.sats) else it }
                entry.sats == null || entry.sats == existing.sats -> return known
                else -> known.map { if (it.address == entry.address) it.copy(sats = entry.sats) else it }
            }
            save(updated)
            return updated
        }
    }

    /** Sets what [address] holds, writing only when it changed. */
    fun updateSats(address: String, sats: ULong): List<SparkExitEarlierAddress> {
        synchronized(lock) {
            val known = runCatching { load() }.getOrDefault(emptyList())
            if (known.none { it.address == address && it.sats != sats }) return known
            val updated = known.map { if (it.address == address) it.copy(sats = sats) else it }
            save(updated)
            return updated
        }
    }

    /** Drops [address], such as one a new exit funds again. */
    fun remove(address: String): List<SparkExitEarlierAddress> {
        synchronized(lock) {
            val known = runCatching { load() }.getOrDefault(emptyList())
            if (known.none { it.address == address }) return known
            val updated = known.filter { it.address != address }
            save(updated)
            return updated
        }
    }

    fun delete() = synchronized(lock) {
        check(!file.exists() || file.delete()) { "Failed to delete '${file.name}'" }
    }

    private fun save(entries: List<SparkExitEarlierAddress>) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        writeDurably(temp, json.encodeToString(entries))
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        writes.update { it + 1 }
    }
}

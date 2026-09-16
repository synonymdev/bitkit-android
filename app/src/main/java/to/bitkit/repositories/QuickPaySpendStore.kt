package to.bitkit.repositories

import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
import to.bitkit.models.QuickPayLedger
import to.bitkit.models.QuickPayLedgerRecord
import to.bitkit.models.QuickPayRecordPhase
import java.util.UUID
import kotlin.time.Clock

internal class QuickPaySpendStore(
    private val cacheStore: CacheStore,
    private val clock: Clock,
) {
    companion object {
        const val LEDGER_VERSION = 1
    }

    suspend fun snapshot(): SpendSnapshot {
        val data = cacheStore.data.first()
        val (ledger, supported) = data.resolvedLedger()
        val dayKey = currentDayKey()
        if (!supported) return SpendSnapshot(0L, supported = false, ledger = ledger)
        val spend = spendFor(ledger, dayKey)
        return SpendSnapshot(spend.spentCents, supported = true, ledger = ledger)
    }

    suspend fun matching(hash: String): QuickPayLedgerRecord? {
        val (ledger, supported) = cacheStore.data.first().resolvedLedger()
        if (!supported) return null
        return ledger.recordMatching(hash)
    }

    suspend fun reserve(
        paymentHash: String,
        amountCents: Long,
        capCents: Long,
        keepHashes: Set<String> = emptySet(),
    ): QuickPayLedgerRecord? {
        var reserved: QuickPayLedgerRecord? = null
        val wrote = writeLedger { ledger, dayKey ->
            if (ledger.recordMatching(paymentHash) != null) return@writeLedger ledger
            val spend = spendFor(ledger, dayKey)
            if (spend.spentCents > Long.MAX_VALUE - amountCents) return@writeLedger ledger
            val total = spend.spentCents + amountCents
            if (total > capCents) return@writeLedger ledger
            val next = ledger.pruned(spend.dayKey, keepHashes)
            val record = QuickPayLedgerRecord(
                id = UUID.randomUUID().toString(),
                amountCents = amountCents,
                dayKey = spend.dayKey,
                invoicePaymentHash = paymentHash,
                paymentId = null,
                phase = QuickPayRecordPhase.SUBMITTING,
            )
            reserved = record
            next.copy(
                dayKey = spend.dayKey,
                spentCents = total,
                records = next.records + record,
            )
        }
        return if (!wrote) null else reserved
    }

    suspend fun release(paymentHash: String) {
        writeLedger { ledger, _ -> ledger.releaseRecord(paymentHash) }
    }

    suspend fun drop(paymentHash: String) {
        writeLedger { ledger, _ ->
            val index = ledger.recordIndex(paymentHash) ?: return@writeLedger ledger
            ledger.copy(records = ledger.records.toMutableList().also { it.removeAt(index) })
        }
    }

    suspend fun markSubmitted(invoiceHash: String, paymentId: String) {
        writeLedger { ledger, _ ->
            val index = ledger.recordIndex(invoiceHash) ?: return@writeLedger ledger
            val record = ledger.records[index]
            ledger.copy(
                records = ledger.records.toMutableList().also {
                    it[index] = record.copy(
                        paymentId = paymentId,
                        phase = QuickPayRecordPhase.SUBMITTED,
                    )
                },
            )
        }
    }

    suspend fun settle(keys: List<String>, success: Boolean) {
        writeLedger { current, _ ->
            val i = keys.firstNotNullOfOrNull { current.recordIndex(it) } ?: return@writeLedger current
            val found = current.records[i]
            val remaining = current.records.toMutableList().also { it.removeAt(i) }
            val spent = if (!success && found.dayKey == current.dayKey) {
                (current.spentCents - found.amountCents).coerceAtLeast(0L)
            } else {
                current.spentCents
            }
            current.copy(records = remaining, spentCents = spent)
        }
    }

    @Suppress("LoopWithTooManyJumpStatements")
    suspend fun applyReconcile(
        rows: List<QuickPayReconcileRow>?,
        liveSubmittingHashes: Set<String>,
        shouldReleaseFailed: (QuickPayLedgerRecord, QuickPayReconcileRow) -> Boolean,
    ) {
        if (rows == null) return
        writeLedger { ledger, dayKey ->
            val next = ledger.pruned(dayKey, liveSubmittingHashes)
            val remaining = mutableListOf<QuickPayLedgerRecord>()
            var spent = next.spentCents
            for (record in next.records) {
                if (liveSubmittingHashes.contains(record.invoicePaymentHash)) {
                    remaining.add(record)
                    continue
                }
                val match = pickLedgerMatch(record, rows)
                if (match == null) {
                    remaining.add(record)
                    continue
                }
                when (match.status) {
                    QuickPayReconcileRow.Status.PENDING -> remaining.add(record)
                    QuickPayReconcileRow.Status.SUCCEEDED -> Unit
                    QuickPayReconcileRow.Status.FAILED -> {
                        if (!shouldReleaseFailed(record, match)) {
                            remaining.add(record)
                        } else if (record.dayKey == next.dayKey) {
                            spent = (spent - record.amountCents).coerceAtLeast(0L)
                        }
                    }
                }
            }
            next.copy(records = remaining, spentCents = spent)
        }
    }

    private suspend fun writeLedger(transform: (QuickPayLedger, String) -> QuickPayLedger): Boolean {
        var supported = true
        cacheStore.update { data ->
            val (ledger, ok) = data.resolvedLedger()
            if (!ok) {
                supported = false
                return@update data
            }
            val dayKey = currentDayKey()
            val next = transform(ledger, dayKey)
            data.copy(quickPayLedger = next)
        }
        return supported
    }

    private fun currentDayKey(): String =
        clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
}

private data class QuickPayDaySpend(
    val dayKey: String,
    val spentCents: Long,
)

private fun spendFor(ledger: QuickPayLedger, dayKey: String): QuickPayDaySpend = when {
    ledger.dayKey.isEmpty() || dayKey > ledger.dayKey -> QuickPayDaySpend(dayKey, 0L)
    dayKey == ledger.dayKey -> QuickPayDaySpend(dayKey, ledger.spentCents)
    else -> QuickPayDaySpend(ledger.dayKey, ledger.spentCents)
}

private fun QuickPayLedger.recordMatching(hash: String): QuickPayLedgerRecord? =
    records.find { it.invoicePaymentHash == hash || it.paymentId == hash || it.id == hash }

private fun QuickPayLedger.pruned(
    currentDay: String,
    keepHashes: Set<String> = emptySet(),
): QuickPayLedger {
    if (currentDay.isEmpty()) return this
    return copy(
        records = records.filter { it.dayKey >= currentDay || it.invoicePaymentHash in keepHashes },
    )
}

private fun AppCacheData.resolvedLedger(): Pair<QuickPayLedger, Boolean> {
    val ledger = quickPayLedger
    if (ledger != null) {
        return ledger to (ledger.version == QuickPaySpendStore.LEDGER_VERSION)
    }
    return QuickPayLedger(
        version = QuickPaySpendStore.LEDGER_VERSION,
        dayKey = "",
        spentCents = 0L,
        records = emptyList(),
    ) to true
}

private fun QuickPayLedger.releaseRecord(paymentHash: String): QuickPayLedger {
    val index = recordIndex(paymentHash) ?: return this
    val record = records[index]
    val remaining = records.toMutableList().also { it.removeAt(index) }
    val spent = if (record.dayKey == dayKey) {
        (spentCents - record.amountCents).coerceAtLeast(0L)
    } else {
        spentCents
    }
    return copy(records = remaining, spentCents = spent)
}

internal fun QuickPayLedger.recordIndex(hash: String): Int? =
    records.indexOfFirst { it.invoicePaymentHash == hash || it.paymentId == hash || it.id == hash }
        .takeIf { it >= 0 }

internal fun pickLedgerMatch(
    record: QuickPayLedgerRecord,
    rows: List<QuickPayReconcileRow>,
): QuickPayReconcileRow? {
    val matches = rows.filter { row ->
        row.isOutboundBolt11 && (
            row.invoicePaymentHash == record.invoicePaymentHash ||
                row.paymentId == record.invoicePaymentHash ||
                row.paymentId == record.paymentId ||
                (record.paymentId != null && row.invoicePaymentHash == record.paymentId)
            )
    }
    if (matches.isEmpty()) return null
    record.paymentId?.let { pid -> matches.find { it.paymentId == pid } }?.let { return it }
    return matches.maxBy {
        when (it.status) {
            QuickPayReconcileRow.Status.SUCCEEDED -> 2
            QuickPayReconcileRow.Status.PENDING -> 1
            QuickPayReconcileRow.Status.FAILED -> 0
        }
    }
}

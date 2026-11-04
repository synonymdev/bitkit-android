package to.bitkit.repositories

import breez_sdk_spark.CooperativeRecoveryError
import breez_sdk_spark.CooperativeRecoveryFailure
import breez_sdk_spark.CpfpInput
import breez_sdk_spark.ExitTransactionStatus
import breez_sdk_spark.RecoverFundsLeaf
import breez_sdk_spark.RecoverFundsResponse
import breez_sdk_spark.RecoveryMethod
import breez_sdk_spark.RecoveryTransaction
import breez_sdk_spark.RecoveryTxKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import to.bitkit.models.safe
import to.bitkit.utils.AppError
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Everything a Spark emergency exit needs to continue after a restart. The SDK keeps none of it: the signed
 * recovery, the request it came from, how its fees were funded and what this device already broadcast. Times are
 * epoch milliseconds. The JSON shape is shared with Bitkit iOS.
 */
data class SparkExitRecord(
    val version: Int = CURRENT_VERSION,
    val network: String,
    val createdAt: Long,
    val phase: Phase,
    val feeRateSatPerVbyte: ULong,
    val destination: String,
    /** The leaves of the quote the user confirmed; every rebuild selects exactly these. */
    val leafIds: List<String>,
    val funding: List<Funding>,
    /**
     * Bumped on every rebuild. Broadcasts are logged per generation and a rebuilt recovery replaces the old one
     * entirely, so older hex is never sent again.
     */
    val generation: Int = 0,
    val recovery: RecoverFundsResponse? = null,
    val broadcastLog: List<BroadcastAttempt> = emptyList(),
    val transferId: String? = null,
    /** What reaches savings: the exited value less the fees paid out of it, plus the unspent fee funding. */
    val expectedSats: ULong,
    val claimableAtHeight: UInt? = null,
    val leftover: List<Leftover> = emptyList(),
    val skippedLeafCount: Int = 0,
    /** Cooperative leaves the last build could not recover. */
    val failedLeafCount: Int = 0,
    /** Set and saved before a rebuild, so nothing of the diverged generation is broadcast while it is replaced. */
    val isRebuilding: Boolean = false,
    val lastBuildAt: Long? = null,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }

    sealed interface Phase {
        /** Waiting for confirmed fee funding of [requiredSats] in all at the funding address before the first build. */
        data class AwaitingFunding(val requiredSats: ULong) : Phase

        /** Built and being driven to the chain. */
        data object Running : Phase

        /** A rebuild needs fee funding of [requiredSats] in all, more than the funding address received so far. */
        data class NeedsFunding(val requiredSats: ULong) : Phase

        data object Done : Phase

        data class Failed(val reason: String) : Phase

        val requiredFundingSats: ULong?
            get() = when (this) {
                is AwaitingFunding -> requiredSats
                is NeedsFunding -> requiredSats
                else -> null
            }
    }

    data class Funding(
        /** The last path component under the funding key's account. */
        val index: UInt,
        val address: String,
        val pubkey: String,
        val txid: String? = null,
        val vout: UInt? = null,
        val valueSats: ULong? = null,
    ) {
        val input: CpfpInput?
            get() {
                if (txid == null || vout == null || valueSats == null) return null
                return CpfpInput.P2wpkh(txid, vout, valueSats, pubkey)
            }
    }

    data class BroadcastAttempt(
        val generation: Int,
        val txid: String,
        val attempts: Int,
        val rejections: Int,
        val lastAttemptAt: Long,
        val lastOutcome: String,
        val isAccepted: Boolean,
    )

    data class Leftover(
        val txid: String,
        val vout: UInt,
        val valueSats: ULong,
    )

    val isActive: Boolean
        get() = when (phase) {
            is Phase.AwaitingFunding, Phase.Running, is Phase.NeedsFunding -> true
            Phase.Done, is Phase.Failed -> false
        }

    val fundingInputs: List<CpfpInput> get() = funding.mapNotNull { it.input }

    val fundedSats: ULong get() = funding.fold(0uL) { sum, f -> sum.safe() + (f.valueSats ?: 0uL).safe() }

    /** What the funding address still has to receive before the exit can build, or null when nothing is missing. */
    val fundingShortfallSats: ULong?
        get() {
            val required = phase.requiredFundingSats ?: return null
            val shortfall = required.safe() - fundedSats.safe()
            return shortfall.takeIf { it > 0uL }
        }

    /** Starts a new generation from a fresh build, forgetting what was broadcast for the previous one. */
    fun adopt(built: RecoverFundsResponse, atMillis: Long): SparkExitRecord = copy(
        generation = generation + 1,
        recovery = built,
        broadcastLog = emptyList(),
        phase = Phase.Running,
        isRebuilding = false,
        lastBuildAt = atMillis,
        failedLeafCount = built.failed.size,
    )
}

/**
 * Reads and writes the exit record of one wallet. Writes go to a temporary file first and replace the record in one
 * move, so a crash never leaves half a record. Files in the app's storage are readable after the first unlock, so
 * the background worker can drive the exit while the device is locked.
 */
class SparkExitStore(private val file: File) {
    fun load(): SparkExitRecord? {
        if (!file.exists()) return null
        return SparkExitJson.decode(file.readText())
    }

    /** Whether an exit runs, false when the record is missing or unreadable. */
    fun isActive(): Boolean = runCatching { load()?.isActive }.getOrNull() == true

    /** Moves an unreadable record out of the way, keeping it for support. */
    fun setAside(nowMillis: Long) {
        val aside = File(file.parentFile, "${file.nameWithoutExtension}.unreadable-$nowMillis.json")
        Files.move(file.toPath(), aside.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    fun save(record: SparkExitRecord) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(SparkExitJson.encode(record))
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun delete() {
        file.delete()
    }
}

class SparkExitFormatError(message: String) : AppError(message)

class SparkExitUnsupportedVersion(val version: Int) : AppError("Unsupported Spark exit record version '$version'")

/**
 * The exit record as JSON. The record's own fields follow Swift's synthesized `Codable` (camelCase, an enum case as
 * `{"running":{}}`, absent optionals left out), and `recovery` follows spark-sdk 0.27.2's serde shape: snake_case
 * fields, explicit nulls and externally tagged enums (`"Ready"`, `{"Confirmed":{"block_height":120}}`), so a stored
 * recovery is readable by the Breez CLI's `check-recover-funds`. Built by hand so the app's `Json` settings cannot
 * change it.
 */
object SparkExitJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(record: SparkExitRecord): String = recordToJson(record).toString()

    fun decode(text: String): SparkExitRecord {
        val root = json.parseToJsonElement(text).jsonObject
        val version = root.int("version")
        if (version != SparkExitRecord.CURRENT_VERSION) throw SparkExitUnsupportedVersion(version)
        return recordFromJson(root)
    }

    fun encodeRecovery(recovery: RecoverFundsResponse): String = recoveryToJson(recovery).toString()

    fun decodeRecovery(text: String): RecoverFundsResponse =
        recoveryFromJson(json.parseToJsonElement(text).jsonObject)

    private fun recordToJson(r: SparkExitRecord) = buildJsonObject {
        put("version", r.version)
        put("network", r.network)
        put("createdAt", r.createdAt)
        put("phase", phaseToJson(r.phase))
        put("feeRateSatPerVbyte", r.feeRateSatPerVbyte.toLong())
        put("destination", r.destination)
        put("leafIds", buildJsonArray { r.leafIds.forEach { add(JsonPrimitive(it)) } })
        put(
            "funding",
            buildJsonArray {
                r.funding.forEach {
                    add(
                        buildJsonObject {
                            put("index", it.index.toLong())
                            put("address", it.address)
                            put("pubkey", it.pubkey)
                            it.txid?.let { txid -> put("txid", txid) }
                            it.vout?.let { vout -> put("vout", vout.toLong()) }
                            it.valueSats?.let { value -> put("valueSats", value.toLong()) }
                        },
                    )
                }
            },
        )
        put("generation", r.generation)
        r.recovery?.let { put("recovery", recoveryToJson(it)) }
        put(
            "broadcastLog",
            buildJsonArray {
                r.broadcastLog.forEach {
                    add(
                        buildJsonObject {
                            put("generation", it.generation)
                            put("txid", it.txid)
                            put("attempts", it.attempts)
                            put("rejections", it.rejections)
                            put("lastAttemptAt", it.lastAttemptAt)
                            put("lastOutcome", it.lastOutcome)
                            put("isAccepted", it.isAccepted)
                        },
                    )
                }
            },
        )
        r.transferId?.let { put("transferId", it) }
        put("expectedSats", r.expectedSats.toLong())
        r.claimableAtHeight?.let { put("claimableAtHeight", it.toLong()) }
        put(
            "leftover",
            buildJsonArray {
                r.leftover.forEach {
                    add(
                        buildJsonObject {
                            put("txid", it.txid)
                            put("vout", it.vout.toLong())
                            put("valueSats", it.valueSats.toLong())
                        },
                    )
                }
            },
        )
        put("skippedLeafCount", r.skippedLeafCount)
        put("failedLeafCount", r.failedLeafCount)
        put("isRebuilding", r.isRebuilding)
        r.lastBuildAt?.let { put("lastBuildAt", it) }
    }

    private fun recordFromJson(o: JsonObject) = SparkExitRecord(
        version = o.int("version"),
        network = o.string("network"),
        createdAt = o.millis("createdAt"),
        phase = phaseFromJson(o.getValue("phase")),
        feeRateSatPerVbyte = o.ulong("feeRateSatPerVbyte"),
        destination = o.string("destination"),
        leafIds = o.array("leafIds").map { it.jsonPrimitive.content },
        funding = o.array("funding").map {
            val f = it.jsonObject
            SparkExitRecord.Funding(
                index = f.ulong("index").toUInt(),
                address = f.string("address"),
                pubkey = f.string("pubkey"),
                txid = f.stringOrNull("txid"),
                vout = f.ulongOrNull("vout")?.toUInt(),
                valueSats = f.ulongOrNull("valueSats"),
            )
        },
        generation = o.intOrNull("generation") ?: 0,
        recovery = o.objectOrNull("recovery")?.let(::recoveryFromJson),
        broadcastLog = o.arrayOrEmpty("broadcastLog").map {
            val b = it.jsonObject
            SparkExitRecord.BroadcastAttempt(
                generation = b.int("generation"),
                txid = b.string("txid"),
                attempts = b.int("attempts"),
                rejections = b.intOrNull("rejections") ?: 0,
                lastAttemptAt = b.millis("lastAttemptAt"),
                lastOutcome = b.string("lastOutcome"),
                isAccepted = b.booleanOrNull("isAccepted") ?: false,
            )
        },
        transferId = o.stringOrNull("transferId"),
        expectedSats = o.ulong("expectedSats"),
        claimableAtHeight = o.ulongOrNull("claimableAtHeight")?.toUInt(),
        leftover = o.arrayOrEmpty("leftover").map {
            val l = it.jsonObject
            SparkExitRecord.Leftover(l.string("txid"), l.ulong("vout").toUInt(), l.ulong("valueSats"))
        },
        skippedLeafCount = o.intOrNull("skippedLeafCount") ?: 0,
        failedLeafCount = o.intOrNull("failedLeafCount") ?: 0,
        isRebuilding = o.booleanOrNull("isRebuilding") ?: false,
        lastBuildAt = o.millisOrNull("lastBuildAt"),
    )

    private fun phaseToJson(phase: SparkExitRecord.Phase): JsonObject = when (phase) {
        is SparkExitRecord.Phase.AwaitingFunding ->
            tagged("awaitingFunding", buildJsonObject { put("requiredSats", phase.requiredSats.toLong()) })
        SparkExitRecord.Phase.Running -> tagged("running", JsonObject(emptyMap()))
        is SparkExitRecord.Phase.NeedsFunding ->
            tagged("needsFunding", buildJsonObject { put("requiredSats", phase.requiredSats.toLong()) })
        SparkExitRecord.Phase.Done -> tagged("done", JsonObject(emptyMap()))
        is SparkExitRecord.Phase.Failed -> tagged("failed", buildJsonObject { put("reason", phase.reason) })
    }

    private fun phaseFromJson(element: JsonElement): SparkExitRecord.Phase {
        val (tag, fields) = variant(element)
        return when (tag) {
            "awaitingFunding" -> SparkExitRecord.Phase.AwaitingFunding(requireFields(fields).ulong("requiredSats"))
            "running" -> SparkExitRecord.Phase.Running
            "needsFunding" -> SparkExitRecord.Phase.NeedsFunding(requireFields(fields).ulong("requiredSats"))
            "done" -> SparkExitRecord.Phase.Done
            "failed" -> SparkExitRecord.Phase.Failed(requireFields(fields).string("reason"))
            else -> throw unknown(tag)
        }
    }

    private fun recoveryToJson(r: RecoverFundsResponse) = buildJsonObject {
        put("recoverable_value_sats", r.recoverableValueSats.toLong())
        put("total_fee_sats", r.totalFeeSats.toLong())
        put("cooperative_fee_sats", r.cooperativeFeeSats.toLong())
        put("cpfp_fee_sats", r.cpfpFeeSats.toLong())
        put("fanout_fee_sats", r.fanoutFeeSats.toLong())
        put("refund_fee_sats", r.refundFeeSats.toLong())
        put("sweep_fee_sats", r.sweepFeeSats.toLong())
        put("leaves", JsonArray(r.leaves.map(::leafToJson)))
        put("failed", JsonArray(r.failed.map(::failureToJson)))
        put("transactions", JsonArray(r.transactions.map(::transactionToJson)))
        put("funding_inputs", JsonArray(r.fundingInputs.map(::cpfpInputToJson)))
        put("fee_rate_sat_per_vbyte", r.feeRateSatPerVbyte.toLong())
        put("destination", r.destination)
    }

    private fun recoveryFromJson(o: JsonObject) = RecoverFundsResponse(
        recoverableValueSats = o.ulong("recoverable_value_sats"),
        totalFeeSats = o.ulong("total_fee_sats"),
        cooperativeFeeSats = o.ulong("cooperative_fee_sats"),
        cpfpFeeSats = o.ulong("cpfp_fee_sats"),
        fanoutFeeSats = o.ulong("fanout_fee_sats"),
        refundFeeSats = o.ulong("refund_fee_sats"),
        sweepFeeSats = o.ulong("sweep_fee_sats"),
        leaves = o.array("leaves").map { leafFromJson(it.jsonObject) },
        failed = o.array("failed").map { failureFromJson(it.jsonObject) },
        transactions = o.array("transactions").map { transactionFromJson(it.jsonObject) },
        fundingInputs = o.array("funding_inputs").map(::cpfpInputFromJson),
        feeRateSatPerVbyte = o.ulong("fee_rate_sat_per_vbyte"),
        destination = o.string("destination"),
    )

    private val methods = mapOf(RecoveryMethod.COOPERATIVE to "Cooperative", RecoveryMethod.UNILATERAL to "Unilateral")

    private val kinds = mapOf(
        RecoveryTxKind.COOPERATIVE to "Cooperative",
        RecoveryTxKind.FAN_OUT to "FanOut",
        RecoveryTxKind.NODE to "Node",
        RecoveryTxKind.REFUND to "Refund",
        RecoveryTxKind.SWEEP to "Sweep",
    )

    private fun leafToJson(leaf: RecoverFundsLeaf) = buildJsonObject {
        put("leaf_id", leaf.leafId)
        put("value_sats", leaf.valueSats.toLong())
        put("method", methods.getValue(leaf.method))
    }

    private fun leafFromJson(o: JsonObject): RecoverFundsLeaf {
        val name = o.string("method")
        val method = methods.entries.firstOrNull { it.value == name }?.key ?: throw unknown(name)
        return RecoverFundsLeaf(o.string("leaf_id"), o.ulong("value_sats"), method)
    }

    private fun failureToJson(failure: CooperativeRecoveryFailure) = buildJsonObject {
        put("leaf_id", failure.leafId)
        put("output_txid", failure.outputTxid)
        put("output_vout", failure.outputVout.toLong())
        put(
            "error",
            when (val error = failure.error) {
                is CooperativeRecoveryError.ReplacementFeeTooLow -> tagged(
                    "ReplacementFeeTooLow",
                    buildJsonObject {
                        put("required_fee_sats", error.requiredFeeSats.toLong())
                        put("required_fee_rate_sat_per_vbyte", error.requiredFeeRateSatPerVbyte.toLong())
                    },
                )
                is CooperativeRecoveryError.OperatorsUnavailable ->
                    tagged("OperatorsUnavailable", buildJsonObject { put("message", error.message) })
                is CooperativeRecoveryError.Generic ->
                    tagged("Generic", buildJsonObject { put("message", error.message) })
            },
        )
    }

    private fun failureFromJson(o: JsonObject): CooperativeRecoveryFailure {
        val (tag, fields) = variant(o.getValue("error"))
        val error = when (tag) {
            "ReplacementFeeTooLow" -> requireFields(fields).let {
                CooperativeRecoveryError.ReplacementFeeTooLow(
                    it.ulong("required_fee_sats"),
                    it.ulong("required_fee_rate_sat_per_vbyte"),
                )
            }
            "OperatorsUnavailable" -> CooperativeRecoveryError.OperatorsUnavailable(
                requireFields(fields).string("message")
            )
            "Generic" -> CooperativeRecoveryError.Generic(requireFields(fields).string("message"))
            else -> throw unknown(tag)
        }
        return CooperativeRecoveryFailure(
            o.string("leaf_id"),
            o.string("output_txid"),
            o.ulong("output_vout").toUInt(),
            error
        )
    }

    private fun transactionToJson(tx: RecoveryTransaction) = buildJsonObject {
        put("kind", kinds.getValue(tx.kind))
        put("node_id", tx.nodeId?.let(::JsonPrimitive) ?: JsonNull)
        put("txid", tx.txid)
        put("tx_hex", tx.txHex)
        put("cpfp_tx_hex", tx.cpfpTxHex?.let(::JsonPrimitive) ?: JsonNull)
        put("csv_timelock_blocks", tx.csvTimelockBlocks?.let { JsonPrimitive(it.toLong()) } ?: JsonNull)
        put("depends_on", JsonArray(tx.dependsOn.map(::JsonPrimitive)))
        put("status", statusToJson(tx.status))
    }

    private fun transactionFromJson(o: JsonObject): RecoveryTransaction {
        val kindName = o.string("kind")
        return RecoveryTransaction(
            kind = kinds.entries.firstOrNull { it.value == kindName }?.key ?: throw unknown(kindName),
            nodeId = o.stringOrNull("node_id"),
            txid = o.string("txid"),
            txHex = o.string("tx_hex"),
            cpfpTxHex = o.stringOrNull("cpfp_tx_hex"),
            csvTimelockBlocks = o.ulongOrNull("csv_timelock_blocks")?.toUInt(),
            dependsOn = o.array("depends_on").map { it.jsonPrimitive.content },
            status = statusFromJson(o.getValue("status")),
        )
    }

    private fun statusToJson(status: ExitTransactionStatus): JsonElement = when (status) {
        is ExitTransactionStatus.Confirmed -> tagged(
            "Confirmed",
            buildJsonObject { put("block_height", status.blockHeight?.let { JsonPrimitive(it.toLong()) } ?: JsonNull) },
        )
        ExitTransactionStatus.Ready -> JsonPrimitive("Ready")
        ExitTransactionStatus.WaitingForDependencies -> JsonPrimitive("WaitingForDependencies")
        is ExitTransactionStatus.WaitingForTimelock -> tagged(
            "WaitingForTimelock",
            buildJsonObject {
                put("spendable_at_height", status.spendableAtHeight?.let { JsonPrimitive(it.toLong()) } ?: JsonNull)
            },
        )
        ExitTransactionStatus.Unverified -> JsonPrimitive("Unverified")
    }

    private fun statusFromJson(element: JsonElement): ExitTransactionStatus {
        val (tag, fields) = variant(element)
        return when (tag) {
            "Confirmed" -> ExitTransactionStatus.Confirmed(requireFields(fields).ulongOrNull("block_height")?.toUInt())
            "Ready" -> ExitTransactionStatus.Ready
            "WaitingForDependencies" -> ExitTransactionStatus.WaitingForDependencies
            "WaitingForTimelock" -> ExitTransactionStatus.WaitingForTimelock(
                requireFields(fields).ulongOrNull("spendable_at_height")?.toUInt(),
            )
            "Unverified" -> ExitTransactionStatus.Unverified
            else -> throw unknown(tag)
        }
    }

    private fun cpfpInputToJson(input: CpfpInput): JsonObject = when (input) {
        is CpfpInput.P2wpkh -> tagged(
            "P2wpkh",
            buildJsonObject {
                put("txid", input.txid)
                put("vout", input.vout.toLong())
                put("value_sats", input.valueSats.toLong())
                put("pubkey", input.pubkey)
            },
        )
        is CpfpInput.P2tr -> tagged(
            "P2tr",
            buildJsonObject {
                put("txid", input.txid)
                put("vout", input.vout.toLong())
                put("value_sats", input.valueSats.toLong())
                put("pubkey", input.pubkey)
            },
        )
        is CpfpInput.Custom -> tagged(
            "Custom",
            buildJsonObject {
                put("txid", input.txid)
                put("vout", input.vout.toLong())
                put("value_sats", input.valueSats.toLong())
                put("script_pubkey_hex", input.scriptPubkeyHex)
                put("signed_input_weight", input.signedInputWeight.toLong())
            },
        )
    }

    private fun cpfpInputFromJson(element: JsonElement): CpfpInput {
        val (tag, fields) = variant(element)
        val f = requireFields(fields)
        val txid = f.string("txid")
        val vout = f.ulong("vout").toUInt()
        val value = f.ulong("value_sats")
        return when (tag) {
            "P2wpkh" -> CpfpInput.P2wpkh(txid, vout, value, f.string("pubkey"))
            "P2tr" -> CpfpInput.P2tr(txid, vout, value, f.string("pubkey"))
            "Custom" -> CpfpInput.Custom(
                txid,
                vout,
                value,
                f.string("script_pubkey_hex"),
                f.ulong("signed_input_weight")
            )
            else -> throw unknown(tag)
        }
    }

    /** serde's default enum shape: a unit variant is its name, a variant with fields an object keyed by its name. */
    private fun variant(element: JsonElement): Pair<String, JsonObject?> {
        if (element is JsonPrimitive && element.isString) return element.content to null
        val o = element.jsonObject
        require(o.size == 1) { "Expected one variant key, got '${o.keys}'" }
        val (tag, fields) = o.entries.first()
        return tag to (fields as? JsonObject)
    }

    private fun tagged(tag: String, fields: JsonObject) = JsonObject(mapOf(tag to fields))

    private fun requireFields(fields: JsonObject?): JsonObject = requireNotNull(
        fields
    ) { "Variant is missing its fields" }

    private fun unknown(tag: String) = SparkExitFormatError("Unknown variant '$tag'")

    private fun JsonObject.value(key: String): JsonPrimitive? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }

    private fun JsonObject.string(key: String) = requireNotNull(value(key)) { "Missing '$key'" }.content

    private fun JsonObject.stringOrNull(key: String) = value(key)?.content

    private fun JsonObject.ulong(key: String) = string(key).toULong()

    private fun JsonObject.ulongOrNull(key: String) = value(key)?.content?.toULong()

    private fun JsonObject.int(key: String) = string(key).toInt()

    private fun JsonObject.intOrNull(key: String) = value(key)?.content?.toInt()

    private fun JsonObject.booleanOrNull(key: String) = value(key)?.content?.toBooleanStrictOrNull()

    /** Swift writes dates as a millisecond count that may carry a fraction. */
    private fun JsonObject.millis(key: String) = string(key).toBigDecimal().toLong()

    private fun JsonObject.millisOrNull(key: String) = value(key)?.content?.toBigDecimal()?.toLong()

    private fun JsonObject.array(key: String) = requireNotNull(this[key]) { "Missing '$key'" }.jsonArray

    private fun JsonObject.arrayOrEmpty(key: String) = (this[key] as? JsonArray).orEmpty()

    private fun JsonObject.objectOrNull(key: String) = this[key] as? JsonObject
}

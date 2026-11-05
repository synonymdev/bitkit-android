package to.bitkit.repositories

import breez_sdk_spark.CooperativeRecoveryError
import breez_sdk_spark.CooperativeRecoveryFailure
import breez_sdk_spark.CpfpInput
import breez_sdk_spark.ExitTransactionStatus
import breez_sdk_spark.RecoveryTxKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SparkExitRecordTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `record round trips with every phase and status`() {
        val recovery = SparkExitFixtures.recovery(
            transactions = listOf(
                SparkExitFixtures.tx(
                    "fan",
                    kind = RecoveryTxKind.FAN_OUT,
                    status = ExitTransactionStatus.Confirmed(120u)
                ),
                SparkExitFixtures.tx("node", cpfp = "03cpfp", dependsOn = listOf("fan")),
                SparkExitFixtures.tx(
                    "refund",
                    kind = RecoveryTxKind.REFUND,
                    cpfp = "03cpfp2",
                    dependsOn = listOf("node"),
                    status = ExitTransactionStatus.WaitingForTimelock(2_100u),
                    csv = 2_000u,
                ),
                SparkExitFixtures.tx(
                    "wait",
                    dependsOn = listOf("node"),
                    status = ExitTransactionStatus.WaitingForDependencies
                ),
                SparkExitFixtures.tx("unknown", status = ExitTransactionStatus.Unverified),
                SparkExitFixtures.tx(
                    "sweep",
                    kind = RecoveryTxKind.SWEEP,
                    dependsOn = listOf("refund"),
                    status = ExitTransactionStatus.Confirmed(null),
                ),
            ),
            failed = listOf(
                CooperativeRecoveryFailure("a", "o1", 0u, CooperativeRecoveryError.ReplacementFeeTooLow(5uL, 6uL)),
                CooperativeRecoveryFailure("b", "o2", 1u, CooperativeRecoveryError.OperatorsUnavailable("down")),
                CooperativeRecoveryFailure("c", "o3", 2u, CooperativeRecoveryError.Generic("boom")),
            ),
        )
        val adopted = SparkExitFixtures.record(SparkExitRecord.Phase.Running).adopt(recovery, 1_700_000_050_000L)
        val phases = listOf(
            SparkExitRecord.Phase.AwaitingFunding(20_000uL),
            SparkExitRecord.Phase.Running,
            SparkExitRecord.Phase.NeedsFunding(4_321uL),
            SparkExitRecord.Phase.Done,
            SparkExitRecord.Phase.Failed("Nothing left to recover"),
        )

        phases.forEach { phase ->
            val record = adopted.copy(
                phase = phase,
                broadcastLog = listOf(
                    SparkExitRecord.BroadcastAttempt(1, "node", 2, 1, 1_700_000_100_000L, "accepted", true),
                    SparkExitRecord.BroadcastAttempt(
                        1,
                        "refund",
                        1,
                        0,
                        1L,
                        "inputs missing",
                        false,
                        isInputSpent = true,
                        feeChainFailures = 2,
                        acceptedAtHeight = 1_000u,
                    ),
                ),
                claimableAtHeight = 2_100u,
                leftover = listOf(SparkExitRecord.Leftover("change", 3u, 700uL)),
                isRebuilding = true,
                leafTotalSats = 90_112uL,
                earlierCpfpTxids = listOf("child"),
                leftoverSweep = SparkExitRecord.LeftoverSweep(
                    "sweep",
                    "02sweep",
                    5_035uL,
                    true,
                    isConfirmed = true,
                    feeRateSatPerVbyte = 7uL,
                    sentAtHeight = 1_010u,
                    replacedTxids = listOf("sweep-0"),
                ),
                fundingPaymentTxid = "paid",
                spentStepStrikes = mapOf("refund" to 1),
                lostLeaves = listOf(SparkExitRecord.LostLeaf("leaf-1", 16_384uL)),
                leftBehindLeaves = listOf(SparkExitRecord.LeftBehindLeaf("leaf-2", 8_192uL, "its refund cannot pay")),
                stallRebuilds = 2,
                stallConfirmedCount = 3,
                unverifiedRebuiltAtHeight = 1_001u,
                stoppedLeafSince = mapOf("leaf-2" to 1_002u),
                retrySince = 1_003u,
                retriedAt = 1_009u,
                exitStateSnapshot = "exit-state",
                isRebuiltFromChain = true,
                isRedoRebuild = true,
                earlierReceivedSats = mapOf("sweep-0" to 90_000uL),
            )

            assertEquals(record, SparkExitJson.decode(SparkExitJson.encode(record)))
        }
    }

    @Test
    fun `recovery uses the spark-sdk serde shape`() {
        val recovery = SparkExitFixtures.recovery(
            listOf(
                SparkExitFixtures.tx("a", cpfp = "03c", status = ExitTransactionStatus.Confirmed(7u)),
                SparkExitFixtures.tx("b", kind = RecoveryTxKind.FAN_OUT),
                SparkExitFixtures.tx("c", status = ExitTransactionStatus.WaitingForTimelock(null)),
            ),
        )

        val json = Json.parseToJsonElement(SparkExitJson.encodeRecovery(recovery)).jsonObject

        assertEquals(2, json.getValue("fee_rate_sat_per_vbyte").jsonPrimitive.int)
        val transactions = json.getValue("transactions").jsonArray.map { it.jsonObject }
        assertEquals("Node", transactions[0].getValue("kind").jsonPrimitive.content)
        assertEquals("03c", transactions[0].getValue("cpfp_tx_hex").jsonPrimitive.content)
        val confirmed = transactions[0].getValue("status").jsonObject.getValue("Confirmed").jsonObject
        assertEquals(7, confirmed.getValue("block_height").jsonPrimitive.int)
        assertEquals("FanOut", transactions[1].getValue("kind").jsonPrimitive.content)
        assertEquals(JsonPrimitive("Ready"), transactions[1].getValue("status"))
        assertEquals(JsonNull, transactions[1].getValue("cpfp_tx_hex"))
        val timelock = transactions[2].getValue("status").jsonObject.getValue("WaitingForTimelock") as JsonObject
        assertEquals(JsonNull, timelock.getValue("spendable_at_height"))
        val input = json.getValue("funding_inputs").jsonArray[0].jsonObject.getValue("P2wpkh").jsonObject
        assertEquals(20_000, input.getValue("value_sats").jsonPrimitive.int)
    }

    /** A recovery as the Breez CLI's `recover-funds --output-file` writes it (serde_json, spark-sdk 0.27.2). */
    @Test
    fun `reads a recovery written by spark-sdk`() {
        val json = """
            {"recoverable_value_sats":90112,"total_fee_sats":8832,"cooperative_fee_sats":0,"cpfp_fee_sats":8388,
             "fanout_fee_sats":0,"refund_fee_sats":0,"sweep_fee_sats":444,
             "leaves":[{"leaf_id":"l1","value_sats":90112,"method":"Unilateral"}],
             "failed":[{"leaf_id":"l2","output_txid":"t","output_vout":3,"error":{"OperatorsUnavailable":{"message":"timeout"}}}],
             "transactions":[
               {"kind":"Node","node_id":"n1","txid":"aa","tx_hex":"02aa","cpfp_tx_hex":"03bb","csv_timelock_blocks":null,
                "depends_on":[],"status":"Ready"},
               {"kind":"Refund","node_id":"l1","txid":"cc","tx_hex":"02cc","cpfp_tx_hex":"03dd","csv_timelock_blocks":2000,
                "depends_on":["aa"],"status":{"WaitingForTimelock":{"spendable_at_height":3100}}},
               {"kind":"Sweep","node_id":null,"txid":"ee","tx_hex":"02ee","cpfp_tx_hex":null,"csv_timelock_blocks":null,
                "depends_on":["cc"],"status":"WaitingForDependencies"}],
             "funding_inputs":[{"P2wpkh":{"txid":"ff","vout":0,"value_sats":10000,"pubkey":"02cd"}}],
             "fee_rate_sat_per_vbyte":1,"destination":"bc1qexample"}
        """.trimIndent()

        val recovery = SparkExitJson.decodeRecovery(json)

        assertEquals(
            listOf(RecoveryTxKind.NODE, RecoveryTxKind.REFUND, RecoveryTxKind.SWEEP),
            recovery.transactions.map { it.kind }
        )
        assertEquals(ExitTransactionStatus.WaitingForTimelock(3_100u), recovery.transactions[1].status)
        assertEquals(2_000u, recovery.transactions[1].csvTimelockBlocks)
        assertNull(recovery.transactions[2].nodeId)
        assertEquals(CooperativeRecoveryError.OperatorsUnavailable("timeout"), recovery.failed[0].error)
        assertEquals(listOf<CpfpInput>(CpfpInput.P2wpkh("ff", 0u, 10_000uL, "02cd")), recovery.fundingInputs)
    }

    /** A record as Bitkit iOS writes it: Swift's synthesized Codable, a fractional millisecond date. */
    @Test
    fun `reads a record written by bitkit ios`() {
        val text = requireNotNull(javaClass.classLoader?.getResource("spark-exit-record-ios.json")).readText()

        val record = SparkExitJson.decode(text)

        assertEquals(SparkExitRecord.Phase.Running, record.phase)
        assertEquals(1_700_000_000_123L, record.createdAt)
        assertEquals(1, record.generation)
        assertEquals(listOf<CpfpInput>(CpfpInput.P2wpkh("f0", 1u, 20_000uL, "02ab")), record.fundingInputs)
        assertEquals(3_100u, record.recovery?.let(SparkExitMath::claimableAtHeight))
        assertEquals("transfer-1", record.transferId)
        assertNull(record.claimableAtHeight)
        assertTrue(record.broadcastLog.single().isAccepted)
        // Written before exits had their own funding key index: it signs with index 0.
        assertEquals(0u, record.fundingIndex)
        assertEquals(record, SparkExitJson.decode(SparkExitJson.encode(record)))
    }

    @Test
    fun `leaves absent optionals out of the record`() {
        val json = Json.parseToJsonElement(SparkExitJson.encode(SparkExitFixtures.record())).jsonObject

        assertFalse("recovery" in json)
        assertFalse("claimableAtHeight" in json)
        assertFalse("fundingPaymentTxid" in json)
        assertFalse("spentStepStrikes" in json)
        assertFalse("lostLeaves" in json)
        listOf(
            "stallRebuilds",
            "stallConfirmedCount",
            "unverifiedRebuiltAtHeight",
            "stoppedLeafSince",
            "retrySince",
            "retriedAt",
            "exitStateSnapshot",
            "isRebuiltFromChain",
            "isRedoRebuild",
            "earlierReceivedSats",
        ).forEach { assertFalse(it in json, it) }
        assertEquals(
            JsonPrimitive(20_000),
            json.getValue("phase").jsonObject.getValue("awaitingFunding").jsonObject["requiredSats"]
        )
        assertFalse("txid" in json.getValue("funding").jsonArray[0].jsonObject)
    }

    @Test
    fun `refuses an unknown record version`() {
        val text = SparkExitJson.encode(SparkExitFixtures.record()).replace("\"version\":1", "\"version\":2")

        val error = assertFailsWith<SparkExitUnsupportedVersion> { SparkExitJson.decode(text) }

        assertEquals(2, error.version)
    }

    @Test
    fun `a record of an unknown version is salvaged to rebuild from chain with its funding`() {
        val record = SparkExitFixtures.record(SparkExitRecord.Phase.Running).copy(
            funding = listOf(
                SparkExitRecord.Funding(3u, SparkExitFixtures.FUNDING_ADDRESS, "02ab", "f0", 1u, 20_000uL)
            ),
            recovery = SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1"))),
            leafTotalSats = 100_000uL,
            exitStateSnapshot = "exit-state",
        )
        val text = SparkExitJson.encode(record)
            .replace("\"version\":1", "\"version\":2")
            .replace("\"phase\":{\"running\":{}}", "\"phase\":{\"runningV2\":{}}")
        assertFailsWith<SparkExitUnsupportedVersion> { SparkExitJson.decode(text) }

        val salvaged = assertNotNull(SparkExitJson.salvage(text, nowMillis = 42L))

        assertEquals(SparkExitRecord.Phase.Running, salvaged.phase)
        assertTrue(salvaged.isRebuilding)
        assertTrue(salvaged.isRebuiltFromChain)
        assertTrue(salvaged.isActive)
        assertNull(salvaged.recovery)
        assertNull(salvaged.lastBuildAt)
        assertEquals(record.funding, salvaged.funding)
        assertEquals(record.fundingInputs, salvaged.fundingInputs)
        assertEquals(record.leafIds, salvaged.leafIds)
        assertEquals(record.transferId, salvaged.transferId)
        assertEquals(record.createdAt, salvaged.createdAt)
        assertEquals(100_000uL, salvaged.leafTotalSats)
        assertEquals("exit-state", salvaged.exitStateSnapshot)
        assertEquals(salvaged, SparkExitJson.decode(SparkExitJson.encode(salvaged)))
        assertFalse(salvaged.adopt(SparkExitFixtures.recovery(emptyList()), 1L).isRebuiltFromChain)
    }

    @Test
    fun `a salvaged record that had ended stays ended and one with nothing to rebuild from is not salvaged`() {
        val done = SparkExitJson.encode(SparkExitFixtures.record(SparkExitRecord.Phase.Done))
            .replace("\"version\":1", "\"version\":9")

        val salvaged = assertNotNull(SparkExitJson.salvage(done, nowMillis = 42L))

        assertEquals(SparkExitRecord.Phase.Done, salvaged.phase)
        assertFalse(salvaged.isRebuiltFromChain)
        assertNull(SparkExitJson.salvage("{", nowMillis = 42L))
        assertNull(SparkExitJson.salvage("""{"version":2,"network":"regtest"}""", nowMillis = 42L))
    }

    @Test
    fun `store saves loads and sets an unreadable record aside`() {
        val file = File(tempFolder.root, "regtest/wallet0/spark-exit.json")
        val store = SparkExitStore(file)
        assertNull(store.load())
        assertFalse(store.isActive())

        store.save(SparkExitFixtures.record())

        assertEquals(SparkExitFixtures.record(), store.load())
        assertTrue(store.isActive())
        assertFalse(File(file.parentFile, "spark-exit.json.tmp").exists())

        // An unreadable record counts as running until the repo salvages it, so an exit never disappears over it.
        file.writeText("{")
        assertTrue(store.isActive())
        assertEquals("{", store.loadText())
        store.setAside(42L)
        assertFalse(file.exists())
        assertTrue(File(file.parentFile, "spark-exit.unreadable-42.json").exists())
    }

    @Test
    fun `a failed or lossy exit stays reachable until its result was seen`() {
        val done = SparkExitFixtures.record(SparkExitRecord.Phase.Done)
        assertFalse(done.showsResult)

        val failed = SparkExitFixtures.record(SparkExitRecord.Phase.Failed(SparkExitDriver.LOST_REASON))
        assertTrue(failed.showsResult)
        assertFalse(failed.copy(isResultSeen = true).showsResult)

        val partial = done.copy(lostLeaves = listOf(SparkExitRecord.LostLeaf("leaf-1", 16_384uL)))
        assertTrue(partial.showsResult)
        assertFalse(partial.copy(isResultSeen = true).showsResult)

        val seen = failed.copy(isResultSeen = true)
        assertEquals(seen, SparkExitJson.decode(SparkExitJson.encode(seen)))
    }
}

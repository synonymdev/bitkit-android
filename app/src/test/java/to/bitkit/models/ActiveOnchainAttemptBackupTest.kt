package to.bitkit.models

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import to.bitkit.repositories.OnchainSendEvidence
import to.bitkit.test.BaseUnitTest
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ActiveOnchainAttemptBackupTest : BaseUnitTest() {
    private val json = Json { ignoreUnknownKeys = true }
    private val binding = "fe843546f607f38ba7b1e8fe479c3103139ebea5b83628cbaa465e41cf6cb6c0"

    private fun golden(): WalletBackupV1 {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/candidate-fee-rates-golden.json")).readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("8487f073ee55b9049aeed241819055138c82af8a596104bc2eada95b6ce96aa7", hash)
        return json.decodeFromString(bytes.decodeToString())
    }

    @Test
    fun `shared iOS candidate fee golden preserves exact map and older winner fee`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/candidate-fee-rates-golden.json")).readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("8487f073ee55b9049aeed241819055138c82af8a596104bc2eada95b6ce96aa7", hash)
        val backup = Json { ignoreUnknownKeys = true }.decodeFromString<WalletBackupV1>(bytes.decodeToString())
        val state = requireNotNull(backup.paykitPaymentState)
        val wire = requireNotNull(state.activeOnchainAttempt)
        wire.validateProofs(state.pendingProofs, "wallet0")
        val attempt = wire.restored("regtest", wire.wallet.binding, "wallet0", 0)
        assertEquals(4uL, attempt.winningFeeRateSatsPerVByte)
        assertEquals(2uL, attempt.copy(txid = attempt.candidateTxids.first()).winningFeeRateSatsPerVByte)
        assertEquals(wire, ActiveOnchainAttemptBackup.from(attempt, "regtest", wire.wallet.binding))
    }

    @Test
    fun `candidate fee wire survives restore and rejects foreign candidates or invalid UInt32 rates`() {
        val wire = requireNotNull(golden().paykitPaymentState?.activeOnchainAttempt)
        val winner = requireNotNull(wire.txid)
        val json = Json { ignoreUnknownKeys = true }
        fun withRates(rates: Map<String, String>): ActiveOnchainAttemptBackup {
            val fields = json.parseToJsonElement(json.encodeToString(wire)) as kotlinx.serialization.json.JsonObject
            val candidateFees = kotlinx.serialization.json.JsonObject(
                rates.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }
            )
            return json.decodeFromString(
                kotlinx.serialization.json.JsonObject(fields + ("candidateFeeRates" to candidateFees)).toString()
            )
        }
        val enhanced = withRates(mapOf(winner to "3"))
        val restored = enhanced.restored("regtest", binding, "wallet0", 0)
        assertEquals(3uL, restored.winningFeeRateSatsPerVByte)
        val exported = ActiveOnchainAttemptBackup.from(restored, "regtest", binding)
        assertEquals(enhanced, exported)
        listOf(
            mapOf("00".repeat(32) to "3"),
            mapOf(winner to "0"),
            mapOf(winner to "4294967296"),
            mapOf(winner to "03"),
        ).forEach {
            assertFailsWith<IllegalArgumentException> { withRates(it).restored("regtest", binding, "wallet0", 0) }
        }
        val original = wire.copy(txid = wire.candidateTxids.first()).restored("regtest", binding, "wallet0", 0)
        assertEquals(wire.feeRateSatsPerVByte.toULong(), original.winningFeeRateSatsPerVByte)
        val successor = wire.copy(txid = wire.candidateTxids.last()).restored("regtest", binding, "wallet0", 0)
        assertEquals(4uL, successor.winningFeeRateSatsPerVByte)
    }

    @Test
    fun `original candidate rate must agree with the retained original rate`() {
        val wire = requireNotNull(golden().paykitPaymentState?.activeOnchainAttempt)
        val original = wire.candidateTxids.first()
        val successor = wire.candidateTxids.last()
        for (status in listOf("pending", "unknown", "rejected", "accepted")) {
            val candidate = wire.copy(status = status, txid = original)
            assertFailsWith<IllegalArgumentException> {
                candidate.copy(candidateFeeRates = mapOf(original to "3", successor to "4"))
                    .restored("regtest", binding, "wallet0", 0)
            }
            for (rates in listOf(mapOf(successor to "4"), mapOf(original to "2", successor to "4"))) {
                val restored = candidate.copy(candidateFeeRates = rates).restored("regtest", binding, "wallet0", 0)
                assertEquals(2uL, restored.winningFeeRateSatsPerVByte)
                assertEquals(4uL, restored.copy(txid = successor).winningFeeRateSatsPerVByte)
            }
        }
    }

    @Test
    fun `restored funding amount must equal its original order fee`() {
        val wire = requireNotNull(golden().paykitPaymentState?.activeOnchainAttempt).copy(
            requestId = null,
            payerIdentity = null,
            orderId = "original-order",
            transfer = ActiveOnchainAttemptBackup.Transfer("2000", "3000", "900", "1000"),
        )
        for (amount in listOf("999", "1001", "1234")) {
            assertFailsWith<IllegalArgumentException> {
                wire.copy(amountSats = amount).restored("regtest", binding, "wallet0", 0)
            }
        }
        val restored = wire.copy(amountSats = "1000").restored("regtest", binding, "wallet0", 0)
        assertEquals(1000uL, restored.amountSats)
        assertEquals(restored.amountSats, restored.transferContext?.originalOrderFeeSats)
        assertEquals(wire.copy(amountSats = "1000"), ActiveOnchainAttemptBackup.from(restored, "regtest", binding))
    }

    @Test
    fun `every retained successor needs a fee rate regardless of current outcome`() {
        val wire = requireNotNull(golden().paykitPaymentState?.activeOnchainAttempt)
        val original = wire.candidateTxids.first()
        val successor = wire.candidateTxids.last()
        val later = "12".repeat(32)
        for (status in listOf("pending", "unknown", "rejected", "accepted")) {
            val candidate = wire.copy(status = status, txid = original, candidateTxids = listOf(original, successor, later))
            for (rates in listOf(null, emptyMap(), mapOf(successor to "4"), mapOf(later to "5"))) {
                assertFailsWith<IllegalArgumentException> {
                    candidate.copy(candidateFeeRates = rates).restored("regtest", binding, "wallet0", 0)
                }
            }
            val restored = candidate.copy(candidateFeeRates = mapOf(successor to "4", later to "5"))
                .restored("regtest", binding, "wallet0", 0)
            assertEquals(wire.feeRateSatsPerVByte.toULong(), restored.winningFeeRateSatsPerVByte)
            assertEquals(4uL, restored.copy(txid = successor).winningFeeRateSatsPerVByte)
            assertEquals(5uL, restored.copy(txid = later).winningFeeRateSatsPerVByte)
        }
    }

    @Test
    fun `incomplete historical fixture cannot install a restored guard`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/active-onchain-attempt-golden.json")).readBytes()
        val backup = json.decodeFromString<WalletBackupV1>(bytes.decodeToString())
        val wire = requireNotNull(backup.paykitPaymentState?.activeOnchainAttempt)
        assertFailsWith<IllegalArgumentException> { wire.restored("regtest", binding, "wallet0", 0) }
    }

    @Test
    fun `accepted successor restore requires its own valid fee rate`() {
        val wire = requireNotNull(golden().paykitPaymentState?.activeOnchainAttempt)
        val original = wire.candidateTxids.first()
        val successor = wire.candidateTxids.last()
        val accepted = wire.copy(status = "accepted", txid = successor)
        for (rates in listOf(null, emptyMap(), mapOf(original to "2"))) {
            assertFailsWith<IllegalArgumentException> {
                accepted.copy(candidateFeeRates = rates).restored("regtest", binding, "wallet0", 0)
            }
        }
        val restored = accepted.copy(candidateFeeRates = mapOf(successor to "4"))
            .restored("regtest", binding, "wallet0", 0)
        assertEquals(4uL, restored.winningFeeRateSatsPerVByte)
        val firstWinner = accepted.copy(txid = original, candidateFeeRates = mapOf(successor to "4"))
            .restored("regtest", binding, "wallet0", 0)
        assertEquals(wire.feeRateSatsPerVByte.toULong(), firstWinner.winningFeeRateSatsPerVByte)
    }

    @Test
    fun `restore rejects malformed original contact before installing a guard`() {
        val wire = requireNotNull(golden().paykitPaymentState?.activeOnchainAttempt)
        val followup = requireNotNull(wire.followup)
        val invalidContacts = listOf(
            kotlinx.serialization.json.JsonPrimitive(""),
            kotlinx.serialization.json.JsonPrimitive("   "),
            kotlinx.serialization.json.JsonPrimitive(123),
            kotlinx.serialization.json.JsonPrimitive(true),
            kotlinx.serialization.json.JsonNull,
            kotlinx.serialization.json.JsonObject(emptyMap()),
            kotlinx.serialization.json.JsonArray(emptyList()),
        )
        for (contact in invalidContacts) {
            assertFailsWith<IllegalArgumentException> {
                wire.copy(followup = followup.copy(contact = contact)).restored("regtest", binding, "wallet0", 0)
            }
        }
        val missing = wire.copy(followup = followup.copy(contact = null)).restored("regtest", binding, "wallet0", 0)
        assertNull(missing.backupFollowup?.contact)
        val validContact = kotlinx.serialization.json.JsonPrimitive("original-contact")
        val valid = wire.copy(followup = followup.copy(contact = validContact)).restored("regtest", binding, "wallet0", 0)
        assertEquals(validContact, valid.backupFollowup?.contact)
    }

    @Test
    fun `restored active operations require original local followup context`() {
        val wire = requireNotNull(golden().paykitPaymentState?.activeOnchainAttempt)
        for (status in listOf("pending", "accepted", "rejected", "unknown")) {
            val candidate = wire.copy(
                status = status,
                txid = wire.candidateTxids.first(),
                followup = null,
            )
            assertFailsWith<IllegalArgumentException> {
                candidate.restored("regtest", binding, "wallet0", 0)
            }
        }
    }

    @Test
    fun `unsigned active preparation cannot restore a permanent wallet guard`() {
        val wire = requireNotNull(golden().paykitPaymentState?.activeOnchainAttempt).copy(
            originalInputs = null,
            candidateTxids = emptyList(),
            candidateFeeRates = emptyMap(),
            txid = null,
            status = "pending",
        )
        assertFailsWith<IllegalArgumentException> {
            wire.restored("regtest", binding, "wallet0", 0)
        }
    }

    @Test
    fun `same golden bytes preserve original proof and remap only validated guard`() {
        val state = requireNotNull(golden().paykitPaymentState)
        val wire = requireNotNull(state.activeOnchainAttempt)
        wire.validateProofs(state.pendingProofs, "wallet0")
        val restored = wire.restored("regtest", binding, "destination-wallet", 2)
        assertEquals(2, restored.walletIndex)
        assertEquals("destination-wallet", restored.walletId)
        assertEquals(wire.payerIdentity, restored.payerIdentity)
        assertEquals(wire.requestId, restored.requestId)
        assertEquals(wire.candidateTxids, restored.candidateTxids)
        assertEquals(UInt.MAX_VALUE, restored.originalInputs?.single()?.vout)
        assertEquals("123", restored.backupFollowup?.feeSats)
        val precise = wire.copy(followup = wire.followup?.copy(createdAtMillis = "1791234000123"))
        assertEquals(
            "1791234000123",
            precise.restored("regtest", binding, "wallet0", 0).backupFollowup?.createdAtMillis
        )
        assertEquals(wire.followup?.contact, restored.backupFollowup?.contact)
        assertFalse(restored.localFollowupComplete)
        assertEquals(OnchainSendEvidence.Unknown, restored.evidence)
        assertNull(state.pendingProofs.single().onchainWalletId)
        WalletScope.pushTestOverride("destination-wallet").use {
            assertEquals("destination-wallet", state.pendingProofs.single().restored().onchainWalletId)
            val rebuilt = ActiveOnchainAttemptBackup.from(restored, "regtest", binding)
            assertEquals(wire.copy(wallet = wire.wallet.copy(sourceIndex = "2")), rebuilt)
            assertEquals(rebuilt, json.decodeFromString<ActiveOnchainAttemptBackup>(json.encodeToString(rebuilt)))
        }
    }

    @Test
    fun `wrong wallet network payer hardware proof or foreign candidate fail closed`() {
        val state = requireNotNull(golden().paykitPaymentState)
        val wire = requireNotNull(state.activeOnchainAttempt)
        assertFailsWith<IllegalArgumentException> { wire.restored("bitcoin", binding, "wallet0", 0) }
        assertFailsWith<IllegalArgumentException> { wire.restored("regtest", "00".repeat(32), "wallet0", 0) }
        assertFailsWith<IllegalArgumentException> { wire.copy(version = 2).restored("regtest", binding, "wallet0", 0) }
        assertFailsWith<IllegalArgumentException> {
            wire.copy(
                amountSats = "01234"
            ).restored("regtest", binding, "wallet0", 0)
        }
        assertFailsWith<IllegalArgumentException> {
            wire.copy(
                txid = "00".repeat(32)
            ).restored("regtest", binding, "wallet0", 0)
        }
        val proof = state.pendingProofs.single()
        val invalid = listOf(
            proof.copy(identity = "foreign-payer"),
            proof.copy(onchainWalletId = "hardware-wallet"),
            proof.copy(paymentIdentifier = "00".repeat(32), onchainAcceptanceVerified = true),
            proof.copy(onchainAmountSats = 1235uL),
            proof.copy(proofData = "00".repeat(32)),
            proof.copy(onchainAcceptanceVerified = true, proofData = proof.paymentIdentifier),
        )
        invalid.forEach { assertFailsWith<Exception> { wire.validateProofs(listOf(it), "wallet0") } }
    }

    @Test
    fun `unprepared zero fee guard is rejected and accepted restore resets local ack`() {
        val wire = requireNotNull(golden().paykitPaymentState?.activeOnchainAttempt)
        val pending = wire.copy(
            status = "pending",
            txid = null,
            originalInputs = null,
            candidateTxids = emptyList(),
            feeRateSatsPerVByte = "0"
        )
        assertFailsWith<IllegalArgumentException> {
            pending.restored("regtest", binding, "wallet0", 0)
        }
        assertFailsWith<IllegalArgumentException> {
            wire.copy(
                feeRateSatsPerVByte = "0"
            ).restored("regtest", binding, "wallet0", 0)
        }
        val accepted = wire.copy(status = "accepted", candidateFeeRates = mapOf(requireNotNull(wire.txid) to "4")).restored("regtest", binding, "wallet0", 0)
        assertEquals(OnchainSendEvidence.Accepted, accepted.evidence)
        assertFalse(accepted.localFollowupComplete)
    }
}

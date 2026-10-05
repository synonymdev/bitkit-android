package to.bitkit.models

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import to.bitkit.repositories.OnchainSendEvidence
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ActiveOnchainAttemptBackupTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val binding = "fe843546f607f38ba7b1e8fe479c3103139ebea5b83628cbaa465e41cf6cb6c0"

    private fun golden(): WalletBackupV1 {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/active-onchain-attempt-golden.json")).readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("1e392cdfaa82f48bed7be194ddaa3efe62efbf41a54fd14b70590d9a11f6f7b2", hash)
        return json.decodeFromString(bytes.decodeToString())
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
    fun `unprepared zero fee guard stays pending and accepted restore resets local ack`() {
        val wire = requireNotNull(golden().paykitPaymentState?.activeOnchainAttempt)
        val pending = wire.copy(
            status = "pending",
            txid = null,
            originalInputs = null,
            candidateTxids = emptyList(),
            feeRateSatsPerVByte = "0"
        )
        val restored = pending.restored("regtest", binding, "wallet0", 0)
        assertEquals(OnchainSendEvidence.Pending, restored.evidence)
        assertNull(restored.originalInputs)
        assertFalse(restored.localFollowupComplete)
        assertFailsWith<IllegalArgumentException> {
            wire.copy(
                feeRateSatsPerVByte = "0"
            ).restored("regtest", binding, "wallet0", 0)
        }
        val accepted = wire.copy(status = "accepted").restored("regtest", binding, "wallet0", 0)
        assertEquals(OnchainSendEvidence.Accepted, accepted.evidence)
        assertFalse(accepted.localFollowupComplete)
    }
}

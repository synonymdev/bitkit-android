@file:OptIn(ExperimentalTime::class)

package to.bitkit.models

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import to.bitkit.repositories.PaykitPaymentProofKind
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class PaykitPaymentStateBackupTest {
    @Test
    fun `payment backup accepts shared wire format and retains pending payment`() {
        WalletScope.pushTestOverride("wallet0").use {
            val fixture = """
                {"subscriptions":{"alice":{"acceptances":[{"id":{"paymentRequestId":"request","counterparty":"bob","counterpartyReceiverPath":"bitkit/server"},"acceptedAt":"2026-09-24T10:00:00.123Z"}],"presentedProposalIds":[]}},"pendingProofs":[{"identity":"alice","requestId":{"paymentRequestId":"request","counterparty":"bob","counterpartyReceiverPath":"bitkit/server","billingPeriodStartsAt":"2026-09-24T10:00:00.100Z"},"paymentEndpointIdentifier":"bitcoin-onchain","kind":"bitcoin-onchain-txid","paymentStarted":true,"billingPeriod":{"startsAt":"2026-09-24T10:00:00.100Z","endsAt":"2026-09-25T10:00:00.100Z"},"onchainMatchingTransactionIdsBeforeAttempt":[]}]}
            """.trimIndent()
            val backup = Json.decodeFromString<PaykitPaymentStateBackup>(fixture)
            val restored = backup.pendingProofs.single().restored()
            assertTrue(restored.paymentStarted)
            assertEquals(PaykitPaymentProofKind.Onchain, restored.kind)
            assertEquals(
                Instant.parse(requireNotNull(restored.requestId.billingPeriodStartsAt)),
                restored.billingPeriod?.startsAt
            )
            assertEquals(1, backup.subscriptions.getValue("alice").restored().acceptedAt.size)
            val rebuilt = PaykitPaymentStateBackup(
                subscriptions = backup.subscriptions.mapValues {
                    PaykitPaymentStateBackup.Subscription(
                        it.value.restored()
                    )
                },
                pendingProofs = listOf(PaykitPaymentStateBackup.Proof(restored)),
            )
            val decoded = Json.decodeFromString<PaykitPaymentStateBackup>(Json.encodeToString(rebuilt))
            assertEquals(restored, decoded.pendingProofs.single().restored())
            assertEquals(backup.subscriptions, decoded.subscriptions)

            val hardwareProof = restored.copy(onchainWalletId = "hardware-wallet")
            val hardwareBackup = PaykitPaymentStateBackup.Proof(hardwareProof)
            val hardwareJson = Json.encodeToString(hardwareBackup)
            assertContains(hardwareJson, "\"onchainWalletId\":\"hardware-wallet\"")
            assertEquals(
                hardwareProof,
                Json.decodeFromString<PaykitPaymentStateBackup.Proof>(hardwareJson).restored(),
            )

            val unknownKind = Json.decodeFromString<PaykitPaymentStateBackup>(
                fixture.replace("bitcoin-onchain-txid", "future-proof"),
            )
            assertFailsWith<IllegalArgumentException> { unknownKind.pendingProofs.single().restored() }
        }
    }
}

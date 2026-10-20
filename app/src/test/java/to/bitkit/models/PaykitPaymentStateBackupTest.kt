@file:OptIn(ExperimentalTime::class)

package to.bitkit.models

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import to.bitkit.repositories.PaykitPaymentProofKind
import to.bitkit.repositories.PaykitPaymentRequestId
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class PaykitPaymentStateBackupTest {
    @Suppress("MaxLineLength")
    @Test
    fun `proofs without an app id remain readable without inventing provenance`() {
        WalletScope.pushTestOverride("wallet0").use {
            val local = Json.decodeFromString<to.bitkit.repositories.PendingPaykitPaymentProof>(
                """{"identity":"alice","requestId":{"paymentRequestId":"request","counterparty":"bob"},"paymentEndpointIdentifier":"bitcoin-onchain","kind":"Onchain","paymentStarted":true}""",
            )
            assertEquals("", local.paymentAppId)
            assertTrue(local.paymentStarted)
            assertFalse(local.onchainAcceptanceVerified)
            val backup = Json.decodeFromString<PaykitPaymentStateBackup.Proof>(
                """{"identity":"alice","requestId":{"paymentRequestId":"request","counterparty":"bob"},"paymentEndpointIdentifier":"bitcoin-onchain","kind":"bitcoin-onchain-txid","paymentStarted":true,"onchainMatchingTransactionIdsBeforeAttempt":[]}""",
            )
            assertEquals("", backup.restored().paymentAppId)
            assertEquals(local.requestId, backup.restored().requestId)
            assertFalse(backup.restored().onchainAcceptanceVerified)
        }
    }

    @Test
    fun `allowance proof keeps its allowance id through backup`() {
        WalletScope.pushTestOverride("wallet0").use {
            val backup = Json.decodeFromString<PaykitPaymentStateBackup.Proof>(
                """
                    {"identity":"alice","requestId":{"paymentRequestId":"request","counterparty":"bob"},
                    "paymentEndpointIdentifier":"bitcoin-bolt11","kind":"bitcoin-bolt11-preimage","paymentStarted":true,
                    "onchainMatchingTransactionIdsBeforeAttempt":[],"allowanceId":"allowance-1"}
                """.trimIndent(),
            )
            val restored = backup.restored()
            assertEquals("allowance-1", restored.allowanceId)
            val json = Json.encodeToString(PaykitPaymentStateBackup.Proof(restored))
            assertContains(json, "\"allowanceId\":\"allowance-1\"")
        }
    }

    @Test
    fun `shared active operation survives payment backup reader writer roundtrip`() {
        val wire = """
            {"subscriptions":{},"pendingProofs":[],"activeOnchainAttempt":{"version":1,"wallet":{"kind":"software","network":"regtest","binding":"${"ab".repeat(
            32
        )}","sourceIndex":"0"},"attemptId":"00000000-0000-4000-8000-000000000001","requestId":null,"orderId":null,"payerIdentity":null,"address":"bcrt1qrecipient","amountSats":"20000","isMaxAmount":false,"status":"unknown","txid":"${"cd".repeat(
            32
        )}","rejectionReason":null,"originalInputs":[{"txid":"${"ef".repeat(
            32
        )}","vout":"0"}],"candidateTxids":["${"cd".repeat(
            32
        )}"],"feeRateSatsPerVByte":"1","followup":null,"transfer":null}}
        """.trimIndent()
        val codec = Json { ignoreUnknownKeys = true }
        val decoded = codec.decodeFromString<PaykitPaymentStateBackup>(wire)
        assertContains(codec.encodeToString(decoded), "\"activeOnchainAttempt\"")
    }

    @Test
    fun `wallet envelope preserves core recovery data`() {
        val fixture = """
            {"version":1,"createdAt":1,"transfers":[],
             "usdtWallet":"{\"identity\":\"42161:wallet\",\"transfers\":[]}"}
        """.trimIndent()
        val decoded = Json.decodeFromString<WalletBackupV1>(fixture)
        assertEquals("""{"identity":"42161:wallet","transfers":[]}""", decoded.usdtWallet)
        assertEquals(decoded, Json.decodeFromString<WalletBackupV1>(Json.encodeToString(decoded)))
        val withoutUsdt = """{"version":1,"createdAt":1,"transfers":[]}"""
        assertEquals(null, Json.decodeFromString<WalletBackupV1>(withoutUsdt).usdtWallet)
    }

    @Test
    fun `usdt backup preserves shared wire format`() {
        val fixture = usdtBackupFixture
        val backup = Json.decodeFromString<PaykitUsdtStateBackup>(fixture)
        val attempt = backup.attempts.single()
        val receipt = backup.receipts.single().restored()
        assertTrue(attempt.paymentStarted)
        assertEquals("bitkit", attempt.binding?.paymentAppId)
        assertEquals("quote", attempt.binding?.conversionQuoteId)
        assertEquals("2026-10-01T00:00:00.125Z", attempt.billingPeriod?.sdkValue?.startsAt)
        assertEquals(50_000uL, receipt.amount.atomic)
        assertEquals(attempt.requestId, receipt.requestId)
        val rebuilt = PaykitUsdtStateBackup(listOf(attempt), listOf(PaykitUsdtStateBackup.Receipt(receipt)))
        val json = Json { encodeDefaults = true }
        assertEquals(json.parseToJsonElement(fixture), json.parseToJsonElement(json.encodeToString(rebuilt)))
    }

    @Test
    fun `payment backup accepts shared wire format and retains pending payment`() {
        WalletScope.pushTestOverride("wallet0").use {
            val fixture = """
                {"subscriptions":{"alice":{"acceptances":[{"id":{"paymentRequestId":"request","counterparty":"bob"},"acceptedAt":"2026-09-24T10:00:00.123Z"}],"presentedProposalIds":[]}},"pendingProofs":[{"identity":"alice","requestId":{"paymentRequestId":"request","counterparty":"bob","billingPeriodStartsAt":"2026-09-24T10:00:00.100Z"},"paymentAppId":"bitkit","paymentEndpointIdentifier":"bitcoin-onchain","kind":"bitcoin-onchain-txid","paymentStarted":true,"billingPeriod":{"startsAt":"2026-09-24T10:00:00.100Z","endsAt":"2026-09-25T10:00:00.100Z"},"onchainMatchingTransactionIdsBeforeAttempt":[]}]}
            """.trimIndent()
            val backup = Json.decodeFromString<PaykitPaymentStateBackup>(fixture)
            val restored = backup.pendingProofs.single().restored()
            assertTrue(restored.paymentStarted)
            assertFalse(restored.onchainAcceptanceVerified)
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
                acceptedOneTimeRequests = mapOf("alice" to setOf(PaykitPaymentRequestId("one-time", "bob"))),
            )
            val decoded = Json.decodeFromString<PaykitPaymentStateBackup>(Json.encodeToString(rebuilt))
            assertEquals(restored, decoded.pendingProofs.single().restored())
            assertEquals(backup.subscriptions, decoded.subscriptions)
            assertEquals(rebuilt.acceptedOneTimeRequests, decoded.acceptedOneTimeRequests)

            val verifiedProof = restored.copy(onchainAcceptanceVerified = true)
            val verifiedBackup = PaykitPaymentStateBackup.Proof(verifiedProof)
            val verifiedJson = Json.encodeToString(verifiedBackup)
            assertContains(verifiedJson, "\"onchainAcceptanceVerified\":true")
            assertTrue(
                Json.decodeFromString<PaykitPaymentStateBackup.Proof>(verifiedJson).restored().onchainAcceptanceVerified
            )

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
    private val usdtBackupFixture = """
            {
              "attempts": [
                {
                  "quoteId": "operation",
                  "wallet": "wallet",
                  "identity": "alice",
                  "contact": "bob",
                  "requestId": {
                    "paymentRequestId": "request",
                    "counterparty": "bob",
                    "billingPeriodStartsAt": "2026-10-01T00:00:00.125Z"
                  },
                  "binding": {
                    "payer": "alice",
                    "payee": "bob",
                    "paymentAppId": "bitkit",
                    "paymentRequestId": "request",
                    "paymentReference": "invoice",
                    "paymentEndpointIdentifier": "usdt-arbitrum-address",
                    "periodStartsAt": "2026-10-01T00:00:00.125Z",
                    "periodEndsAt": "2026-11-01T00:00:00.125Z",
                    "conversionQuoteId": "quote"
                  },
                  "billingPeriod": {
                    "startsAt": "2026-10-01T00:00:00.125Z",
                    "endsAt": "2026-11-01T00:00:00.125Z"
                  },
                  "proof": null,
                  "proofQueued": false,
                  "paymentStarted": true
                }
              ],
              "receipts": [
                {
                  "wallet": "wallet",
                  "identity": "alice",
                  "requestId": {
                    "paymentRequestId": "request",
                    "counterparty": "bob",
                    "billingPeriodStartsAt": "2026-10-01T00:00:00.125Z"
                  },
                  "paymentId": "42161:transaction:2",
                  "proofEventId": "proof",
                  "verified": true,
                  "transferId": "transfer",
                  "amountAtomic": 50000,
                  "receivedAtMillis": 1790812801000,
                  "underpaid": false,
                  "afterExpiry": false
                }
              ]
            }
    """.trimIndent()
}

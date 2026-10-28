package to.bitkit.models

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import to.bitkit.repositories.PaykitAllowanceLimits
import to.bitkit.repositories.PaykitAllowanceLocalState
import to.bitkit.repositories.PaykitAllowanceLocalState.Stage
import to.bitkit.repositories.PaykitPaymentRequestId
import java.util.Locale
import kotlin.test.assertEquals

class PaykitAllowanceStateBackupTest {
    companion object {
        private val REQUEST_ID = PaykitPaymentRequestId("request-1", "pubky" + "y".repeat(52))
        private const val CREATED_AT_MILLIS = 1_793_062_800_000L

        /** The wire format both apps read and write; field names and encodings must not drift. */
        private val GOLDEN = """
            {"groups":[{"id":"group-1","counterparty":"pubky${"y".repeat(52)}",
            "limits":{"perPaymentUsd":"5","monthlyUsd":"50","perPaymentSats":5000,"monthlySats":50000},
            "allowanceIds":["allowance-1"],"createdAt":"2026-10-27T01:00:00.000Z"}],
            "journal":[{"attemptId":"attempt-1","isAutomatic":true,
            "requestId":{"paymentRequestId":"request-1","counterparty":"pubky${"y".repeat(52)}",
            "billingPeriodStartsAt":null},"allowanceId":"allowance-1","amountSats":1000,
            "paymentEndpointIdentifier":"btc-lightning-bolt11","paymentHash":"${"ab".repeat(32)}",
            "onchainAddress":null,"transactionId":null,"stage":"succeeded","createdAt":"2026-10-27T01:00:00.000Z",
            "paymentListVersion":4,"previousPaymentListVersion":3}],
            "autoPaidSatsByAllowance":{"pubky${"y".repeat(52)}/allowance-1":1000},
            "autoPaidRequestIds":[{"paymentRequestId":"request-1","counterparty":"pubky${"y".repeat(52)}",
            "billingPeriodStartsAt":null}],
            "presentedProposalIds":["proposal-1"],"unannouncedOfferIds":["pubky${"y".repeat(52)}/allowance-2"],
            "notifiedRequestIds":["request-2"],
            "lastTrustedTime":"2026-10-27T01:00:00.000Z"}
        """.trimIndent().replace("\n", "")
    }

    private val json = Json { encodeDefaults = true }

    @Test
    fun `allowance state survives the backup in the shared wire format`() {
        val state = localState()

        val wire = json.encodeToString(PaykitAllowanceStateBackup(state))

        assertEquals(json.parseToJsonElement(GOLDEN).jsonObject, json.parseToJsonElement(wire) as JsonObject)
        assertEquals(state, json.decodeFromString<PaykitAllowanceStateBackup>(GOLDEN).restored())
    }

    @Test
    fun `a backup without lasting totals rebuilds them from its journal`() {
        val fromIos = json.decodeFromString<PaykitAllowanceStateBackup>(GOLDEN)
            .copy(autoPaidSatsByAllowance = emptyMap(), autoPaidRequestIds = emptySet())

        val restored = fromIos.restored()

        assertEquals(mapOf("${REQUEST_ID.counterparty}/allowance-1" to 1_000uL), restored.autoPaidSatsByAllowance)
        assertEquals(setOf(REQUEST_ID), restored.autoPaidRequestIds)
    }

    @Test
    fun `a journal entry that omits its optional fields still restores`() {
        val wire = """
            {"journal":[{"attemptId":"manual-1","isAutomatic":false,
            "requestId":{"paymentRequestId":"request-1","counterparty":"${REQUEST_ID.counterparty}"},
            "amountSats":1000,"paymentEndpointIdentifier":"btc-regtest-p2wpkh","stage":"sent",
            "createdAt":"2026-10-27T01:00:00.000Z"}]}
        """.trimIndent().replace("\n", "")

        val entry = json.decodeFromString<PaykitAllowanceStateBackup>(wire).restored().journal.single()

        assertEquals(null, entry.allowanceId)
        assertEquals(null, entry.paymentListVersion)
        assertEquals(Stage.SENT, entry.stage)
    }

    @Test
    fun `a journal stage this version does not know restores as unknown`() {
        val wire = GOLDEN.replace("\"stage\":\"succeeded\"", "\"stage\":\"settling\"")

        val restored = json.decodeFromString<PaykitAllowanceStateBackup>(wire).restored()

        assertEquals(Stage.UNKNOWN, restored.journal.single().stage)
    }

    @Test
    fun `journal stages are written in ASCII under any device locale`() {
        val default = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        try {
            val state = localState().let { it.copy(journal = it.journal.map { e -> e.copy(stage = Stage.SUBMITTED) }) }

            val backup = PaykitAllowanceStateBackup(state)

            assertEquals("submitted", backup.journal.single().stage)
        } finally {
            Locale.setDefault(default)
        }
    }

    private fun localState() = PaykitAllowanceLocalState(
        groups = listOf(
            PaykitAllowanceLocalState.Group(
                id = "group-1",
                counterparty = REQUEST_ID.counterparty,
                limits = PaykitAllowanceLimits(5, 50, 5_000uL, 50_000uL),
                allowanceIds = listOf("allowance-1"),
                createdAtMillis = CREATED_AT_MILLIS,
            ),
        ),
        journal = listOf(
            PaykitAllowanceLocalState.JournalEntry(
                attemptId = "attempt-1",
                isAutomatic = true,
                requestId = REQUEST_ID,
                allowanceId = "allowance-1",
                amountSats = 1_000uL,
                paymentEndpointIdentifier = "btc-lightning-bolt11",
                paymentHash = "ab".repeat(32),
                stage = Stage.SUCCEEDED,
                createdAtMillis = CREATED_AT_MILLIS,
                paymentListVersion = 4uL,
                previousPaymentListVersion = 3uL,
            ),
        ),
        presentedProposalIds = setOf("proposal-1"),
        acceptingOfferIds = setOf("${REQUEST_ID.counterparty}/allowance-2"),
        notifiedRequestIds = setOf("request-2"),
        lastTrustedTimeMillis = CREATED_AT_MILLIS,
        autoPaidSatsByAllowance = mapOf("${REQUEST_ID.counterparty}/allowance-1" to 1_000uL),
        autoPaidRequestIds = setOf(REQUEST_ID),
    )
}

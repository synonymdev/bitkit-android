package to.bitkit.ext

import com.synonym.bitkitcore.BroadcastException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test
import to.bitkit.utils.AppError
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BroadcastExceptionExtTest {
    @Test
    fun `electrum broadcast failures are connectivity failures`() {
        val error = AppError(BroadcastException.ElectrumException("DNS lookup failed"))

        assertTrue(error.isBroadcastConnectivityFailure())
    }

    @Test
    fun `broadcast timeout failures are connectivity failures`() = runTest {
        val timeout = runCatching { withTimeout(0) { Unit } }.exceptionOrNull() as TimeoutCancellationException

        assertTrue(timeout.isBroadcastConnectivityFailure())
    }

    @Test
    fun `unrelated broadcast failures are not connectivity failures`() {
        val error = AppError(BroadcastException.InvalidTransaction("bad tx"))

        assertFalse(error.isBroadcastConnectivityFailure())
    }

    @Test
    fun `only invalid raw transactions are definite broadcast failures`() {
        val invalidTransactions = listOf(
            BroadcastException.InvalidHex("bad hex"),
            BroadcastException.InvalidTransaction("bad tx"),
        )
        for (error in invalidTransactions) {
            assertTrue(AppError(error).isDefiniteHardwarePreBroadcastFailure())
        }
        assertFalse(
            BroadcastException.ElectrumException("Broadcast failed: disconnected")
                .isDefiniteHardwarePreBroadcastFailure()
        )
        assertFalse(AppError("unknown result").isDefiniteHardwarePreBroadcastFailure())
    }

    @Test
    fun `recognized backend refusals allow navigation without proving no dispatch`() {
        val reasons = listOf(
            "min relay fee not met",
            "mempool min fee not met",
            "bad-txns-inputs-missingorspent",
            "txn-mempool-conflict",
            "non-final",
        )
        for (reason in reasons) {
            val error = AppError(BroadcastException.ElectrumException("broadcast failed: $reason"))
            assertTrue(error.isHardwareBroadcastRefusalForNavigation())
            assertFalse(error.isDefiniteHardwarePreBroadcastFailure())
        }
    }

    @Test
    fun `unknown and connectivity errors retain the navigation guard`() {
        val errors = listOf(
            "broadcast failed: disconnected",
            "broadcast failed: unknown refusal",
            "response lost after dispatch",
            "min relay fee not met",
        )
        for (details in errors) {
            val error = AppError(BroadcastException.ElectrumException(details))
            assertFalse(error.isHardwareBroadcastRefusalForNavigation())
        }
        assertFalse(AppError("unknown result").isHardwareBroadcastRefusalForNavigation())
    }

    @Test
    fun `wrapped Electrum server refusals allow navigation`() {
        val messages = listOf(
            "the transaction was rejected by network rules.\n\nmin relay fee not met, 100 < 110",
            "sendrawtransaction RPC error: {\"code\":-26,\"message\":\"bad-txns-inputs-missingorspent\"}",
            "mempool min fee not met, 110 < 300",
        )
        for (message in messages) {
            val payload = kotlinx.serialization.json.buildJsonObject {
                put("code", kotlinx.serialization.json.JsonPrimitive(1))
                put("message", kotlinx.serialization.json.JsonPrimitive(message))
            }
            val error = BroadcastException.ElectrumException("Broadcast failed: Electrum server error: $payload")
            assertTrue(error.isHardwareBroadcastRefusalForNavigation())
            assertFalse(error.isDefiniteHardwarePreBroadcastFailure())
        }
    }

    @Test
    fun `malformed unknown and incidental Electrum messages stay guarded`() {
        val payloads = listOf(
            "Broadcast failed: Electrum server error: {invalid JSON min relay fee not met}",
            "Broadcast failed: Electrum server error: {\"message\":\"disconnected\"}",
            "Broadcast failed: Electrum server error: {\"message\":\"unknown refusal\"}",
            "Broadcast failed: Electrum server error: {\"message\":{},\"reason\":\"min relay fee not met\"}",
            "Broadcast failed: Electrum server error: {\"message\":\"non-finalized response lost\"}",
        )
        for (payload in payloads) {
            assertFalse(BroadcastException.ElectrumException(payload).isHardwareBroadcastRefusalForNavigation())
        }
    }

    @Test
    fun `reported missing inputs JSON string coded RPC refusal allows navigation only`() {
        assertReportedRefusal(
            """Broadcast failed: Electrum server error: "sendrawtransaction RPC error -25: """ +
                """bad-txns-inputs-missingorspent"""",
        )
    }

    @Test
    fun `reported replacement fee JSON string coded RPC refusal allows navigation only`() {
        assertReportedRefusal(
            """Broadcast failed: Electrum server error: "sendrawtransaction RPC error -26: """ +
                """insufficient fee, rejecting replacement """ +
                """b3f63e62aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa; new feerate """ +
                """0.00001000 BTC/kvB <= old feerate 0.00001018 BTC/kvB"""",
        )
    }

    private fun assertReportedRefusal(payload: String) {
        val error = BroadcastException.ElectrumException(payload)
        assertTrue(error.isHardwareBroadcastRefusalForNavigation(), payload)
        assertFalse(error.isDefiniteHardwarePreBroadcastFailure())
    }

    @Test
    fun `coded RPC unknown malformed and replacement lookalikes stay guarded`() {
        val messages = listOf(
            "sendrawtransaction RPC error -25: disconnected",
            "sendrawtransaction RPC error unknown: bad-txns-inputs-missingorspent",
            "sendrawtransaction RPC error -25 bad-txns-inputs-missingorspent",
            "sendrawtransaction RPC error -25: bad-txns-inputs-missingorspentish",
            "sendrawtransaction RPC error -26: insufficient fee information unavailable",
            "sendrawtransaction RPC error -26: insufficient fee, rejecting replacementish",
            "sendrawtransaction RPC error -26: unknown result containing insufficient fee, rejecting replacement tx",
        )
        for (message in messages) {
            val payload = kotlinx.serialization.json.JsonPrimitive(message)
            val error = BroadcastException.ElectrumException("Broadcast failed: Electrum server error: $payload")
            assertFalse(error.isHardwareBroadcastRefusalForNavigation(), message)
        }
        val malformed = "Broadcast failed: Electrum server error: \"sendrawtransaction RPC error -25: " +
            "bad-txns-inputs-missingorspent"
        assertFalse(BroadcastException.ElectrumException(malformed).isHardwareBroadcastRefusalForNavigation())
    }

    @Test
    fun `replacement refusal requires complete txid and exact feerate grammar`() {
        val txid = "b3f63e62" + "a".repeat(56)
        val known = "insufficient fee, rejecting replacement $txid; " +
            "new feerate 0.00001000 BTC/kvB <= old feerate 0.00001018 BTC/kvB"
        val messages = listOf(
            known.replace(txid, txid.dropLast(1)),
            known.replace(txid, "g" + txid.drop(1)),
            known.replace(txid, "b3f63e62…"),
            known.replace("0.00001000", "0.0000100"),
            known.replace("0.00001018", "0.000010180"),
            known.replace("0.00001000", "-0.00001000"),
            known.replace("BTC/kvB", "sats/vB"),
            known.replace("<=", "<"),
            "$known disconnected",
            "$known\nunknown result",
            "insufficient fee, rejecting replacement disconnected",
        )
        for (message in messages) {
            val payload = kotlinx.serialization.json.JsonPrimitive("sendrawtransaction RPC error -26: $message")
            val error = BroadcastException.ElectrumException("Broadcast failed: Electrum server error: $payload")
            assertFalse(error.isHardwareBroadcastRefusalForNavigation(), message)
        }
    }
}

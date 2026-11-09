package to.bitkit.services

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.synonym.bitkitcore.UsdtBackup
import com.synonym.bitkitcore.UsdtDestination
import com.synonym.bitkitcore.UsdtException
import com.synonym.bitkitcore.UsdtWallet
import com.synonym.bitkitcore.usdtAddress
import com.synonym.vssclient.vssDeriveStoreId
import com.synonym.vssclient.vssGet
import com.synonym.vssclient.vssListKeys
import com.synonym.vssclient.vssNewClientWithLnurlAuth
import com.synonym.vssclient.vssStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import to.bitkit.data.backup.UsdtRecoveryBackupStore
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.PaykitUsdtStateBackup
import to.bitkit.test.annotations.DeviceIntegration
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Opt-in protocol check with a public seed, isolated databases and real encrypted VSS transport. */
@RunWith(AndroidJUnit4::class)
@DeviceIntegration
class UsdtNativeRecoveryIntegrationTest {
    companion object {
        private const val FIXTURE = "http://127.0.0.1:18557"
        private const val MNEMONIC = "test test test test test test test test test test test junk"
    }

    @Test
    fun encryptedRecoveryAgainstLocalProtocolFixture(): Unit = runBlocking {
        withTimeout(180_000) {
            val driver = runCatching { request("$FIXTURE/android-driver/control") }.getOrNull()
            assumeTrue("Requires the owned local recovery protocol fixture", driver != null)
            val scenario = requireNotNull(driver)["scenario"]?.jsonPrimitive?.content
            val phase = driver["phase"]?.jsonPrimitive?.content
            assumeTrue("The fixture must select a scenario and process phase", scenario != null && phase != null)
            require(requireNotNull(scenario).matches(Regex("[a-z0-9-]{1,64}")))
            val url = "$FIXTURE/$scenario"
            val phaseStart = events(url).size
            val context = ApplicationProvider.getApplicationContext<Context>()
            val directory = File(context.cacheDir, "usdt-recovery-$scenario").apply { mkdirs() }
            val storeId = vssDeriveStoreId("bitkit_v1_regtest", MNEMONIC, null)
            vssNewClientWithLnurlAuth(url, storeId, MNEMONIC, null, "$url/auth")
            val store = UsdtRecoveryBackupStore(
                put = { key, value -> vssStore(key, value) },
                keys = { vssListKeys(null).map { it.key } },
                get = { key -> vssGet(key)?.value },
            )
            val wallet = UsdtWallet(
                address = usdtAddress(MNEMONIC, null),
                storagePath = File(directory, if (phase == "restore") "restored.sqlite" else "original.sqlite").path,
                rpcUrl = "$url/rpc",
                bundlerUrl = "$url/rpc",
                bridgeUrl = null,
                backup = object : UsdtBackup {
                    override suspend fun persist(snapshot: String) {
                        runSuspendCatching {
                            store.persist(snapshot, PaykitUsdtStateBackup(emptyList(), emptyList()))
                        }.getOrElse {
                            throw UsdtException.BackupUnavailable()
                        }
                    }
                },
            )
            try {
                val saved = File(directory, "signed-snapshot.json")
                if (phase == "prepare") {
                    assertTrue(wallet.history().isEmpty(), "Each scenario needs a new isolated database")
                    val mode = requireNotNull(request("$url/control")["mode"]).jsonPrimitive.content
                    request("$url/control", buildJsonObject { put("faultMode", mode) })
                    request("$url/control", buildJsonObject { put("mode", "accept") })
                    vssStore("WALLET", "complete-wallet-backup".encodeToByteArray())
                    assertEquals("complete-wallet-backup", vssGet("WALLET")?.value?.decodeToString())
                    request("$url/control", buildJsonObject { put("mode", mode) })
                    val quote = wallet.quoteTransfer(
                        "0x1111111111111111111111111111111111111111",
                        1_000_000uL,
                        UsdtDestination.ARBITRUM,
                    )
                    val original = expectBackupFailure(wallet, store, url, mode) {
                        wallet.send(quote.id, MNEMONIC, null)
                    }
                    saved.writeText(original)
                    request("$url/control", buildJsonObject { put("expectedSnapshot", original) })
                    request("$url/control", buildJsonObject { put("advance", 3) })
                    repeat(2) {
                        val snapshot = expectBackupFailure(wallet, store, url, mode) { wallet.refreshTransfers() }
                        assertEquals(signedOperation(original), signedOperation(snapshot))
                    }
                    assertTrue(requireNotNull(request("$url/control")["operations"]).jsonArray.isEmpty())
                    assertEquals(1, wallet.history().size)
                } else {
                    val configured = request("$url/control")
                    val original = configured.getValue("expectedSnapshot").jsonPrimitive.content
                    val mode = configured.getValue("faultMode").jsonPrimitive.content
                    request(
                        "$url/control",
                        buildJsonObject {
                            put("mode", mode)
                            put("advance", 3)
                        },
                    )
                    if (phase == "restore") {
                        assertTrue(wallet.history().isEmpty())
                        store.restore(
                            legacyWallet = null,
                            legacyPaymentState = null,
                            restoreWallet = { wallet.restoreBackup(it) },
                            restorePayments = { assertTrue(it.attempts.isEmpty() && it.receipts.isEmpty()) },
                        )
                    } else {
                        assertEquals("resume", phase)
                    }
                    assertEquals(signedOperation(original), signedOperation(wallet.exportBackup()))
                    val snapshot = expectBackupFailure(wallet, store, url, mode) { wallet.refreshTransfers() }
                    assertEquals(signedOperation(original), signedOperation(snapshot))
                    assertFalse(events(url).drop(phaseStart).any { eventType(it) == "broadcast-attempt" })
                    val recovery = buildJsonObject {
                        put("mode", "accept")
                        put("advance", 3)
                    }
                    request("$url/control", recovery)
                    wallet.refreshTransfers()
                    wallet.refreshTransfers()
                    val state = request("$url/control")
                    val operations = requireNotNull(state["operations"]).jsonArray
                    assertEquals(1, operations.size, "Retries may only resubmit the original signed operation")
                    assertEquals(signedOperation(original), operations.single().jsonObject["operation"])
                    val allEvents = state.getValue("events").jsonArray.map { it.jsonObject }
                    val initialKeys = allEvents.first { eventType(it) == "backup-acknowledged" }["keys"]
                    val phaseEvents = allEvents.drop(phaseStart)
                    val firstAttempt = phaseEvents.indexOfFirst { eventType(it) == "broadcast-attempt" }
                    assertTrue(firstAttempt >= 0, "This process must submit the restored operation")
                    assertTrue(
                        phaseEvents.take(firstAttempt).any {
                            eventType(it) == "backup-acknowledged" && it["keys"] != initialKeys
                        },
                        "This process must acknowledge its recovery record before attempting submission",
                    )
                    assertTrue(phaseEvents.any { eventType(it) == "broadcast" })
                    phaseEvents.filter { eventType(it) == "broadcast-attempt" }.forEach {
                        assertEquals(signedOperation(original), it["operation"])
                    }
                    assertEquals("complete-wallet-backup", vssGet("WALLET")?.value?.decodeToString())
                    assertEquals(1, wallet.history().size)
                }
                File(directory, "$phase-public-fixture-snapshot.json").writeText(wallet.exportBackup())
            } finally {
                wallet.close()
            }
        }
    }

    private fun events(url: String) = request("$url/control").getValue("events").jsonArray.map { it.jsonObject }

    private suspend fun expectBackupFailure(
        wallet: UsdtWallet,
        store: UsdtRecoveryBackupStore,
        url: String,
        mode: String,
        operation: suspend () -> Unit,
    ): String {
        val before = request("$url/control")
        val checkpoint = before.getValue("events").jsonArray.size
        assertFailsWith<UsdtException.BackupUnavailable> { operation() }
        val snapshot = wallet.exportBackup()
        val after = request("$url/control")
        val newEvents = after.getValue("events").jsonArray.drop(checkpoint).map { it.jsonObject }
        val fault = if (mode == "reject") "backup-rejected" else "backup-ack-dropped"
        assertTrue(newEvents.any { eventType(it) == fault }, "Every retry must reach the requested VSS fault")
        assertFalse(newEvents.any { eventType(it) == "broadcast-attempt" })
        assertFalse(newEvents.any { eventType(it) == "backup-acknowledged" })
        if (mode == "reject") {
            assertEquals(before["stores"], after["stores"])
        } else {
            assertEquals("commit-drop", mode)
            val records = store.snapshots()
            assertTrue(
                records.isNotEmpty(),
                "The server committed encrypted recovery data despite losing its acknowledgement",
            )
            records.forEach { assertEquals(signedOperation(snapshot), signedOperation(it.usdtWallet)) }
        }
        return snapshot
    }

    private fun signedOperation(snapshot: String) = Json.parseToJsonElement(snapshot).jsonObject
        .getValue("transfers").jsonArray.single().jsonObject.getValue("plan").jsonObject.getValue("operation")

    private fun eventType(event: JsonObject) = event["type"]?.jsonPrimitive?.content

    private fun request(address: String, body: JsonObject? = null): JsonObject {
        val connection = URI(address).toURL().openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 3_000
            connection.readTimeout = 3_000
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().encodeToByteArray()) }
            }
            assertEquals(200, connection.responseCode)
            connection.inputStream.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        } finally {
            connection.disconnect()
        }
    }
}

package to.bitkit.data.backup

import com.synonym.bitkitcore.UsdtException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Test
import to.bitkit.models.PaykitUsdtStateBackup
import java.io.IOException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UsdtRecoveryBackupStoreTest {
    private val payments = PaykitUsdtStateBackup(emptyList(), emptyList())

    @Test
    fun `independent writers preserve all snapshots and complete wallet backup`() = runTest {
        val remote = mutableMapOf("WALLET" to "complete-wallet-backup".encodeToByteArray())
        val first = store(remote)
        val second = store(remote)
        awaitAll(
            async { first.persist("signed-one", payments) },
            async { second.persist("signed-two", payments) },
        )
        assertContentEquals("complete-wallet-backup".encodeToByteArray(), remote["WALLET"])
        assertEquals(setOf("signed-one", "signed-two"), store(remote).snapshots().map { it.usdtWallet }.toSet())
    }

    @Test
    fun `uncertain acknowledgement fails then restarted retry writes the same record`() = runTest {
        val remote = mutableMapOf<String, ByteArray>()
        val interrupted = UsdtRecoveryBackupStore(
            put = { key, data ->
                remote[key] = data
                throw IOException("acknowledgement lost")
            },
            keys = { remote.keys.toList() },
            get = { remote[it] },
        )
        assertFailsWith<IOException> { interrupted.persist("same-signed-payment", payments) }
        val restarted = store(remote)
        restarted.persist("same-signed-payment", payments)
        assertEquals(1, remote.size)
        assertEquals("same-signed-payment", restarted.snapshots().single().usdtWallet)
    }

    @Test
    fun `failed upload retains previous acknowledged snapshots`() = runTest {
        val remote = mutableMapOf<String, ByteArray>()
        store(remote).persist("previous", payments)
        val unavailable = UsdtRecoveryBackupStore(
            put = { _, _ -> throw IOException("VSS unavailable") },
            keys = { remote.keys.toList() },
            get = { remote[it] },
        )
        assertFailsWith<IOException> { unavailable.persist("pending", payments) }
        assertEquals(listOf("previous"), store(remote).snapshots().map { it.usdtWallet })
    }

    @Test
    fun `unknown future versions cannot be silently skipped`() = runTest {
        val remote = mutableMapOf("USDT_RECOVERY_V2_future" to "future".encodeToByteArray())
        assertFailsWith<UsdtException.InvalidBackup> { store(remote).snapshots() }
    }

    @Test
    fun `missing and corrupt records fail restore`() = runTest {
        val remote = mutableMapOf<String, ByteArray>()
        store(remote).persist("signed", payments)
        val missing = UsdtRecoveryBackupStore(put = { _, _ -> }, keys = { remote.keys.toList() }, get = { null })
        assertFailsWith<UsdtException.InvalidBackup> { missing.snapshots() }
        remote[remote.keys.single()] = "corrupt".encodeToByteArray()
        assertFailsWith<UsdtException.InvalidBackup> { store(remote).snapshots() }
    }

    @Test
    fun `interrupted migration replays legacy and every journal snapshot after restart`() = runTest {
        val remote = mutableMapOf<String, ByteArray>()
        store(remote).persist("signed-one", payments)
        store(remote).persist("signed-two", payments)
        for (failureAt in 0 until 6) {
            var step = 0
            val restored = mutableSetOf<String>()
            assertFailsWith<IOException> {
                store(remote).restore(
                    legacyWallet = "legacy",
                    legacyPaymentState = payments,
                    restoreWallet = {
                        if (step++ == failureAt) throw IOException("Interrupted wallet restore")
                        restored.add(it)
                    },
                    restorePayments = {
                        if (step++ == failureAt) throw IOException("Interrupted association restore")
                    },
                )
            }
            store(remote).restore(
                legacyWallet = "legacy",
                legacyPaymentState = payments,
                restoreWallet = { restored.add(it) },
                restorePayments = {},
            )
            assertEquals(setOf("legacy", "signed-one", "signed-two"), restored)
        }
    }

    @Test
    fun `iOS and Android envelope encodings restore with their original content hashes`() = runTest {
        val android = "{\"version\":1,\"usdtWallet\":\"{\\\"identity\\\":\\\"42161:0x11111111111111" +
            "11111111111111111111111111\\\",\\\"transfers\\\":[]}\",\"paykitUsdtState\"" +
            ":{\"attempts\":[],\"receipts\":[]}}"
        val ios = "{\"paykitUsdtState\":{\"attempts\":[],\"receipts\":[]},\"usdtWallet\":\"{\\" +
            "\"identity\\\":\\\"42161:0x1111111111111111111111111111111111111111\\\"," +
            "\\\"transfers\\\":[]}\",\"version\":1}"
        val remote = mutableMapOf(
            "USDT_RECOVERY_V1_c3b84c73c7a42be59f530edc24ed338ebb15475f10229a7c3fe49125beb532c2" to
                android.encodeToByteArray(),
            "USDT_RECOVERY_V1_54006dcc1f1a5af1d8c31954fee58bc3ab35f5c3c0d3cb81b63e4fd7c3ea9c5c" to
                ios.encodeToByteArray(),
        )
        val restored = store(remote).snapshots()
        assertEquals(2, restored.size)
        val wallet = """{"identity":"42161:0x1111111111111111111111111111111111111111","transfers":[]}"""
        assertEquals(setOf(wallet), restored.map { it.usdtWallet }.toSet())
        assertEquals(listOf(payments, payments), restored.map { it.paykitUsdtState })
    }

    private fun store(remote: MutableMap<String, ByteArray>) = UsdtRecoveryBackupStore(
        put = { key, data -> remote[key] = data },
        keys = { remote.keys.toList() },
        get = { remote[it] },
    )
}

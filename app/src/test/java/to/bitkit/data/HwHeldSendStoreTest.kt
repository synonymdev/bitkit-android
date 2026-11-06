package to.bitkit.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import to.bitkit.di.json
import to.bitkit.test.BaseUnitTest
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HwHeldSendStoreTest : BaseUnitTest() {
    @get:Rule
    val folder = TemporaryFolder()

    private val held = HeldHwSend(
        walletId = "wallet-trezor",
        txId = "AB",
        address = "address",
        amountSats = 25_000uL,
        satsPerVByte = 2uL,
        serializedTx = "00",
        miningFeeSats = 200uL,
        feeRate = 2uL,
        totalSpent = 25_200uL,
    )

    @Test
    fun `a held send is replaced by its transaction and released by it`() = test {
        val sut = store()

        sut.holdSend(held)
        sut.holdSend(held.copy(tags = listOf("rent")))
        assertEquals(listOf(held.copy(tags = listOf("rent"))), sut.load().heldSends)

        sut.releaseHeldSends(setOf("ab"))
        assertTrue(sut.load().heldSends.isEmpty())
    }

    @Test
    fun `a file that cannot be read fails every read and write until it is cleared`() = test {
        val file = folder.newFile("held.json").apply { writeText("{not json") }
        val sut = store(file)

        assertNull(assertFailsWith<HwHeldSendsUnreadableError> { sut.load() }.walletId)
        assertFailsWith<HwHeldSendsUnreadableError> { sut.holdSend(held) }
        assertFailsWith<HwHeldSendsUnreadableError> { sut.releaseHeldSends(setOf("ab")) }
        assertEquals("{not json", file.readText())

        sut.clearUnreadable("wallet-trezor")

        assertEquals(HwHeldSendData(), sut.load())
    }

    @Test
    fun `an unreadable record is kept through writes and refuses only its wallet until cleared`() = test {
        val file = folder.newFile("held.json")
        val readable = json.encodeToString(HeldHwSend.serializer(), held).dropLast(1) + ""","futureField":1}"""
        file.writeText(
            """{"version":1,"heldSends":[$readable,{"walletId":"wallet-jade","txId":7}]}""",
        )
        val sut = store(file)

        val data = sut.load()
        assertEquals(listOf(held), data.heldSends)
        assertTrue(data.hasUnreadableFor("wallet-jade"))
        assertFalse(data.hasUnreadableFor("wallet-trezor"))

        sut.holdSend(held.copy(txId = "CD"))
        val written = json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(1, written.getValue("unreadable").jsonArray.size)
        assertTrue(store(file, reopen = true).load().hasUnreadableFor("wallet-jade"))

        sut.clearUnreadable("wallet-jade")
        assertFalse(sut.load().hasUnreadableFor("wallet-jade"))
        assertEquals(2, sut.load().heldSends.size)
    }

    @Test
    fun `a record an earlier version could not read is read again`() = test {
        val file = folder.newFile("held.json")
        val record = json.encodeToString(HeldHwSend.serializer(), held)
        file.writeText("""{"version":1,"heldSends":[],"unreadable":[$record]}""")

        val data = store(file).load()

        assertEquals(listOf(held), data.heldSends)
        assertTrue(data.unreadable.isEmpty())
    }

    @Test
    fun `an unreadable record tells what can still be read of it`() {
        val record = json.parseToJsonElement(
            """{"walletId":"wallet-jade","amountSats":5000,"address":"bc1q","txId":"AB","heldAt":1700000000}""",
        )
        val unreadable = HwHeldSendData(unreadable = listOf(record)).unreadableErrorFor("wallet-jade")

        assertEquals(listOf(UnreadableHeldSend(5_000uL, "bc1q", "AB", 1_700_000_000L)), unreadable?.records)
        assertFalse(unreadable?.clearsAllWallets ?: true)
        assertNull(HwHeldSendData(unreadable = listOf(record)).unreadableErrorFor("wallet-trezor"))
    }

    @Test
    fun `a record whose wallet cannot be read refuses every wallet`() {
        val data = HwHeldSendData(unreadable = listOf(JsonObject(mapOf("txId" to JsonPrimitive("AB")))))

        assertTrue(data.hasUnreadableFor("wallet-trezor"))
        assertTrue(data.hasUnreadableFor("wallet-jade"))
    }

    private var files = 0

    private fun store(
        file: File = folder.root.resolve("held-${files++}.json"),
        reopen: Boolean = false,
    ): HwHeldSendStore {
        // DataStore allows one instance per file, so a reopened store reads a copy of what was written
        val source = if (reopen) file.copyTo(folder.root.resolve("reopened-${files++}.json")) else file
        return HwHeldSendStore({ source }, testDispatcher)
    }
}

package to.bitkit.repositories

import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.keychain.Keychain
import to.bitkit.data.keychain.KeychainError
import to.bitkit.models.HwFundingSignedTx
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.SignedTransactionId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HwRetainedSendRepoTest : BaseUnitTest() {
    private companion object {
        val KEY = Keychain.Key.HW_RETAINED_SEND.name
        const val WALLET = "wallet-a"
        const val OTHER_WALLET = "wallet-b"
        const val ADDRESS = "bcrt1qrecipient"
        const val AMOUNT_SATS = 25_000uL
        const val CORRUPT_ENTRY = "{\"walletId\":\"wallet-a\",\"serializedTx\":\"deadbeef\""
    }

    private val keychain = mock<Keychain>()
    private val entries = mutableMapOf<String, String>()
    private val hex = requireNotNull(javaClass.getResourceAsStream("/hardware-signed-transaction.hex"))
        .bufferedReader().readText().trim()
    private val signedTx = signedTx(hex)
    private lateinit var sut: HwRetainedSendRepo

    @Before
    fun setUp() = test {
        whenever(keychain.loadScopedString(eq(KEY), any())).thenAnswer { entries[it.getArgument(1)] }
        whenever(keychain.upsertScopedString(eq(KEY), any(), any())).doSuspendableAnswer {
            entries[it.getArgument(2)] = it.getArgument(1)
        }
        whenever(keychain.deleteScoped(eq(KEY), any())).doSuspendableAnswer {
            entries.remove(it.getArgument<String>(1))
            Unit
        }
        whenever(keychain.wipe()).doSuspendableAnswer { entries.clear() }
        sut = HwRetainedSendRepo(testDispatcher, keychain)
    }

    @Test
    fun `a retained send survives a new repository and rebuilds what a rebroadcast needs`() = test {
        sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx).getOrThrow()

        val record = assertNotNull(HwRetainedSendRepo(testDispatcher, keychain).record(WALLET).getOrThrow())

        assertEquals(WALLET, record.walletId)
        assertEquals(ADDRESS, record.address)
        assertEquals(AMOUNT_SATS, record.amountSats)
        assertFalse(record.retryWasRefused)
        assertEquals(signedTx, record.signedTx)
        assertTrue(record.isSamePayment(ADDRESS, AMOUNT_SATS))
        assertFalse(record.isSamePayment("bcrt1qother", AMOUNT_SATS))
        assertFalse(record.isSamePayment(ADDRESS, AMOUNT_SATS + 1uL))
    }

    @Test
    fun `a wallet without a record reports none`() = test {
        assertNull(sut.record(WALLET).getOrThrow())
    }

    @Test
    fun `records stay separate per wallet`() = test {
        val otherSignedTx = signedTx("ab".repeat(100))
        sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx).getOrThrow()
        sut.retain(OTHER_WALLET, "bcrt1qother", 1_000uL, otherSignedTx).getOrThrow()

        sut.markRetryRefused(WALLET).getOrThrow()
        assertEquals(false, sut.record(OTHER_WALLET).getOrThrow()?.retryWasRefused)

        sut.release(WALLET).getOrThrow()
        assertNull(sut.record(WALLET).getOrThrow())
        assertEquals(otherSignedTx, sut.record(OTHER_WALLET).getOrThrow()?.signedTx)
    }

    @Test
    fun `retaining identical bytes keeps the existing record`() = test {
        sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx).getOrThrow()
        sut.markRetryRefused(WALLET).getOrThrow()

        val result = sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx(hex.uppercase()))

        assertTrue(result.isSuccess)
        val record = assertNotNull(sut.record(WALLET).getOrThrow())
        assertTrue(record.retryWasRefused)
        assertEquals(hex, record.serializedTx)
    }

    @Test
    fun `retaining different bytes fails and keeps the original`() = test {
        sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx).getOrThrow()

        val result = sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx("ab".repeat(100)))

        assertIs<HwRetainedSendError.DifferentTransactionRetained>(result.exceptionOrNull())
        assertEquals(hex, sut.record(WALLET).getOrThrow()?.serializedTx)
    }

    @Test
    fun `a failed write fails the retain`() = test {
        val failure = KeychainError.FailedToSave(KEY)
        whenever(keychain.upsertScopedString(eq(KEY), any(), any())).doSuspendableAnswer { throw failure }

        assertEquals(failure, sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx).exceptionOrNull())
    }

    @Test
    fun `marking a retry refused persists and is idempotent`() = test {
        sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx).getOrThrow()

        sut.markRetryRefused(WALLET).getOrThrow()
        sut.markRetryRefused(WALLET).getOrThrow()

        val record = assertNotNull(sut.record(WALLET).getOrThrow())
        assertTrue(record.retryWasRefused)
        assertEquals(signedTx, record.signedTx)
    }

    @Test
    fun `marking a wallet without a record writes nothing`() = test {
        sut.markRetryRefused(WALLET).getOrThrow()

        assertTrue(entries.isEmpty())
    }

    @Test
    fun `release deletes the entry`() = test {
        sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx).getOrThrow()

        sut.release(WALLET).getOrThrow()

        assertFalse(WALLET in entries)
        assertNull(sut.record(WALLET).getOrThrow())
    }

    @Test
    fun `observing the transaction releases regardless of txid case`() = test {
        sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx).getOrThrow()
        val txId = SignedTransactionId.fromHex(hex)

        sut.releaseIfObserved(WALLET, listOf("ff".repeat(32), txId.uppercase())).getOrThrow()

        assertNull(sut.record(WALLET).getOrThrow())
    }

    @Test
    fun `observing other transactions keeps the record`() = test {
        sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx).getOrThrow()

        sut.releaseIfObserved(WALLET, listOf("ff".repeat(32))).getOrThrow()

        assertEquals(signedTx, sut.record(WALLET).getOrThrow()?.signedTx)
    }

    @Test
    fun `a record with invalid hex never matches an observed transaction`() = test {
        val invalid = signedTx("not hex")
        sut.retain(WALLET, ADDRESS, AMOUNT_SATS, invalid).getOrThrow()

        val result = sut.releaseIfObserved(WALLET, listOf("not hex", "", "ff".repeat(32)))

        assertTrue(result.isSuccess)
        assertEquals(invalid, sut.record(WALLET).getOrThrow()?.signedTx)
    }

    @Test
    fun `an undecodable entry blocks only its wallet and only abandon deletes it`() = test {
        sut.retain(OTHER_WALLET, ADDRESS, AMOUNT_SATS, signedTx).getOrThrow()
        entries[WALLET] = CORRUPT_ENTRY

        assertIs<HwRetainedSendError.Undecodable>(sut.record(WALLET).exceptionOrNull())
        assertIs<HwRetainedSendError.Undecodable>(sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx).exceptionOrNull())
        assertIs<HwRetainedSendError.Undecodable>(sut.markRetryRefused(WALLET).exceptionOrNull())
        assertIs<HwRetainedSendError.Undecodable>(sut.release(WALLET).exceptionOrNull())
        assertIs<HwRetainedSendError.Undecodable>(sut.releaseIfObserved(WALLET, listOf("ff")).exceptionOrNull())
        assertEquals(CORRUPT_ENTRY, entries[WALLET])
        assertEquals(signedTx, sut.record(OTHER_WALLET).getOrThrow()?.signedTx)

        sut.abandon(WALLET).getOrThrow()

        assertNull(sut.record(WALLET).getOrThrow())
        assertEquals(signedTx, sut.record(OTHER_WALLET).getOrThrow()?.signedTx)
    }

    @Test
    fun `a storage read failure stays distinct and deletes nothing`() = test {
        val failure = KeychainError.FailedToLoad(KEY)
        whenever(keychain.loadScopedString(KEY, WALLET)).thenAnswer { throw failure }

        assertEquals(failure, sut.record(WALLET).exceptionOrNull())
        assertEquals(failure, sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx).exceptionOrNull())
        assertEquals(failure, sut.markRetryRefused(WALLET).exceptionOrNull())
        assertEquals(failure, sut.release(WALLET).exceptionOrNull())
        assertEquals(failure, sut.releaseIfObserved(WALLET, listOf("ff")).exceptionOrNull())

        verify(keychain, never()).deleteScoped(any(), any())
        verify(keychain, never()).upsertScopedString(any(), any(), any())
    }

    @Test
    fun `abandon deletes only that wallet's entry after a read failure`() = test {
        sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx).getOrThrow()
        sut.retain(OTHER_WALLET, ADDRESS, AMOUNT_SATS, signedTx).getOrThrow()
        whenever(keychain.loadScopedString(KEY, WALLET)).thenAnswer { throw KeychainError.FailedToLoad(KEY) }

        sut.abandon(WALLET).getOrThrow()

        assertFalse(WALLET in entries)
        assertEquals(signedTx, sut.record(OTHER_WALLET).getOrThrow()?.signedTx)
    }

    @Test
    fun `abandon deletes a readable record`() = test {
        sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx).getOrThrow()

        sut.abandon(WALLET).getOrThrow()

        assertNull(sut.record(WALLET).getOrThrow())
    }

    @Test
    fun `nothing is kept outside the keychain so a wipe clears every record`() = test {
        sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx).getOrThrow()
        sut.retain(OTHER_WALLET, ADDRESS, AMOUNT_SATS, signedTx).getOrThrow()

        keychain.wipe()

        assertNull(sut.record(WALLET).getOrThrow())
        assertNull(sut.record(OTHER_WALLET).getOrThrow())
    }

    @Test
    fun `descriptions and errors never reveal the payment`() = test {
        sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx).getOrThrow()
        val description = assertNotNull(sut.record(WALLET).getOrThrow()).toString()
        assertFalse(hex in description)
        assertFalse(ADDRESS in description)

        val different = signedTx("ab".repeat(100))
        val conflict = assertNotNull(sut.retain(WALLET, ADDRESS, AMOUNT_SATS, different).exceptionOrNull())
        assertFalse(hex in conflict.message.orEmpty())

        entries[WALLET] = CORRUPT_ENTRY
        val undecodable = assertNotNull(sut.record(WALLET).exceptionOrNull())
        assertFalse("deadbeef" in undecodable.message.orEmpty())
        assertNull(undecodable.cause)
    }

    private fun signedTx(serializedTx: String) = HwFundingSignedTx(
        serializedTx = serializedTx,
        miningFeeSats = 1_000uL,
        feeRate = 2uL,
        totalSpent = 26_000uL,
    )
}

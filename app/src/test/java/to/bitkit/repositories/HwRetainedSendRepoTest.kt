package to.bitkit.repositories

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.keychain.Keychain
import to.bitkit.data.keychain.KeychainError
import to.bitkit.models.HwFundingSignedTx
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.SignedTransactionId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `observing the transaction marks and keeps the record regardless of txid case`() = test {
        sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx).getOrThrow()
        assertEquals(false, sut.record(WALLET).getOrThrow()?.observed)
        val txId = SignedTransactionId.fromHex(hex)

        sut.markObserved(WALLET, listOf("ff".repeat(32), txId.uppercase())).getOrThrow()

        val record = assertNotNull(sut.record(WALLET).getOrThrow())
        assertTrue(record.observed)
        assertEquals(signedTx, record.signedTx)
        verify(keychain, never()).deleteScoped(any(), any())
    }

    @Test
    fun `marking observed is idempotent and writes once`() = test {
        sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx).getOrThrow()
        val txId = SignedTransactionId.fromHex(hex)

        sut.markObserved(WALLET, listOf(txId)).getOrThrow()
        val observedEntry = entries[WALLET]
        clearInvocations(keychain)
        sut.markObserved(WALLET, listOf(txId)).getOrThrow()

        assertEquals(observedEntry, entries[WALLET])
        verify(keychain, never()).upsertScopedString(any(), any(), any())
    }

    @Test
    fun `the observed flag survives a new repository and does not touch another wallet`() = test {
        sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx).getOrThrow()
        sut.retain(OTHER_WALLET, ADDRESS, AMOUNT_SATS, signedTx).getOrThrow()

        sut.markObserved(WALLET, listOf(SignedTransactionId.fromHex(hex))).getOrThrow()

        val restarted = HwRetainedSendRepo(testDispatcher, keychain)
        assertEquals(true, restarted.record(WALLET).getOrThrow()?.observed)
        assertEquals(false, restarted.record(OTHER_WALLET).getOrThrow()?.observed)
    }

    @Test
    fun `marking a refused retry and marking observed keep each other`() = test {
        sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx).getOrThrow()
        val txId = SignedTransactionId.fromHex(hex)

        sut.markRetryRefused(WALLET).getOrThrow()
        sut.markObserved(WALLET, listOf(txId)).getOrThrow()
        sut.markRetryRefused(WALLET).getOrThrow()

        val record = assertNotNull(sut.record(WALLET).getOrThrow())
        assertTrue(record.retryWasRefused)
        assertTrue(record.observed)
    }

    @Test
    fun `an entry stored before the observed flag existed reads as not observed`() = test {
        sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx).getOrThrow()
        val stored = assertNotNull(entries[WALLET])
        assertFalse("observed" in stored)

        assertEquals(false, sut.record(WALLET).getOrThrow()?.observed)
    }

    @Test
    fun `marking observed for a wallet without a record writes nothing`() = test {
        sut.markObserved(WALLET, listOf("ff".repeat(32))).getOrThrow()

        assertTrue(entries.isEmpty())
    }

    @Test
    fun `observing other transactions keeps the record`() = test {
        sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx).getOrThrow()

        sut.markObserved(WALLET, listOf("ff".repeat(32))).getOrThrow()

        val record = assertNotNull(sut.record(WALLET).getOrThrow())
        assertFalse(record.observed)
        assertEquals(signedTx, record.signedTx)
    }

    @Test
    fun `a record with invalid hex never matches an observed transaction`() = test {
        val invalid = signedTx("not hex")
        sut.retain(WALLET, ADDRESS, AMOUNT_SATS, invalid).getOrThrow()

        val result = sut.markObserved(WALLET, listOf("not hex", "", "ff".repeat(32)))

        assertTrue(result.isSuccess)
        val record = assertNotNull(sut.record(WALLET).getOrThrow())
        assertFalse(record.observed)
        assertEquals(invalid, record.signedTx)
    }

    @Test
    fun `an undecodable entry blocks only its wallet and only abandon deletes it`() = test {
        sut.retain(OTHER_WALLET, ADDRESS, AMOUNT_SATS, signedTx).getOrThrow()
        entries[WALLET] = CORRUPT_ENTRY

        assertIs<HwRetainedSendError.Undecodable>(sut.record(WALLET).exceptionOrNull())
        assertIs<HwRetainedSendError.Undecodable>(sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx).exceptionOrNull())
        assertIs<HwRetainedSendError.Undecodable>(sut.markRetryRefused(WALLET).exceptionOrNull())
        assertIs<HwRetainedSendError.Undecodable>(sut.release(WALLET).exceptionOrNull())
        assertIs<HwRetainedSendError.Undecodable>(sut.markObserved(WALLET, listOf("ff")).exceptionOrNull())
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
        assertEquals(failure, sut.markObserved(WALLET, listOf("ff")).exceptionOrNull())

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
    fun `plain retain refuses different bytes and leaves the record`() = test {
        sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx).getOrThrow()
        val original = entries[WALLET]

        val conflict = sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx("ab".repeat(100)))

        assertIs<HwRetainedSendError.DifferentTransactionRetained>(conflict.exceptionOrNull())
        assertEquals(original, entries[WALLET])
    }

    @Test
    fun `an observed replacement overwrites only an observed record`() = test {
        val different = signedTx("ab".repeat(100))
        sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx).getOrThrow()
        val original = assertNotNull(sut.record(WALLET).getOrThrow())

        val refused = sut.retain(WALLET, "other", AMOUNT_SATS, different, HwRetainedSendReplacement.ObservedRecord)
        assertIs<HwRetainedSendError.DifferentTransactionRetained>(refused.exceptionOrNull())

        sut.markObserved(WALLET, listOf(SignedTransactionId.fromHex(hex))).getOrThrow()
        val outcome = sut.retain(WALLET, "other", AMOUNT_SATS, different, HwRetainedSendReplacement.ObservedRecord)
            .getOrThrow()

        assertEquals(HwRetainOutcome.Replaced(original.copy(observed = true)), outcome)
        assertEquals(different, sut.record(WALLET).getOrThrow()?.signedTx)
    }

    @Test
    fun `a consented replacement overwrites any record in one write and reports what it replaced`() = test {
        val different = signedTx("ab".repeat(100))
        sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx).getOrThrow()
        val original = assertNotNull(sut.record(WALLET).getOrThrow())
        clearInvocations(keychain)

        val outcome = sut.retain(WALLET, "other", 1uL, different, HwRetainedSendReplacement.Consented).getOrThrow()

        assertEquals(HwRetainOutcome.Replaced(original), outcome)
        assertEquals(different, sut.record(WALLET).getOrThrow()?.signedTx)
        verify(keychain).upsertScopedString(eq(KEY), any(), eq(WALLET))
        verify(keychain, never()).deleteScoped(any(), any())
    }

    @Test
    fun `identical bytes are already held whatever the replacement and keep the flags`() = test {
        sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx).getOrThrow()
        sut.markRetryRefused(WALLET).getOrThrow()

        val outcome = sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx, HwRetainedSendReplacement.Consented)

        assertEquals(HwRetainOutcome.AlreadyHeld, outcome.getOrThrow())
        assertEquals(true, sut.record(WALLET).getOrThrow()?.retryWasRefused)
    }

    @Test
    fun `a failed replace write leaves the old record`() = test {
        sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx).getOrThrow()
        val original = entries[WALLET]
        val failure = KeychainError.FailedToSave(KEY)
        whenever(keychain.upsertScopedString(eq(KEY), any(), any())).doSuspendableAnswer { throw failure }

        val result = sut.retain(WALLET, ADDRESS, 1uL, signedTx("ab".repeat(100)), HwRetainedSendReplacement.Consented)

        assertEquals(failure, result.exceptionOrNull())
        assertEquals(original, entries[WALLET])
    }

    @Test
    fun `a corrupt entry is replaced only with consent`() = test {
        entries[WALLET] = CORRUPT_ENTRY

        for (replacement in listOf(HwRetainedSendReplacement.None, HwRetainedSendReplacement.ObservedRecord)) {
            val refused = sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx, replacement)
            assertIs<HwRetainedSendError.Undecodable>(refused.exceptionOrNull())
            assertEquals(CORRUPT_ENTRY, entries[WALLET])
        }

        val outcome = sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx, HwRetainedSendReplacement.Consented)

        assertEquals(HwRetainOutcome.ReplacedUnreadableEntry, outcome.getOrThrow())
        assertEquals(signedTx, sut.record(WALLET).getOrThrow()?.signedTx)
    }

    @Test
    fun `an undecryptable entry is overwritten only with consent`() = test {
        val failure = KeychainError.FailedToLoad(KEY)
        whenever(keychain.loadScopedString(KEY, WALLET)).thenAnswer { throw failure }

        assertEquals(failure, sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx).exceptionOrNull())
        verify(keychain, never()).upsertScopedString(any(), any(), any())

        val outcome = sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx, HwRetainedSendReplacement.Consented)

        assertEquals(HwRetainOutcome.ReplacedUnreadableEntry, outcome.getOrThrow())
        assertTrue(WALLET in entries)
        verify(keychain, never()).deleteScoped(any(), any())
    }

    @Test
    fun `restore puts the older record back only over the bytes that replaced it`() = test {
        val different = "ab".repeat(100)
        sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx(different)).getOrThrow()
        val previous = assertNotNull(sut.record(WALLET).getOrThrow())
        sut.retain(WALLET, ADDRESS, 1uL, signedTx, HwRetainedSendReplacement.Consented).getOrThrow()
        val current = entries[WALLET]

        assertEquals(HwRestoreOutcome.LeftCurrentRecord, sut.restore(previous, "cd".repeat(100)).getOrThrow())
        assertEquals(current, entries[WALLET])

        assertEquals(HwRestoreOutcome.WroteOldRecord, sut.restore(previous, hex).getOrThrow())
        assertEquals(previous, sut.record(WALLET).getOrThrow())
    }

    @Test
    fun `restore keeps new bytes the watcher has seen`() = test {
        sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx("ab".repeat(100))).getOrThrow()
        val previous = assertNotNull(sut.record(WALLET).getOrThrow())
        sut.retain(WALLET, ADDRESS, 1uL, signedTx, HwRetainedSendReplacement.Consented).getOrThrow()
        sut.markObserved(WALLET, listOf(SignedTransactionId.fromHex(hex))).getOrThrow()

        assertEquals(HwRestoreOutcome.LeftCurrentRecord, sut.restore(previous, hex).getOrThrow())

        assertEquals(signedTx, sut.record(WALLET).getOrThrow()?.signedTx)
    }

    @Test
    fun `restore fails when the read fails and writes nothing`() = test {
        sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx("ab".repeat(100))).getOrThrow()
        val previous = assertNotNull(sut.record(WALLET).getOrThrow())
        sut.retain(WALLET, ADDRESS, 1uL, signedTx, HwRetainedSendReplacement.Consented).getOrThrow()
        val current = entries[WALLET]
        val failure = KeychainError.FailedToLoad(KEY)
        whenever(keychain.loadScopedString(KEY, WALLET)).thenAnswer { throw failure }

        assertEquals(failure, sut.restore(previous, hex).exceptionOrNull())

        assertEquals(current, entries[WALLET])
    }

    @Test
    fun `restore fails when the write fails and keeps the new bytes`() = test {
        sut.retain(WALLET, ADDRESS, AMOUNT_SATS, signedTx("ab".repeat(100))).getOrThrow()
        val previous = assertNotNull(sut.record(WALLET).getOrThrow())
        sut.retain(WALLET, ADDRESS, 1uL, signedTx, HwRetainedSendReplacement.Consented).getOrThrow()
        val current = entries[WALLET]
        val failure = KeychainError.FailedToSave(KEY)
        whenever(keychain.upsertScopedString(eq(KEY), any(), any())).doSuspendableAnswer { throw failure }

        assertEquals(failure, sut.restore(previous, hex).exceptionOrNull())

        assertEquals(current, entries[WALLET])
    }

    @Test
    fun `keychain load diagnostics check the scoped preference key`() {
        val scopedKey = stringPreferencesKey("${KEY}_$WALLET")
        val preferences = mock<Preferences>()
        whenever(preferences[scopedKey]).thenReturn("AAAA")
        val context = mock<Context>()
        whenever(context.applicationContext).thenReturn(context)
        val realKeychain = spy(Keychain(mock(), context, testDispatcher))
        doReturn(preferences).whenever(realKeychain).snapshot

        assertFailsWith<KeychainError.FailedToLoad> { realKeychain.loadScopedString(KEY, WALLET) }

        verify(preferences).contains(scopedKey)
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

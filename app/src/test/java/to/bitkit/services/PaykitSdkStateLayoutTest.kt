package to.bitkit.services

import com.synonym.paykit.IdentityStatus
import com.synonym.paykit.PaykitException
import com.synonym.paykit.PaykitSdk
import com.synonym.paykit.SdkStateBlob
import com.synonym.paykit.SdkStateBlobSnapshot
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.keychain.Keychain
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaykitSdkStateLayoutTest : BaseUnitTest() {
    private companion object {
        val STATE_KEY = Keychain.Key.PAYKIT_SDK_STATE.name
    }

    private val rc55Bytes = byteArrayOf(1, 7, 8)
    private val emptyAccountingBytes = byteArrayOf(1, 0, 7, 8)
    private val accountingBytes = byteArrayOf(1, 1, 9)

    private val keychain = mock<Keychain>()
    private val blocking = mock<Keychain.BlockingAccess>()
    private var storedData: ByteArray? = null

    @Before
    fun setUp() {
        whenever(keychain.accessBlocking<Any?>(any())).doAnswer {
            it.getArgument<Keychain.BlockingAccess.() -> Any?>(0).invoke(blocking)
        }
        whenever(blocking.load(STATE_KEY)).doAnswer { storedData }
        doAnswer { storedData = it.getArgument(1) }.whenever(blocking).upsert(eq(STATE_KEY), any())
    }

    @Test
    fun `rc55 layout gains the empty accounting tag and loses it again`() {
        val sdkBytes = PaykitSdkStateLayout.RC55.sdkBytes(rc55Bytes)
        val (layout, storedBytes) = PaykitSdkStateLayout.stored(sdkBytes)

        assertContentEquals(emptyAccountingBytes, sdkBytes)
        assertEquals(PaykitSdkStateLayout.RC55, layout)
        assertContentEquals(rc55Bytes, storedBytes)
    }

    @Test
    fun `state with accounting keeps the allowances layout`() {
        val (layout, storedBytes) = PaykitSdkStateLayout.stored(accountingBytes)

        assertEquals(PaykitSdkStateLayout.ALLOWANCES, layout)
        assertContentEquals(accountingBytes, storedBytes)
        assertContentEquals(accountingBytes, PaykitSdkStateLayout.ALLOWANCES.sdkBytes(accountingBytes))
    }

    @Test
    fun `revision suffix names the stored layout`() {
        assertEquals("id.rc55", PaykitSdkStateLayout.RC55.revision("id"))
        assertEquals("id.alw", PaykitSdkStateLayout.ALLOWANCES.revision("id"))
        assertEquals(PaykitSdkStateLayout.RC55, PaykitSdkStateLayout.fromRevision("id.rc55"))
        assertEquals(PaykitSdkStateLayout.ALLOWANCES, PaykitSdkStateLayout.fromRevision("id.alw"))
        assertNull(PaykitSdkStateLayout.fromRevision("3f1c0e9a-7b8d-4c2e-9f10-2a6b5c4d3e21"))
    }

    @Test
    fun `rc55 blob loads in the allowances layout`() {
        storeSnapshot(rc55Bytes, "r1.rc55")

        val snapshot = assertNotNull(store().loadStateBlob())

        assertContentEquals(emptyAccountingBytes, snapshot.blob.exportBytes())
        assertEquals("r1.rc55", snapshot.revision)
    }

    @Test
    fun `unmarked blob loads unchanged`() {
        storeSnapshot(rc55Bytes, "legacy")

        val snapshot = assertNotNull(store().loadStateBlob())

        assertContentEquals(rc55Bytes, snapshot.blob.exportBytes())
        assertEquals("legacy", snapshot.revision)
    }

    @Test
    fun `save without accounting stores the rc55 layout`() {
        val store = store()

        val revision = store.saveStateBlobAtomically(blob(emptyAccountingBytes), expectedRevision = null)

        val stored = storedSnapshot()
        assertTrue(revision.endsWith(".rc55"))
        assertEquals(revision, stored.revision)
        assertContentEquals(rc55Bytes, stored.blob.exportBytes())
        assertContentEquals(emptyAccountingBytes, store.loadStateBlob()?.blob?.exportBytes())
    }

    @Test
    fun `save with accounting stores the allowances layout`() {
        storeSnapshot(rc55Bytes, "r1.rc55")
        val store = store()

        val revision = store.saveStateBlobAtomically(blob(accountingBytes), expectedRevision = "r1.rc55")

        val stored = storedSnapshot()
        assertTrue(revision.endsWith(".alw"))
        assertEquals(revision, stored.revision)
        assertContentEquals(accountingBytes, stored.blob.exportBytes())
        assertContentEquals(accountingBytes, store.loadStateBlob()?.blob?.exportBytes())
    }

    @Test
    fun `save rejects a stale revision`() {
        storeSnapshot(rc55Bytes, "r1.rc55")
        val store = store()
        val loadedRevision = assertNotNull(store.loadStateBlob()).revision
        val nextRevision = store.saveStateBlobAtomically(blob(accountingBytes), loadedRevision)

        for (staleRevision in listOf(loadedRevision, null)) {
            val error = assertFailsWith<PaykitException.Storage> {
                store.saveStateBlobAtomically(blob(emptyAccountingBytes), staleRevision)
            }
            assertEquals("revision_conflict", error.code)
        }
        assertEquals(nextRevision, storedSnapshot().revision)
        assertContentEquals(accountingBytes, storedSnapshot().blob.exportBytes())
    }

    @Test
    fun `unmarked rc55 blob resolves to the rc55 layout`() = test {
        storeSnapshot(rc55Bytes, "legacy")
        val store = store()
        val probes = mutableListOf<PaykitSdk>()

        store.resolveLegacyLayoutIfNeeded {
            probe(decodes = it.contentEquals(emptyAccountingBytes), hasIdentity = true).also(probes::add)
        }

        val stored = storedSnapshot()
        assertEquals("legacy.rc55", stored.revision)
        assertContentEquals(rc55Bytes, stored.blob.exportBytes())
        assertContentEquals(emptyAccountingBytes, store.loadStateBlob()?.blob?.exportBytes())
        assertEquals(2, probes.size)
        probes.forEach { verify(it).close() }
    }

    @Test
    fun `unmarked allowances blob resolves to the allowances layout`() = test {
        storeSnapshot(accountingBytes, "legacy")
        val store = store()

        store.resolveLegacyLayoutIfNeeded { probe(decodes = it.contentEquals(accountingBytes), hasIdentity = true) }

        val stored = storedSnapshot()
        assertEquals("legacy.alw", stored.revision)
        assertContentEquals(accountingBytes, stored.blob.exportBytes())
        assertContentEquals(accountingBytes, store.loadStateBlob()?.blob?.exportBytes())
    }

    @Test
    fun `identity decides when both layouts decode`() = test {
        val cases = listOf(
            true to "legacy.rc55",
            false to "legacy.alw",
        )
        for ((rc55HasIdentity, expectedRevision) in cases) {
            storeSnapshot(rc55Bytes, "legacy")

            store().resolveLegacyLayoutIfNeeded {
                val isRc55Candidate = it.contentEquals(emptyAccountingBytes)
                probe(decodes = true, hasIdentity = isRc55Candidate == rc55HasIdentity)
            }

            assertEquals(expectedRevision, storedSnapshot().revision)
        }
    }

    @Test
    fun `undecodable or marked blobs keep their revision`() = test {
        storeSnapshot(rc55Bytes, "legacy")
        store().resolveLegacyLayoutIfNeeded { probe(decodes = false, hasIdentity = false) }
        assertEquals("legacy", storedSnapshot().revision)

        storeSnapshot(rc55Bytes, "r1.alw")
        var probeCount = 0
        store().resolveLegacyLayoutIfNeeded {
            probeCount++
            probe(decodes = true, hasIdentity = true)
        }
        assertEquals("r1.alw", storedSnapshot().revision)
        assertEquals(0, probeCount)
    }

    @Test
    fun `resolution keeps a state saved while probing`() = test {
        storeSnapshot(rc55Bytes, "legacy")

        store().resolveLegacyLayoutIfNeeded {
            storeSnapshot(accountingBytes, "concurrent.alw")
            probe(decodes = true, hasIdentity = true)
        }

        val stored = storedSnapshot()
        assertEquals("concurrent.alw", stored.revision)
        assertContentEquals(accountingBytes, stored.blob.exportBytes())
    }

    private fun store() = PaykitSdkStateBlobStore(keychain, ::decode, ::encode, ::blob)

    private fun probe(decodes: Boolean, hasIdentity: Boolean): PaykitSdk {
        val sdk = mock<PaykitSdk>()
        if (decodes) {
            whenever { sdk.allowanceAccountingState() }.thenReturn(null)
        } else {
            whenever { sdk.allowanceAccountingState() }
                .thenThrow(PaykitException.Storage("decode", "decode SDK state blob"))
        }
        val publicKey = if (hasIdentity) "pubky_test" else null
        whenever { sdk.identityStatus() }.thenReturn(IdentityStatus(publicKey, liveSessionAvailable = false))
        return sdk
    }

    private fun storeSnapshot(bytes: ByteArray, revision: String) {
        storedData = encode(SdkStateBlobSnapshot(blob(bytes), revision))
    }

    private fun storedSnapshot() = decode(checkNotNull(storedData))

    private fun blob(bytes: ByteArray): SdkStateBlob = mock { on { exportBytes() } doReturn bytes }

    private fun encode(snapshot: SdkStateBlobSnapshot) =
        "${snapshot.revision}\n".encodeToByteArray() + snapshot.blob.exportBytes()

    private fun decode(data: ByteArray): SdkStateBlobSnapshot {
        val separator = data.indexOf('\n'.code.toByte())
        return SdkStateBlobSnapshot(
            blob = blob(data.copyOfRange(separator + 1, data.size)),
            revision = data.copyOf(separator).decodeToString(),
        )
    }
}

package to.bitkit.data.sharing

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34], qualifiers = "en-rUS")
class SharedPubkyDiscoveryTest : BaseUnitTest() {
    private companion object {
        const val WIRE_PUBKY = "3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        const val SECRET_KEY = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
    }

    private val context = mock<Context>()
    private val packageManager = mock<PackageManager>()
    private val contentResolver = mock<ContentResolver>()
    private val discovery = SharedPubkyDiscovery(context, testDispatcher)

    @Before
    fun setUp() {
        whenever(context.packageName).thenReturn(SharedPubkyContract.BITKIT_SOURCE)
        whenever(context.packageManager).thenReturn(packageManager)
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(
            packageManager.resolveContentProvider(SharedPubkyContract.RING_AUTHORITY, PackageManager.MATCH_ALL),
        ).thenReturn(
            ProviderInfo().apply {
                packageName = SharedPubkyContract.RING_SOURCE
                authority = SharedPubkyContract.RING_AUTHORITY
                readPermission = SharedPubkyContract.RING_READ_PERMISSION
            },
        )
        whenever(
            packageManager.checkSignatures(SharedPubkyContract.BITKIT_SOURCE, SharedPubkyContract.RING_SOURCE),
        ).thenReturn(PackageManager.SIGNATURE_MATCH)
    }

    @Test
    fun `reads a valid credential from a single real cursor row`() = test {
        val credential = readCredential(cursor(row())).getOrThrow()

        assertEquals(
            SharedPubkyIdentity(SharedPubkyContract.PROTOCOL_VERSION, SharedPubkyContract.RING_SOURCE, WIRE_PUBKY),
            credential.identity,
        )
        assertEquals(SECRET_KEY, credential.secretKeyHex)
    }

    @Test
    fun `empty credential response proves the identity is unavailable`() = test {
        assertSame(SharedPubkyError.IdentityUnavailable, readCredential(cursor()).exceptionOrNull())
    }

    @Test
    fun `null provider query results are retryable`() = test {
        assertSame(SharedPubkyError.ProviderQueryFailed, discoverIdentities(null).exceptionOrNull())
        assertSame(SharedPubkyError.ProviderQueryFailed, readCredential(null).exceptionOrNull())
    }

    @Test
    fun `empty identity response proves no shared identities exist`() = test {
        val result = discoverIdentities(MatrixCursor(SharedPubkyContract.publicColumns))

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
    }

    @Test
    fun `missing Ring provider is definitively unavailable`() = test {
        whenever(
            packageManager.resolveContentProvider(SharedPubkyContract.RING_AUTHORITY, PackageManager.MATCH_ALL),
        ).thenReturn(null)

        assertSame(SharedPubkyError.SourceUnavailable, discovery.discoverRingIdentities().exceptionOrNull())
        verifyNoInteractions(contentResolver)
    }

    @Test
    fun `missing secret column is unavailable before identity validation`() = test {
        val cursor = MatrixCursor(SharedPubkyContract.publicColumns).apply {
            addRow(arrayOf<Any?>(2, "untrusted", "invalid"))
        }

        assertSame(SharedPubkyError.IdentityUnavailable, readCredential(cursor).exceptionOrNull())
    }

    @Test
    fun `missing public columns are invalid before checking rows or secret column`() = test {
        SharedPubkyContract.publicColumns.forEach { missingColumn ->
            val columns = SharedPubkyContract.publicColumns.filterNot { it == missingColumn }.toTypedArray()

            assertSame(SharedPubkyError.InvalidResponse, readCredential(MatrixCursor(columns)).exceptionOrNull())
        }
    }

    @Test
    fun `unsupported version precedes source key secret and row count validation`() = test {
        val cursor = cursor(row(version = 2, source = "untrusted", pubky = "invalid", secret = null), row())

        assertIs<SharedPubkyError.UnsupportedVersion>(readCredential(cursor).exceptionOrNull())
    }

    @Test
    fun `untrusted source precedes key secret and row count validation`() = test {
        val cursor = cursor(row(source = "untrusted", pubky = "invalid", secret = null), row())

        assertIs<SharedPubkyError.UntrustedSource>(readCredential(cursor).exceptionOrNull())
    }

    @Test
    fun `malformed or mismatched public keys are invalid`() = test {
        listOf("invalid", "pubky$WIRE_PUBKY", "y".repeat(52)).forEach { pubky ->
            assertSame(SharedPubkyError.InvalidResponse, readCredential(cursor(row(pubky = pubky))).exceptionOrNull())
        }
    }

    @Test
    fun `multiple rows are invalid even when the first credential is valid`() = test {
        assertSame(SharedPubkyError.InvalidResponse, readCredential(cursor(row(), row())).exceptionOrNull())
    }

    @Test
    fun `null empty and malformed secret values are invalid`() = test {
        listOf(null, "", SECRET_KEY.dropLast(1), "g".repeat(64), SECRET_KEY.uppercase()).forEach { secret ->
            assertSame(SharedPubkyError.InvalidResponse, readCredential(cursor(row(secret = secret))).exceptionOrNull())
        }
    }

    @Test
    fun `untrusted provider is rejected before querying credentials`() = test {
        whenever(
            packageManager.checkSignatures(SharedPubkyContract.BITKIT_SOURCE, SharedPubkyContract.RING_SOURCE),
        ).thenReturn(PackageManager.SIGNATURE_NO_MATCH)

        assertIs<SharedPubkyError.UntrustedSource>(discovery.readRingCredential(WIRE_PUBKY).exceptionOrNull())
        verifyNoInteractions(contentResolver)
    }

    private suspend fun readCredential(cursor: MatrixCursor?): Result<SharedPubkyCredential> {
        whenever(
            contentResolver.query(
                SharedPubkyContract.ringCredentialUri(WIRE_PUBKY),
                SharedPubkyContract.credentialColumns,
                null,
                null,
                null,
            ),
        ).thenReturn(cursor)

        return discovery.readRingCredential(WIRE_PUBKY).also {
            if (cursor != null) assertTrue(cursor.isClosed)
        }
    }

    private suspend fun discoverIdentities(cursor: MatrixCursor?): Result<List<SharedPubkyIdentity>> {
        whenever(
            contentResolver.query(
                SharedPubkyContract.ringIdentitiesUri,
                SharedPubkyContract.publicColumns,
                null,
                null,
                null,
            ),
        ).thenReturn(cursor)

        return discovery.discoverRingIdentities().also {
            if (cursor != null) assertTrue(cursor.isClosed)
        }
    }

    private fun cursor(vararg rows: Array<Any?>) = MatrixCursor(SharedPubkyContract.credentialColumns).apply {
        rows.forEach { addRow(it) }
    }

    private fun row(
        version: Int = SharedPubkyContract.PROTOCOL_VERSION,
        source: String = SharedPubkyContract.RING_SOURCE,
        pubky: String = WIRE_PUBKY,
        secret: String? = SECRET_KEY,
    ): Array<Any?> = arrayOf(version, source, pubky, secret)
}

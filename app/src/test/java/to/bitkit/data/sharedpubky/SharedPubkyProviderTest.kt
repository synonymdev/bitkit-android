package to.bitkit.data.sharedpubky

import android.content.Context
import android.content.pm.Signature
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import to.bitkit.data.keychain.Keychain
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

@HiltAndroidTest
@Config(application = HiltTestApplication::class, sdk = [34])
@RunWith(RobolectricTestRunner::class)
class SharedPubkyProviderTest : BaseUnitTest() {
    companion object {
        private const val TRUSTED_UID = 10001
        private const val UNTRUSTED_UID = 10002
        private const val SECRET_KEY_HEX = "8f1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f8"
        private const val PUBKY = "3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        private const val OTHER_PUBKY = "1rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
    }

    @get:Rule(order = 1)
    val hiltRule = HiltAndroidRule(this)

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val keychain = mock<Keychain>()
    private val provider = Robolectric.buildContentProvider(SharedPubkyProvider::class.java).create().get()

    @Before
    fun setUp() {
        hiltRule.inject()
        provider.keychainProvider = { keychain }
        provider.derivePublicKey = { "pubky$PUBKY" }

        val packageManager = Shadows.shadowOf(context.packageManager)
        packageManager.getInternalMutablePackageInfo(context.packageName).signatures = arrayOf(Signature("beef"))
        packageManager.setPackagesForUid(TRUSTED_UID, context.packageName)
        ShadowBinder.setCallingUid(TRUSTED_UID)
    }

    @Test
    fun `untrusted caller is rejected`() {
        ShadowBinder.setCallingUid(UNTRUSTED_UID)

        assertFalse(provider.isCallerTrusted(UNTRUSTED_UID))
        assertFailsWith<SecurityException> { provider.query(identitiesUri()) }
    }

    @Test
    fun `identities are empty without a stored secret`() {
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(null)

        val cursor = requireNotNull(provider.query(identitiesUri()))

        assertEquals(0, cursor.count)
    }

    @Test
    fun `identities and credential expose the stored secret`() {
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(SECRET_KEY_HEX)

        val identities = requireNotNull(provider.query(identitiesUri()))
        val credential = requireNotNull(provider.query(credentialUri(PUBKY)))
        identities.moveToFirst()
        credential.moveToFirst()

        assertEquals(1, identities.count)
        assertEquals(PUBKY, identities.getString(identities.getColumnIndexOrThrow(SharedPubkyContract.COLUMN_PUBKY)))
        assertEquals(1, credential.count)
        assertEquals(
            SECRET_KEY_HEX,
            credential.getString(credential.getColumnIndexOrThrow(SharedPubkyContract.COLUMN_SECRET_KEY)),
        )
    }

    @Test
    fun `credential is empty for another pubky`() {
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(SECRET_KEY_HEX)

        val cursor = requireNotNull(provider.query(credentialUri(OTHER_PUBKY)))

        assertEquals(0, cursor.count)
    }

    private fun identitiesUri(): Uri = uriOf(SharedPubkyContract.PATH_IDENTITIES)

    private fun credentialUri(pubky: String): Uri =
        uriOf("${SharedPubkyContract.PATH_IDENTITIES}/$pubky/${SharedPubkyContract.PATH_CREDENTIAL}")

    private fun uriOf(path: String): Uri =
        Uri.parse("content://${context.packageName}${SharedPubkyContract.AUTHORITY_SUFFIX}/$path")

    private fun SharedPubkyProvider.query(uri: Uri) = query(uri, null, null, null, null)
}

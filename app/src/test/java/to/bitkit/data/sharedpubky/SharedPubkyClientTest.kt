package to.bitkit.data.sharedpubky

import android.app.Application
import android.content.ContentProvider
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.ProviderInfo
import android.content.pm.Signature
import android.database.MatrixCursor
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Config(application = Application::class, sdk = [34])
@RunWith(RobolectricTestRunner::class)
class SharedPubkyClientTest : BaseUnitTest() {
    companion object {
        private const val PUBKY = "3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        private const val TRUSTED_SIGNATURE = "beef"
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val ringProvider = mock<ContentProvider>()
    private lateinit var sut: SharedPubkyClient

    @Before
    fun setUp() {
        Shadows.shadowOf(context.packageManager)
            .getInternalMutablePackageInfo(context.packageName).signatures = arrayOf(Signature(TRUSTED_SIGNATURE))
        ShadowContentResolver.registerProviderInternal(SharedPubkyContract.RING_AUTHORITY, ringProvider)
        whenever(ringProvider.query(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).thenAnswer {
            MatrixCursor(arrayOf(SharedPubkyContract.COLUMN_PUBKY)).apply {
                addRow(arrayOf(PUBKY))
                addRow(arrayOf(null))
            }
        }
        sut = SharedPubkyClient(context, testDispatcher)
    }

    @Test
    fun `listRingIdentities reads pubkys from a trusted ring provider`() = test {
        installRing(TRUSTED_SIGNATURE)

        assertEquals(listOf(PUBKY), sut.listRingIdentities().getOrThrow())
    }

    @Test
    fun `ring provider with another signature is never queried`() = test {
        installRing("dead")

        assertTrue(sut.listRingIdentities().isFailure)
        assertTrue(sut.ringCredential(PUBKY).isFailure)
        verify(ringProvider, never()).query(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `listRingIdentities fails without pubky ring`() = test {
        assertTrue(sut.listRingIdentities().isFailure)
        verify(ringProvider, never()).query(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
    }

    private fun installRing(signature: String) {
        Shadows.shadowOf(context.packageManager).installPackage(
            PackageInfo().apply {
                packageName = SharedPubkyContract.RING_PACKAGE
                signatures = arrayOf(Signature(signature))
                providers = arrayOf(
                    ProviderInfo().apply {
                        name = "to.pubkyring.SharedPubkyProvider"
                        packageName = SharedPubkyContract.RING_PACKAGE
                        authority = SharedPubkyContract.RING_AUTHORITY
                    },
                )
            },
        )
    }
}

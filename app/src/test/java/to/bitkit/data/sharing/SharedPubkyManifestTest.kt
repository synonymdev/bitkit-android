package to.bitkit.data.sharing

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.BuildConfig
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34])
class SharedPubkyManifestTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val packageManager = context.packageManager

    @Test
    fun `provider authority and permissions expand for the application variant`() {
        val applicationId = BuildConfig.APPLICATION_ID
        val permissionName = "$applicationId.permission.READ_SHARED_PUBKY"
        val provider = requireNotNull(
            packageManager.resolveContentProvider("$applicationId.sharedpubky", PackageManager.MATCH_ALL)
        )

        assertEquals(applicationId, provider.packageName)
        assertEquals(permissionName, provider.readPermission)
        assertEquals(permissionName, provider.writePermission)
        assertTrue(provider.exported)

        val permission = packageManager.getPermissionInfo(permissionName, PackageManager.GET_META_DATA)
        assertEquals(
            PermissionInfo.PROTECTION_SIGNATURE,
            permission.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE,
        )
    }
}

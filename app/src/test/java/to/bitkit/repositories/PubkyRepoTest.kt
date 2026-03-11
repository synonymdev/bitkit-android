package to.bitkit.repositories

import android.graphics.Bitmap
import app.cash.turbine.test
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import to.bitkit.data.PubkyImageCache
import to.bitkit.data.PubkyStore
import to.bitkit.data.PubkyStoreData
import to.bitkit.data.keychain.Keychain
import to.bitkit.services.PubkyService
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import com.synonym.bitkitcore.PubkyProfile as CorePubkyProfile

class PubkyRepoTest : BaseUnitTest() {
    private lateinit var sut: PubkyRepo

    private val pubkyService = mock<PubkyService>()
    private val keychain = mock<Keychain>()
    private val imageCache = mock<PubkyImageCache>()
    private val pubkyStore = mock<PubkyStore>()

    @Before
    fun setUp() = runBlocking {
        whenever(pubkyStore.data).thenReturn(flowOf(PubkyStoreData()))
        sut = createSut()
    }

    private fun createSut() = PubkyRepo(
        ioDispatcher = testDispatcher,
        pubkyService = pubkyService,
        keychain = keychain,
        imageCache = imageCache,
        pubkyStore = pubkyStore,
    )

    @Test
    fun `initial state should have no public key`() = test {
        assertNull(sut.publicKey.value)
        assertFalse(sut.isAuthenticated.value)
    }

    @Test
    fun `startAuthentication should return auth uri on success`() = test {
        val authUri = "pubky://auth?capabilities=..."
        whenever(pubkyService.startAuth()).thenReturn(authUri)

        val result = sut.startAuthentication()

        assertTrue(result.isSuccess)
        assertEquals(authUri, result.getOrNull())
    }

    @Test
    fun `startAuthentication should reset state on failure`() = test {
        whenever(pubkyService.startAuth()).thenThrow(RuntimeException("Auth failed"))

        val result = sut.startAuthentication()

        assertTrue(result.isFailure)
        sut.isAuthenticated.test(timeout = 500.milliseconds) {
            assertFalse(awaitItem())
        }
    }

    @Test
    fun `completeAuthentication should save session and update state`() = test {
        val testSecret = "session_secret"
        val testPk = "completed_pk"
        whenever(pubkyService.completeAuth()).thenReturn(testSecret)
        whenever(pubkyService.importSession(testSecret)).thenReturn(testPk)

        val ffiProfile = mock<CorePubkyProfile>()
        whenever(ffiProfile.name).thenReturn("User")
        whenever(ffiProfile.bio).thenReturn(null)
        whenever(ffiProfile.image).thenReturn(null)
        whenever(ffiProfile.links).thenReturn(null)
        whenever(ffiProfile.status).thenReturn(null)
        whenever(pubkyService.getProfile(testPk)).thenReturn(ffiProfile)

        val result = sut.completeAuthentication()

        assertTrue(result.isSuccess)
        assertEquals(testPk, sut.publicKey.value)
        assertTrue(sut.isAuthenticated.value)
        verifyBlocking(keychain) { saveString(Keychain.Key.PAYKIT_SESSION.name, testSecret) }
    }

    @Test
    fun `completeAuthentication should reset state on failure`() = test {
        whenever(pubkyService.completeAuth()).thenThrow(RuntimeException("Failed"))

        val result = sut.completeAuthentication()

        assertTrue(result.isFailure)
        assertFalse(sut.isAuthenticated.value)
        assertNull(sut.publicKey.value)
    }

    @Test
    fun `cancelAuthentication should reset state to idle`() = test {
        whenever(pubkyService.startAuth()).thenReturn("auth_uri")
        sut.startAuthentication()

        sut.cancelAuthentication()

        assertFalse(sut.isAuthenticated.value)
    }

    @Test
    fun `loadProfile should update profile on success`() = test {
        authenticateForTesting()

        val pk = sut.publicKey.value!!
        val ffiProfile = mock<CorePubkyProfile>()
        whenever(ffiProfile.name).thenReturn("Profile Name")
        whenever(ffiProfile.bio).thenReturn("A bio")
        whenever(ffiProfile.image).thenReturn("pubky://image_uri")
        whenever(ffiProfile.links).thenReturn(null)
        whenever(ffiProfile.status).thenReturn("active")
        whenever(pubkyService.getProfile(pk)).thenReturn(ffiProfile)

        sut.loadProfile()

        val profile = sut.profile.value
        assertNotNull(profile)
        assertEquals("Profile Name", profile.name)
        assertEquals("A bio", profile.bio)
        assertEquals("pubky://image_uri", profile.imageUrl)
        assertEquals("active", profile.status)
    }

    @Test
    fun `loadProfile should keep existing profile on failure`() = test {
        authenticateForTesting()
        val existingProfile = sut.profile.value
        assertNotNull(existingProfile)

        val pk = sut.publicKey.value!!
        whenever(pubkyService.getProfile(pk)).thenThrow(RuntimeException("Network error"))

        sut.loadProfile()

        assertEquals(existingProfile, sut.profile.value)
        assertFalse(sut.isLoadingProfile.value)
    }

    @Test
    fun `loadProfile should return early when no public key`() = test {
        sut.loadProfile()

        verify(pubkyService, never()).getProfile(any())
    }

    @Test
    fun `loadProfile should cache metadata on success`() = test {
        authenticateForTesting()

        val pk = sut.publicKey.value!!
        val ffiProfile = mock<CorePubkyProfile>()
        whenever(ffiProfile.name).thenReturn("Cached Name")
        whenever(ffiProfile.bio).thenReturn("")
        whenever(ffiProfile.image).thenReturn("pubky://cached_image")
        whenever(ffiProfile.links).thenReturn(null)
        whenever(ffiProfile.status).thenReturn(null)
        whenever(pubkyService.getProfile(pk)).thenReturn(ffiProfile)

        sut.loadProfile()

        verifyBlocking(pubkyStore, atLeastOnce()) { update(any()) }
    }

    @Test
    fun `signOut should clear state and keychain`() = test {
        authenticateForTesting()

        val result = sut.signOut()

        assertTrue(result.isSuccess)
        assertNull(sut.publicKey.value)
        assertNull(sut.profile.value)
        assertFalse(sut.isAuthenticated.value)
        verifyBlocking(keychain, atLeastOnce()) { delete(Keychain.Key.PAYKIT_SESSION.name) }
        verify(imageCache).clear()
        verifyBlocking(pubkyStore) { reset() }
    }

    @Test
    fun `signOut should force sign out when server sign out fails`() = test {
        authenticateForTesting()
        whenever(pubkyService.signOut()).thenThrow(RuntimeException("Server error"))

        val result = sut.signOut()

        assertTrue(result.isSuccess)
        verifyBlocking(pubkyService) { forceSignOut() }
        assertFalse(sut.isAuthenticated.value)
    }

    @Test
    fun `cachedImage should delegate to imageCache`() = test {
        val testUri = "pubky://test_image"
        val bitmap = mock<Bitmap>()
        whenever(imageCache.memoryImage(testUri)).thenReturn(bitmap)

        val result = sut.cachedImage(testUri)

        assertEquals(bitmap, result)
    }

    @Test
    fun `cachedImage should return null when not cached`() = test {
        whenever(imageCache.memoryImage(any())).thenReturn(null)

        val result = sut.cachedImage("pubky://missing")

        assertNull(result)
    }

    @Test
    fun `fetchImage should return cached image from disk`() = test {
        val testUri = "pubky://disk_cached"
        val bitmap = mock<Bitmap>()
        whenever(imageCache.image(testUri)).thenReturn(bitmap)

        val result = sut.fetchImage(testUri)

        assertTrue(result.isSuccess)
        assertEquals(bitmap, result.getOrNull())
        verify(pubkyService, never()).fetchFile(any())
    }

    @Test
    fun `fetchImage should fail when fetch throws`() = test {
        val testUri = "pubky://failing_image"
        whenever(imageCache.image(testUri)).thenReturn(null)
        whenever(pubkyService.fetchFile(testUri)).thenThrow(RuntimeException("Network error"))

        val result = sut.fetchImage(testUri)

        assertTrue(result.isFailure)
    }

    @Test
    fun `displayName should return null when no profile and no cache`() = test {
        sut.displayName.test(timeout = 500.milliseconds) {
            assertNull(awaitItem())
        }
    }

    @Test
    fun `displayImageUri should return null when no profile and no cache`() = test {
        sut.displayImageUri.test(timeout = 500.milliseconds) {
            assertNull(awaitItem())
        }
    }

    @Test
    fun `displayName should return cached name when no profile`() = test {
        whenever(pubkyStore.data).thenReturn(flowOf(PubkyStoreData(cachedName = "Cached")))
        sut = createSut()

        sut.displayName.test(timeout = 500.milliseconds) {
            assertEquals("Cached", awaitItem())
        }
    }

    private suspend fun authenticateForTesting() {
        val testSecret = "test_secret"
        val testPk = "test_pk_12345"
        whenever(pubkyService.completeAuth()).thenReturn(testSecret)
        whenever(pubkyService.importSession(testSecret)).thenReturn(testPk)

        val ffiProfile = mock<CorePubkyProfile>()
        whenever(ffiProfile.name).thenReturn("Test")
        whenever(ffiProfile.bio).thenReturn(null)
        whenever(ffiProfile.image).thenReturn(null)
        whenever(ffiProfile.links).thenReturn(null)
        whenever(ffiProfile.status).thenReturn(null)
        whenever(pubkyService.getProfile(testPk)).thenReturn(ffiProfile)

        sut.completeAuthentication()
    }
}

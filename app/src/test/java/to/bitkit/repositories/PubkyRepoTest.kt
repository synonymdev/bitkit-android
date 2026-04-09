package to.bitkit.repositories

import app.cash.turbine.test
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
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
import to.bitkit.data.PubkyStore
import to.bitkit.data.PubkyStoreData
import to.bitkit.data.keychain.Keychain
import to.bitkit.env.Env
import to.bitkit.models.PubkyProfile
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
    private val imageLoader = mock<ImageLoader>()
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
        imageLoader = imageLoader,
        pubkyStore = pubkyStore,
        httpClient = mock(),
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
        whenever(pubkyService.getProfile(testPk)).thenReturn(ffiProfile)

        val result = sut.completeAuthentication()

        assertTrue(result.isSuccess)
        assertEquals(testPk, sut.publicKey.value)
        assertTrue(sut.isAuthenticated.value)
        verifyBlocking(keychain) { saveString(Keychain.Key.PAYKIT_SESSION.name, testSecret) }
    }

    @Test
    fun `completeAuthentication should not load contacts automatically`() = test {
        val testSecret = "session_secret"
        val testPk = "completed_pk"
        whenever(pubkyService.completeAuth()).thenReturn(testSecret)
        whenever(pubkyService.importSession(testSecret)).thenReturn(testPk)
        val ffiProfile = createFfiProfile(name = "User")
        whenever(pubkyService.getProfile(testPk)).thenReturn(ffiProfile)

        val result = sut.completeAuthentication()

        assertTrue(result.isSuccess)
        verify(pubkyService, never()).sessionList(any(), any())
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

        val pk = checkNotNull(sut.publicKey.value) { "publicKey should be set after authentication" }
        val ffiProfile = mock<CorePubkyProfile>()
        whenever(ffiProfile.name).thenReturn("Profile Name")
        whenever(ffiProfile.bio).thenReturn("A bio")
        whenever(ffiProfile.image).thenReturn("pubky://image_uri")
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

        val pk = checkNotNull(sut.publicKey.value) { "publicKey should be set after authentication" }
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

        val pk = checkNotNull(sut.publicKey.value) { "publicKey should be set after authentication" }
        val ffiProfile = mock<CorePubkyProfile>()
        whenever(ffiProfile.name).thenReturn("Cached Name")
        whenever(ffiProfile.bio).thenReturn("")
        whenever(ffiProfile.image).thenReturn("pubky://cached_image")
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
        verifyBlocking(pubkyStore) { reset() }
    }

    @Test
    fun `signOut should evict pubky images from caches`() = test {
        authenticateForTesting()
        val pk = checkNotNull(sut.publicKey.value)
        val ffiProfile = mock<CorePubkyProfile>()
        whenever(ffiProfile.name).thenReturn("Test")
        whenever(ffiProfile.image).thenReturn("pubky://image_uri")
        whenever(pubkyService.getProfile(pk)).thenReturn(ffiProfile)
        sut.loadProfile()

        val memoryCache = mock<MemoryCache>()
        val diskCache = mock<DiskCache>()
        val memoryCacheKey = MemoryCache.Key("pubky://image_uri")
        whenever(memoryCache.keys).thenReturn(setOf(memoryCacheKey))
        whenever(imageLoader.memoryCache).thenReturn(memoryCache)
        whenever(imageLoader.diskCache).thenReturn(diskCache)

        sut.signOut()

        verify(memoryCache).remove(memoryCacheKey)
        verify(diskCache).remove("pubky://image_uri")
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
    fun `clearPendingImport should only clear temporary import state`() = test {
        authenticateForTesting()
        val existingContact = PubkyProfile(
            publicKey = "pubkyexisting-contact",
            name = "Existing Contact",
            bio = "",
            imageUrl = null,
            links = emptyList(),
            tags = emptyList(),
            status = null,
        )
        val pendingContactKey = "pubkypending-contact"
        val publicKey = checkNotNull(sut.publicKey.value)

        sut.addContact(existingContact.publicKey, existingProfile = existingContact)
        whenever(pubkyService.getContacts(publicKey)).thenReturn(listOf(pendingContactKey))
        val pendingContactProfile = createFfiProfile(name = "Pending Contact")
        whenever(pubkyService.getProfile(pendingContactKey)).thenReturn(pendingContactProfile)

        val prepareResult = sut.prepareImport()

        assertTrue(prepareResult.isSuccess)
        assertNotNull(sut.pendingImportProfile.value)
        assertEquals(1, sut.pendingImportContacts.value.size)

        sut.clearPendingImport()

        assertNull(sut.pendingImportProfile.value)
        assertTrue(sut.pendingImportContacts.value.isEmpty())
        assertEquals(listOf(existingContact), sut.contacts.value)
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

    @Test
    fun `loadContacts should populate contacts on success`() = test {
        authenticateForTesting()
        val contactKey = "pubkycontact1"
        val contactPath = "${Env.contactsBasePath}$contactKey"
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn("test_secret")
        whenever(pubkyService.sessionList("test_secret", Env.contactsBasePath))
            .thenReturn(listOf(contactPath))

        val json = """{"name":"Alice","bio":"Hello"}"""
        val pk = checkNotNull(sut.publicKey.value)
        val strippedPk = pk.removePrefix("pubky")
        whenever(pubkyService.fetchFileString("pubky://$strippedPk${Env.contactsBasePath}$contactKey"))
            .thenReturn(json)

        sut.loadContacts()

        val contacts = sut.contacts.value
        assertEquals(1, contacts.size)
        assertEquals("Alice", contacts.first().name)
        assertEquals(contactKey, contacts.first().publicKey)
        assertFalse(sut.isLoadingContacts.value)
    }

    @Test
    fun `addContact should replace existing contact with normalized key`() = test {
        authenticateForTesting()
        val original = PubkyProfile(
            publicKey = "contact-key",
            name = "Alice",
            bio = "",
            imageUrl = null,
            links = emptyList(),
            tags = listOf("old"),
            status = null,
        )
        val updated = original.copy(name = "Alice Updated", tags = listOf("new"))

        sut.addContact("contact-key", existingProfile = original)
        sut.addContact("contact-key", existingProfile = updated)

        val contacts = sut.contacts.value
        assertEquals(1, contacts.size)
        assertEquals("pubkycontact-key", contacts.first().publicKey)
        assertEquals("Alice Updated", contacts.first().name)
        assertEquals(listOf("new"), contacts.first().tags)
    }

    @Test
    fun `loadProfile should ignore stale result when authenticated key changes`() = test {
        val oldSecret = "old_secret"
        val oldPublicKey = "old_public_key"
        val newSecret = "new_secret"
        val newPublicKey = "new_public_key"
        authenticateForTesting(
            publicKey = oldPublicKey,
            secret = oldSecret,
            profileName = "Initial Old",
        )
        whenever(pubkyService.completeAuth()).thenReturn(newSecret)
        whenever(pubkyService.importSession(newSecret)).thenReturn(newPublicKey)
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn(newSecret)
        whenever(pubkyService.sessionList(newSecret, Env.contactsBasePath)).thenReturn(emptyList())
        val staleProfile = createFfiProfile(name = "Stale Old")
        whenever(pubkyService.getProfile(oldPublicKey)).thenAnswer {
            runBlocking { sut.completeAuthentication() }
            staleProfile
        }

        sut.loadProfile()

        assertEquals(newPublicKey, sut.publicKey.value)
        assertEquals("Initial Old", sut.profile.value?.name)
    }

    @Test
    fun `loadContacts should ignore stale result when authenticated key changes`() = test {
        val oldSecret = "old_secret"
        val oldPublicKey = "old_public_key"
        val newSecret = "new_secret"
        val newPublicKey = "new_public_key"
        val existingContact = PubkyProfile(
            publicKey = "pubkyexisting-contact",
            name = "Existing Contact",
            bio = "",
            imageUrl = null,
            links = emptyList(),
            tags = emptyList(),
            status = null,
        )
        val staleContactKey = "pubkystale-contact"
        val staleContactPath = "${Env.contactsBasePath}$staleContactKey"
        val staleContactUri = "pubky://$oldPublicKey${Env.contactsBasePath}$staleContactKey"

        authenticateForTesting(
            publicKey = oldPublicKey,
            secret = oldSecret,
            profileName = "Initial Old",
        )
        sut.addContact(existingContact.publicKey, existingProfile = existingContact)

        whenever(pubkyService.completeAuth()).thenReturn(newSecret)
        whenever(pubkyService.importSession(newSecret)).thenReturn(newPublicKey)
        val newProfile = createFfiProfile(name = "New User")
        whenever(pubkyService.getProfile(newPublicKey)).thenReturn(newProfile)
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn(oldSecret)
        whenever(pubkyService.sessionList(oldSecret, Env.contactsBasePath)).thenReturn(listOf(staleContactPath))
        whenever(pubkyService.fetchFileString(staleContactUri)).thenAnswer {
            runBlocking { sut.completeAuthentication() }
            """{"name":"Stale Contact","bio":""}"""
        }

        sut.loadContacts()

        val contacts = sut.contacts.value
        assertEquals(newPublicKey, sut.publicKey.value)
        assertEquals(1, contacts.size)
        assertEquals(existingContact.publicKey, contacts.first().publicKey)
        assertEquals(existingContact.name, contacts.first().name)
    }

    @Test
    fun `loadContacts should return early when no public key`() = test {
        sut.loadContacts()

        verify(pubkyService, never()).sessionList(any(), any())
    }

    @Test
    fun `loadContacts should use placeholder when profile fetch fails`() = test {
        authenticateForTesting()
        val contactKey = "pubkycontact2"
        val contactPath = "${Env.contactsBasePath}$contactKey"
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn("test_secret")
        whenever(pubkyService.sessionList("test_secret", Env.contactsBasePath))
            .thenReturn(listOf(contactPath))

        val pk = checkNotNull(sut.publicKey.value)
        val strippedPk = pk.removePrefix("pubky")
        whenever(pubkyService.fetchFileString("pubky://$strippedPk${Env.contactsBasePath}$contactKey"))
            .thenThrow(RuntimeException("Network error"))

        sut.loadContacts()

        val contacts = sut.contacts.value
        assertEquals(1, contacts.size)
        assertEquals(contactKey, contacts.first().publicKey)
        assertFalse(sut.isLoadingContacts.value)
    }

    @Test
    fun `fetchContactProfile should return profile on success`() = test {
        val contactKey = "pubky://contact3"
        val contactProfile = mock<CorePubkyProfile>()
        whenever(contactProfile.name).thenReturn("Bob")
        whenever(contactProfile.bio).thenReturn("Bio")
        whenever(pubkyService.getProfile(contactKey)).thenReturn(contactProfile)

        val result = sut.fetchContactProfile(contactKey)

        assertTrue(result.isSuccess)
        assertEquals("Bob", result.getOrNull()?.name)
    }

    @Test
    fun `fetchContactProfile should return failure on error`() = test {
        val contactKey = "pubky://failing"
        whenever(pubkyService.getProfile(contactKey)).thenThrow(RuntimeException("Failed"))

        val result = sut.fetchContactProfile(contactKey)

        assertTrue(result.isFailure)
    }

    @Test
    fun `signOut should clear contacts`() = test {
        authenticateForTesting()
        val contactKey = "pubkycontact4"
        val contactPath = "${Env.contactsBasePath}$contactKey"
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn("test_secret")
        whenever(pubkyService.sessionList("test_secret", Env.contactsBasePath))
            .thenReturn(listOf(contactPath))

        val pk = checkNotNull(sut.publicKey.value)
        val strippedPk = pk.removePrefix("pubky")
        val json = """{"name":"Charlie","bio":""}"""
        whenever(pubkyService.fetchFileString("pubky://$strippedPk${Env.contactsBasePath}$contactKey"))
            .thenReturn(json)

        sut.loadContacts()
        assertEquals(1, sut.contacts.value.size)

        sut.signOut()

        assertTrue(sut.contacts.value.isEmpty())
    }

    @Test
    fun `signOut should clear pending import`() = test {
        authenticateForTesting()
        val publicKey = checkNotNull(sut.publicKey.value)
        val pendingContactKey = "pubkypending-contact"
        whenever(pubkyService.getContacts(publicKey)).thenReturn(listOf(pendingContactKey))
        val pendingContactProfile = createFfiProfile(name = "Pending Contact")
        whenever(pubkyService.getProfile(pendingContactKey)).thenReturn(pendingContactProfile)

        sut.prepareImport()
        assertNotNull(sut.pendingImportProfile.value)
        assertEquals(1, sut.pendingImportContacts.value.size)

        sut.signOut()

        assertNull(sut.pendingImportProfile.value)
        assertTrue(sut.pendingImportContacts.value.isEmpty())
        assertTrue(sut.contacts.value.isEmpty())
    }

    @Test
    fun `loadContacts should extract contact key from path`() = test {
        authenticateForTesting()
        val contactKey = "pubkyabc123"
        val contactPath = "${Env.contactsBasePath}$contactKey"
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn("test_secret")
        whenever(pubkyService.sessionList("test_secret", Env.contactsBasePath))
            .thenReturn(listOf(contactPath))

        val pk = checkNotNull(sut.publicKey.value)
        val strippedPk = pk.removePrefix("pubky")
        val expectedUri = "pubky://$strippedPk${Env.contactsBasePath}$contactKey"
        val json = """{"name":"Extracted","bio":""}"""
        whenever(pubkyService.fetchFileString(expectedUri)).thenReturn(json)

        sut.loadContacts()

        verify(pubkyService).fetchFileString(expectedUri)
        assertEquals("Extracted", sut.contacts.value.first().name)
        assertEquals(contactKey, sut.contacts.value.first().publicKey)
    }

    private suspend fun authenticateForTesting(
        publicKey: String = "test_pk_12345",
        secret: String = "test_secret",
        profileName: String = "Test",
    ) {
        whenever(pubkyService.completeAuth()).thenReturn(secret)
        whenever(pubkyService.importSession(secret)).thenReturn(publicKey)
        val ffiProfile = createFfiProfile(name = profileName)
        whenever(pubkyService.getProfile(publicKey)).thenReturn(ffiProfile)
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn(secret)
        whenever(pubkyService.sessionList(secret, Env.contactsBasePath)).thenReturn(emptyList())

        sut.completeAuthentication()
    }

    private fun createFfiProfile(name: String): CorePubkyProfile {
        val ffiProfile = mock<CorePubkyProfile>()
        whenever(ffiProfile.name).thenReturn(name)
        whenever(ffiProfile.bio).thenReturn("")
        whenever(ffiProfile.image).thenReturn(null)
        whenever(ffiProfile.status).thenReturn(null)
        return ffiProfile
    }
}

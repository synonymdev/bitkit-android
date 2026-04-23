package to.bitkit.repositories

import app.cash.turbine.test
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.clearInvocations
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
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
import to.bitkit.models.PubkySessionBackupKind
import to.bitkit.models.PubkySessionBackupV1
import to.bitkit.services.PubkyService
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import com.synonym.bitkitcore.PubkyProfile as CorePubkyProfile

@Suppress("LargeClass")
class PubkyRepoTest : BaseUnitTest() {
    companion object {
        // Valid 52-char z-base-32 key (+ "pubky" prefix = 57 chars)
        private const val VALID_CONTACT_KEY_A = "pubkyybndrfg8ejkmcpqxot1uwisza345h769ybndrfg8ejkmcpqxot1u"
        private const val VALID_CONTACT_KEY_B = "pubkya345h769ybndrfg8ejkmcpqxot1uwiszybndrfg8ejkmcpqxot1u"
        private const val VALID_SELF_KEY = "pubkyot1uwisza345h769ybndrfg8ejkmcpqxybndrfg8ejkmcpqxot1u"
    }

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
        verifyBlocking(keychain) { upsertString(Keychain.Key.PAYKIT_SESSION.name, testSecret) }
    }

    @Test
    fun `completeAuthentication should clear managed secret key`() = test {
        val testSecret = "session_secret"
        val testPk = "completed_pk"
        whenever(pubkyService.completeAuth()).thenReturn(testSecret)
        whenever(pubkyService.importSession(testSecret)).thenReturn(testPk)
        val ffiProfile = createFfiProfile(name = "User")
        whenever(pubkyService.getProfile(testPk)).thenReturn(ffiProfile)

        val result = sut.completeAuthentication()

        assertTrue(result.isSuccess)
        verifyBlocking(keychain) { delete(Keychain.Key.PUBKY_SECRET_KEY.name) }
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
        clearInvocations(pubkyStore)

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
    fun `deleteProfile should fail when signOut fails`() = test {
        authenticateForTesting()
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn("test_secret")
        whenever(pubkyService.signOut()).thenThrow(RuntimeException("Sign out failed"))
        whenever(pubkyService.forceSignOut()).thenThrow(RuntimeException("Force sign out failed"))

        val result = sut.deleteProfile()

        assertTrue(result.isFailure)
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
            publicKey = VALID_CONTACT_KEY_B,
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
    fun `snapshotSessionBackupState should prefer local seed over session secret`() = test {
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn("local_secret")
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn("session_secret")

        val result = sut.snapshotSessionBackupState()

        assertEquals(
            PubkySessionBackupV1(kind = PubkySessionBackupKind.LocalSeed),
            result.getOrNull(),
        )
    }

    @Test
    fun `snapshotSessionBackupState should use external session when no local seed exists`() = test {
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(null)
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn("session_secret")

        val result = sut.snapshotSessionBackupState()

        assertEquals(
            PubkySessionBackupV1(
                kind = PubkySessionBackupKind.ExternalSession,
                sessionSecret = "session_secret",
            ),
            result.getOrNull(),
        )
    }

    @Test
    fun `snapshotSessionBackupState should return null when no pubky credentials exist`() = test {
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(null)
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn(null)

        val result = sut.snapshotSessionBackupState()

        assertNull(result.getOrNull())
    }

    @Test
    fun `initialize should restore session from local secret key when saved session is missing`() = test {
        val secretKey = "local_secret"
        val session = "new_session"
        val publicKey = VALID_SELF_KEY
        val ffiProfile = createFfiProfile(name = "Recovered User")
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn(null)
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(secretKey)
        whenever(pubkyService.signIn(secretKey)).thenReturn(session)
        whenever(pubkyService.importSession(session)).thenReturn(publicKey)
        whenever(pubkyService.getProfile(publicKey)).thenReturn(ffiProfile)

        sut.initialize()

        assertEquals(publicKey, sut.publicKey.value)
        assertTrue(sut.isAuthenticated.value)
        verifyBlocking(keychain) { upsertString(Keychain.Key.PAYKIT_SESSION.name, session) }
    }

    @Test
    fun `refreshSessionIfPossible should refresh session when local secret key exists`() = test {
        val secretKey = "local_secret"
        val session = "new_session"
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(secretKey)
        whenever(pubkyService.signIn(secretKey)).thenReturn(session)
        whenever(pubkyService.importSession(session)).thenReturn(VALID_SELF_KEY)

        val result = sut.refreshSessionIfPossible()

        assertEquals(true, result.getOrNull())
        assertEquals(VALID_SELF_KEY, sut.publicKey.value)
        assertTrue(sut.isAuthenticated.value)
        verifyBlocking(keychain) { upsertString(Keychain.Key.PAYKIT_SESSION.name, session) }
    }

    @Test
    fun `refreshSessionIfPossible should return false when local secret key is missing`() = test {
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(null)

        val result = sut.refreshSessionIfPossible()

        assertEquals(false, result.getOrNull())
        assertNull(sut.publicKey.value)
        assertFalse(sut.isAuthenticated.value)
    }

    @Test
    fun `restoreSessionBackupState should derive local secret key for local seed backups`() = test {
        val seed = byteArrayOf(1, 2, 3)
        whenever(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn("test mnemonic")
        whenever(keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)).thenReturn(null)
        whenever(pubkyService.mnemonicToSeed("test mnemonic", null)).thenReturn(seed)
        whenever(pubkyService.deriveSecretKey(seed)).thenReturn("derived_secret")

        val result = sut.restoreSessionBackupState(
            PubkySessionBackupV1(kind = PubkySessionBackupKind.LocalSeed),
        )

        assertTrue(result.isSuccess)
        verifyBlocking(keychain) { upsertString(Keychain.Key.PUBKY_SECRET_KEY.name, "derived_secret") }
    }

    @Test
    fun `restoreSessionBackupState should save external session backups`() = test {
        val result = sut.restoreSessionBackupState(
            PubkySessionBackupV1(
                kind = PubkySessionBackupKind.ExternalSession,
                sessionSecret = "external_session",
            ),
        )

        assertTrue(result.isSuccess)
        verifyBlocking(keychain) { upsertString(Keychain.Key.PAYKIT_SESSION.name, "external_session") }
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
            publicKey = VALID_CONTACT_KEY_B,
            name = "Alice",
            bio = "",
            imageUrl = null,
            links = emptyList(),
            tags = listOf("old"),
            status = null,
        )
        val updated = original.copy(name = "Alice Updated", tags = listOf("new"))

        sut.addContact(VALID_CONTACT_KEY_B, existingProfile = original)
        sut.addContact(VALID_CONTACT_KEY_B, existingProfile = updated)

        val contacts = sut.contacts.value
        assertEquals(1, contacts.size)
        assertEquals(VALID_CONTACT_KEY_B, contacts.first().publicKey)
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
            publicKey = VALID_CONTACT_KEY_B,
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
    fun `loadContacts should treat missing contacts directory as empty`() = test {
        authenticateForTesting()
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn("test_secret")
        whenever(pubkyService.sessionList("test_secret", Env.contactsBasePath))
            .thenThrow(RuntimeException("Directory Not Found (404)"))

        sut.loadContacts()

        assertTrue(sut.contacts.value.isEmpty())
        assertFalse(sut.isLoadingContacts.value)
    }

    @Test
    fun `fetchContactProfile should return bitkit profile when available`() = test {
        val contactKey = VALID_CONTACT_KEY_A
        val strippedKey = contactKey.removePrefix("pubky")
        val json = """{"name":"Bob","bio":"Bio"}"""
        whenever(pubkyService.fetchFileString("pubky://$strippedKey${Env.profilePath}"))
            .thenReturn(json)

        val result = sut.fetchContactProfile(contactKey)

        assertTrue(result.isSuccess)
        assertEquals("Bob", result.getOrNull()?.name)
        verify(pubkyService, never()).getProfile(contactKey)
    }

    @Test
    fun `fetchContactProfile should fall back to pubky profile when bitkit profile is missing`() = test {
        val contactKey = VALID_CONTACT_KEY_A
        val strippedKey = contactKey.removePrefix("pubky")
        val contactProfile = mock<CorePubkyProfile>()
        whenever(pubkyService.fetchFileString("pubky://$strippedKey${Env.profilePath}"))
            .thenThrow(RuntimeException("Missing bitkit profile"))
        whenever(contactProfile.name).thenReturn("Bob")
        whenever(contactProfile.bio).thenReturn("Bio")
        whenever(pubkyService.getProfile(contactKey)).thenReturn(contactProfile)

        val result = sut.fetchContactProfile(contactKey)

        assertTrue(result.isSuccess)
        assertEquals("Bob", result.getOrNull()?.name)
    }

    @Test
    fun `fetchContactProfile should fall back to placeholder when remote profile is missing`() = test {
        val contactKey = VALID_CONTACT_KEY_A
        val strippedKey = contactKey.removePrefix("pubky")
        whenever(pubkyService.fetchFileString("pubky://$strippedKey${Env.profilePath}"))
            .thenThrow(RuntimeException("Missing bitkit profile"))
        whenever(pubkyService.getProfile(contactKey)).thenThrow(RuntimeException("Profile not found"))

        val result = sut.fetchContactProfile(contactKey)

        assertTrue(result.isSuccess)
        assertEquals(PubkyProfile.placeholder(contactKey), result.getOrNull())
    }

    @Test
    fun `fetchContactProfile should fail for invalid pubky format`() = test {
        val result = sut.fetchContactProfile("pubkyinvalid-short")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is PubkyContactError.InvalidFormat)
    }

    @Test
    fun `addContact should fail when adding current pubky`() = test {
        authenticateForTesting(publicKey = VALID_SELF_KEY)

        val result = sut.addContact(VALID_SELF_KEY)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is PubkyContactError.CannotAddSelf)
    }

    @Test
    fun `uploadAvatar should use session when managed key belongs to another pubky`() = test {
        val session = "test_session"
        val currentPublicKey = "pubkyalice"
        val staleSecretKey = "stale_secret"
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(staleSecretKey)
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn(session)
        whenever(pubkyService.publicKeyFromSecret(staleSecretKey)).thenReturn("pubkybob")

        authenticateForTesting(publicKey = currentPublicKey, secret = session, profileName = "Alice")
        clearInvocations(keychain)

        val result = sut.uploadAvatar(byteArrayOf(1, 2, 3))

        assertTrue(result.isSuccess)
        verifyBlocking(pubkyService, never()) { putWithSecretKey(any(), any(), any()) }
        verifyBlocking(pubkyService) { sessionPut(eq(session), any(), any()) }
        verifyBlocking(keychain) { delete(Keychain.Key.PUBKY_SECRET_KEY.name) }
    }

    @Test
    fun `uploadAvatar should use managed key when it matches current pubky`() = test {
        val session = "test_session"
        val currentPublicKey = "pubkyalice"
        val secretKey = "managed_secret"
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(secretKey)
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn(session)
        whenever(pubkyService.publicKeyFromSecret(secretKey)).thenReturn("alice")

        authenticateForTesting(publicKey = currentPublicKey, secret = session, profileName = "Alice")

        val result = sut.uploadAvatar(byteArrayOf(1, 2, 3))

        assertTrue(result.isSuccess)
        verifyBlocking(pubkyService) { putWithSecretKey(eq(secretKey), any(), any()) }
        verifyBlocking(pubkyService, never()) { sessionPut(eq(session), any(), any()) }
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
    fun `wipeLocalState should clear pubky state without server sign out`() = test {
        authenticateForTesting()
        clearInvocations(pubkyStore)
        val contact = PubkyProfile(
            publicKey = "pubkycontact4",
            name = "Charlie",
            bio = "",
            imageUrl = null,
            links = emptyList(),
            tags = emptyList(),
            status = null,
        )
        sut.addContact(contact.publicKey, existingProfile = contact)

        sut.wipeLocalState()

        assertNull(sut.publicKey.value)
        assertNull(sut.profile.value)
        assertTrue(sut.contacts.value.isEmpty())
        assertFalse(sut.isAuthenticated.value)
        verify(pubkyService, never()).signOut()
        verifyBlocking(pubkyStore) { reset() }
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
        whenever { pubkyService.completeAuth() }.thenReturn(secret)
        whenever { pubkyService.importSession(secret) }.thenReturn(publicKey)
        val ffiProfile = createFfiProfile(name = profileName)
        whenever { pubkyService.getProfile(publicKey) }.thenReturn(ffiProfile)
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn(secret)
        whenever { pubkyService.sessionList(secret, Env.contactsBasePath) }.thenReturn(emptyList())

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

package to.bitkit.repositories

import app.cash.turbine.test
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import com.synonym.paykit.ContactProfileResolution
import com.synonym.paykit.ContactProfileSource
import com.synonym.paykit.ContactRecord
import com.synonym.paykit.PaykitException
import com.synonym.paykit.PaykitProfile
import com.synonym.paykit.PubkyAuthCompanionClaim
import com.synonym.paykit.PubkySessionBootstrapResult
import com.synonym.paykit.PublicationStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.clearInvocations
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import to.bitkit.data.PubkyCachedProfile
import to.bitkit.data.PubkyImageCacheEpoch
import to.bitkit.data.PubkyStore
import to.bitkit.data.PubkyStoreData
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.data.sharedpubky.SharedPubkyClient
import to.bitkit.data.sharedpubky.SharedPubkyContract
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.PubkyAuthClaim
import to.bitkit.models.PubkyAuthRequest
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkySessionBackupKind
import to.bitkit.models.PubkySessionBackupV1
import to.bitkit.services.PaykitReadLane
import to.bitkit.services.PubkyRingAuthTimeoutError
import to.bitkit.services.PubkyService
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import com.synonym.paykit.PubkyProfile as SdkPubkyProfile

@Suppress("LargeClass")
class PubkyRepoTest : BaseUnitTest() {
    companion object {
        // Valid 52-char z-base-32 key (+ "pubky" prefix = 57 chars)
        private const val VALID_CONTACT_KEY_A = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xy"
        private const val NON_CANONICAL_CONTACT_KEY_A = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        private const val VALID_CONTACT_KEY_B = "pubky1rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xy"
        private const val VALID_SELF_KEY = "pubky5rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xy"
    }

    private lateinit var sut: PubkyRepo

    private val pubkyService = mock<PubkyService>()
    private val keychain = mock<Keychain>()
    private val sharedPubkyClient = mock<SharedPubkyClient>()
    private val imageLoader = mock<ImageLoader>()
    private val imageCacheEpoch = PubkyImageCacheEpoch()
    private val pubkyStore = mock<PubkyStore>()
    private val settingsStore = mock<SettingsStore>()
    private val settingsFlow = MutableStateFlow(SettingsData())
    private val profileSetupPending = MutableStateFlow(false)
    private var adoptedSource: String? = null

    @Before
    fun setUp() = runBlocking {
        adoptedSource = null
        settingsFlow.value = SettingsData()
        whenever(pubkyStore.data).thenReturn(flowOf(PubkyStoreData()))
        whenever(settingsStore.data).thenReturn(settingsFlow)
        whenever(settingsStore.isPubkyProfileSetupPending).thenReturn(profileSetupPending)
        whenever { settingsStore.setPubkyProfileSetupPending(any()) }.thenAnswer {
            profileSetupPending.value = it.getArgument(0)
            Unit
        }
        whenever(pubkyService.contactRecords()).thenReturn(emptyList())
        whenever { settingsStore.update(any()) }.thenAnswer {
            val transform = it.getArgument<(SettingsData) -> SettingsData>(0)
            settingsFlow.value = transform(settingsFlow.value)
            Unit
        }
        whenever { keychain.loadString(Keychain.Key.SHARED_PUBKY_SOURCE.name) }.thenAnswer { adoptedSource }
        whenever { keychain.upsertString(eq(Keychain.Key.SHARED_PUBKY_SOURCE.name), any()) }.thenAnswer {
            adoptedSource = it.getArgument(1)
            Unit
        }
        whenever { keychain.delete(Keychain.Key.SHARED_PUBKY_SOURCE.name) }.thenAnswer {
            adoptedSource = null
            Unit
        }
        sut = createSut()
    }

    private fun createSut(httpClient: HttpClient = mock()) = PubkyRepo(
        ioDispatcher = testDispatcher,
        pubkyService = pubkyService,
        keychain = keychain,
        sharedPubkyClient = sharedPubkyClient,
        imageLoader = imageLoader,
        imageCacheEpoch = imageCacheEpoch,
        pubkyStore = pubkyStore,
        settingsStore = settingsStore,
        httpClient = httpClient,
    )

    private fun stubAdoptedRingSource() {
        adoptedSource = SharedPubkyContract.RING_SOURCE_PREFIX + VALID_SELF_KEY.removePrefix("pubky")
    }

    @Test
    fun `initial state should have no public key`() = test {
        assertNull(sut.publicKey.value)
        assertFalse(sut.isAuthenticated.value)
    }

    @Test
    fun `Ring signup registers and authorizes before activating the local session`() = test {
        val events = mutableListOf<String>()
        val registeredSession = mock<PubkySessionBootstrapResult>()
        val request = ringSignupRequest()
        stubSignupKeys()
        whenever(pubkyService.registerIdentity("secret", "homeserver", "invite")).thenAnswer {
            events += "register"
            registeredSession
        }
        whenever(pubkyService.approveRingAuth(requireNotNull(request.authorizationUrl), "secret")).thenAnswer {
            events += "authorize"
        }
        whenever(pubkyService.activateRegisteredIdentity(registeredSession)).thenAnswer { events += "activate" }

        val result = sut.approveSignupAuth(request)

        assertTrue(result.isSuccess)
        assertEquals(listOf("register", "authorize", "activate"), events)
        verifyBlocking(pubkyService, never()) { signIn(any()) }
        assertTrue(profileSetupPending.value)
        assertEquals(VALID_SELF_KEY, sut.publicKey.value)
    }

    @Test
    fun `Ring signup does not activate the registered session when authorization fails`() = test {
        val registeredSession = mock<PubkySessionBootstrapResult>()
        val request = ringSignupRequest()
        stubSignupKeys()
        whenever(pubkyService.registerIdentity("secret", "homeserver", "invite")).thenReturn(registeredSession)
        whenever(pubkyService.approveRingAuth(requireNotNull(request.authorizationUrl), "secret"))
            .thenThrow(IllegalStateException("authorization failed"))

        assertTrue(sut.approveSignupAuth(request).isFailure)
        verifyBlocking(pubkyService, never()) { activateRegisteredIdentity(any()) }
        assertFalse(profileSetupPending.value)
        assertNull(sut.publicKey.value)
    }

    @Test
    fun `Ring signup can retry after relay timeout without activating the timed out session`() = test {
        val registeredSession = mock<PubkySessionBootstrapResult>()
        val request = ringSignupRequest()
        stubSignupKeys()
        whenever(pubkyService.registerIdentity("secret", "homeserver", "invite")).thenReturn(registeredSession)
        whenever(pubkyService.approveRingAuth(requireNotNull(request.authorizationUrl), "secret"))
            .thenAnswer { throw AppError(PubkyRingAuthTimeoutError()) }
            .thenReturn(Unit)

        assertTrue(sut.approveSignupAuth(request).isFailure)
        verifyBlocking(pubkyService, never()) { activateRegisteredIdentity(any()) }
        assertNull(sut.publicKey.value)
        assertFalse(profileSetupPending.value)

        assertTrue(sut.approveSignupAuth(request).isSuccess)
        verifyBlocking(pubkyService) { activateRegisteredIdentity(registeredSession) }
        assertEquals(VALID_SELF_KEY, sut.publicKey.value)
        assertTrue(profileSetupPending.value)
    }

    @Test
    fun `Ring signup clears profile setup state when local activation fails`() = test {
        val registeredSession = mock<PubkySessionBootstrapResult>()
        val request = ringSignupRequest()
        profileSetupPending.value = true
        stubSignupKeys()
        whenever(pubkyService.registerIdentity("secret", "homeserver", "invite")).thenReturn(registeredSession)
        whenever(pubkyService.activateRegisteredIdentity(registeredSession)).thenAnswer {
            throw TestAppError("activation failed")
        }

        assertTrue(sut.approveSignupAuth(request).isFailure)
        assertFalse(profileSetupPending.value)
        assertNull(sut.publicKey.value)
    }

    @Test
    fun `direct signup skips app authorization and activates the registered session`() = test {
        val registeredSession = mock<PubkySessionBootstrapResult>()
        val request = directSignupRequest()
        stubSignupKeys()
        whenever(pubkyService.registerIdentity("secret", "homeserver", "invite")).thenReturn(registeredSession)

        assertTrue(sut.approveSignupAuth(request).isSuccess)
        verifyBlocking(pubkyService, never()) { approveRingAuth(any(), any(), any()) }
        verifyBlocking(pubkyService) { activateRegisteredIdentity(registeredSession) }
        assertTrue(profileSetupPending.value)
        assertEquals(VALID_SELF_KEY, sut.publicKey.value)
    }

    @Test
    fun `signup clears a stale ring reference before registering`() = test {
        val events = mutableListOf<String>()
        val registeredSession = mock<PubkySessionBootstrapResult>()
        stubSignupKeys()
        stubAdoptedRingSource()
        whenever(keychain.delete(Keychain.Key.SHARED_PUBKY_SOURCE.name)).thenAnswer { events += "clear" }
        whenever(pubkyService.registerIdentity("secret", "homeserver", "invite")).thenAnswer {
            events += "register"
            registeredSession
        }
        whenever(pubkyService.activateRegisteredIdentity(registeredSession)).thenAnswer { events += "activate" }

        assertTrue(sut.approveSignupAuth(directSignupRequest()).isSuccess)
        assertEquals(listOf("clear", "register", "activate"), events)
    }

    @Test
    fun `signup keeps the ring reference when already signed in`() = test {
        stubAdoptedRingSource()
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn("session")

        assertTrue(sut.approveSignupAuth(directSignupRequest()).isFailure)
        verifyBlocking(keychain, never()) { delete(Keychain.Key.SHARED_PUBKY_SOURCE.name) }
        verifyBlocking(pubkyService, never()) { registerIdentity(any(), any(), any()) }
    }

    @Test
    fun `Ring signup stops when registration fails`() = test {
        val request = ringSignupRequest()
        stubSignupKeys()
        whenever(pubkyService.registerIdentity("secret", "homeserver", "invite"))
            .thenThrow(IllegalStateException("registration failed"))

        assertTrue(sut.approveSignupAuth(request).isFailure)
        verifyBlocking(pubkyService, never()) { approveRingAuth(any(), any(), any()) }
        verifyBlocking(pubkyService, never()) { activateRegisteredIdentity(any()) }
        assertFalse(profileSetupPending.value)
    }

    @Test
    fun `Ring signup clears credentials when pending setup persistence and rollback fail`() = test {
        stubSignupKeys()
        whenever(pubkyService.registerIdentity("secret", "homeserver", "invite"))
            .thenReturn(mock<PubkySessionBootstrapResult>())
        whenever(settingsStore.setPubkyProfileSetupPending(true)).thenAnswer {
            throw TestAppError("persistence failed")
        }
        whenever(pubkyService.forgetSessionAccess()).thenAnswer {
            throw TestAppError("cleanup failed")
        }

        assertTrue(sut.approveSignupAuth(ringSignupRequest()).isFailure)

        verify(keychain).delete(Keychain.Key.PAYKIT_SESSION.name)
        verify(keychain).delete(Keychain.Key.PUBKY_SECRET_KEY.name)
        assertNull(sut.publicKey.value)
        assertFalse(sut.isAuthenticated.value)
        assertFalse(profileSetupPending.value)
    }

    @Test
    fun `identity check fails closed when secure storage cannot be read`() = test {
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenThrow(IllegalStateException("unavailable"))

        assertTrue(runSuspendCatching { sut.hasIdentity() }.isFailure)
    }

    @Test
    fun `approveAuth should forward requested capabilities`() = test {
        val authUrl = "pubkyauth://signin?caps=/pub/bitkit.to/:rw"
        val capabilities = "/pub/bitkit.to/:rw"
        val clientId = "paykit.test"
        val secretKey = "local_secret"
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(secretKey)

        val result = sut.approveAuth(authUrl, capabilities, clientId)

        assertTrue(result.isSuccess)
        verifyBlocking(pubkyService) { approveAuth(authUrl, capabilities, clientId, secretKey) }
    }

    @Test
    fun `approveAuthWithCompanionClaim forwards exact claim identifiers and capability`() = test {
        val authUrl = "pubkyauth://signin?x-bitkit-claim=watch-only-account-v1"
        val clientId = "paykit.test"
        val secretKey = "local_secret"
        val payload = ByteArray(84) { it.toByte() }
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(secretKey)

        val result = sut.approveAuthWithCompanionClaim(authUrl, clientId, payload)

        assertTrue(result.isSuccess)
        verifyBlocking(pubkyService) {
            approveAuthWithCompanionClaim(
                authUrl = authUrl,
                expectedCapabilities = PubkyAuthClaim.WATCH_ONLY_ACCOUNT_CAPABILITIES,
                approvedClientId = clientId,
                secretKeyHex = secretKey,
                claim = PubkyAuthCompanionClaim(
                    queryParameter = PubkyAuthClaim.QUERY_PARAMETER,
                    claimType = PubkyAuthClaim.WATCH_ONLY_ACCOUNT_V1.wireValue,
                    unsignedPayload = payload,
                ),
            )
        }
    }

    @Test
    fun `createIdentity should forget session when incomplete session revocation fails`() = test {
        val httpClient = identityHttpClient()
        sut = createSut(httpClient)
        whenever(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn("test mnemonic")
        whenever(pubkyService.deriveSecretKey("test mnemonic")).thenReturn("test-secret")
        whenever(pubkyService.publicKeyFromSecret("test-secret")).thenReturn(VALID_SELF_KEY.removePrefix("pubky"))
        whenever(pubkyService.signUp("test-secret", "test-homeserver", "test-code")).thenReturn(Unit)
        whenever(pubkyService.publishPaykitProfile(any())).thenAnswer { throw TestAppError("Publish failed") }
        whenever(pubkyService.signOut()).thenAnswer { throw TestAppError("Server error") }

        val result = sut.createIdentity(
            name = "Test",
            bio = "",
            links = emptyList(),
            tags = emptyList(),
            avatarBytes = null,
        )
        httpClient.close()

        assertTrue(result.isFailure)
        assertEquals("Publish failed", result.exceptionOrNull()?.message)
        verifyBlocking(pubkyService) { signUp("test-secret", "test-homeserver", "test-code") }
        verifyBlocking(pubkyService) { signOut() }
        verifyBlocking(pubkyService) { forgetSessionAccess() }
    }

    @Test
    fun `createIdentity clears stale pending signup without a session`() = test {
        profileSetupPending.value = true
        whenever(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(null)

        val result = sut.createIdentity("Test", "", emptyList(), emptyList(), null)

        assertTrue(result.isFailure)
        assertFalse(profileSetupPending.value)
        verify(keychain).loadString(Keychain.Key.BIP39_MNEMONIC.name)
        verifyBlocking(pubkyService, never()) { publishPaykitProfile(any()) }
    }

    @Test
    fun `createIdentity restores a stored local key without Homegate signup`() = test {
        val httpClient = identityHttpClient()
        sut = createSut(httpClient)
        profileSetupPending.value = true
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn("local-secret")
        whenever(pubkyService.publicKeyFromSecret("local-secret")).thenReturn(VALID_SELF_KEY.removePrefix("pubky"))
        whenever(pubkyService.publishPaykitProfile(any())).thenReturn(mock())

        val result = sut.createIdentity("Restored", "", emptyList(), emptyList(), null)

        assertTrue(result.isSuccess)
        assertEquals(VALID_SELF_KEY, sut.publicKey.value)
        assertEquals("Restored", sut.profile.value?.name)
        assertFalse(profileSetupPending.value)
        assertTrue((httpClient.engine as MockEngine).requestHistory.isEmpty())
        verifyBlocking(pubkyService) { signIn("local-secret") }
        verifyBlocking(pubkyService, never()) { signUp(any(), any(), any()) }
        verify(keychain, never()).loadString(Keychain.Key.BIP39_MNEMONIC.name)
        httpClient.close()
    }

    @Test
    fun `createIdentity stops when the local key cannot be read`() = test {
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenAnswer {
            throw TestAppError("unavailable")
        }

        val result = sut.createIdentity("Test", "", emptyList(), emptyList(), null)

        assertEquals("unavailable", result.exceptionOrNull()?.message)
        verify(keychain, never()).loadString(Keychain.Key.BIP39_MNEMONIC.name)
        verifyBlocking(pubkyService, never()) { signIn(any()) }
        verifyBlocking(pubkyService, never()) { signUp(any(), any(), any()) }
        verifyBlocking(pubkyService, never()) { publishPaykitProfile(any()) }
    }

    @Test
    fun `createIdentity retries local sign in without deleting the existing identity`() = test {
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn("local-secret")
        whenever(pubkyService.publicKeyFromSecret("local-secret")).thenReturn(VALID_SELF_KEY)
        whenever(pubkyService.signIn("local-secret")).thenAnswer { throw TestAppError("offline") }.thenReturn(Unit)
        whenever(pubkyService.publishPaykitProfile(any())).thenReturn(mock())

        val firstResult = sut.createIdentity("Test", "", emptyList(), emptyList(), null)

        assertEquals("offline", firstResult.exceptionOrNull()?.message)
        assertNull(sut.publicKey.value)
        verifyBlocking(pubkyService, never()) { publishPaykitProfile(any()) }

        assertTrue(sut.createIdentity("Test", "", emptyList(), emptyList(), null).isSuccess)
        assertEquals(VALID_SELF_KEY, sut.publicKey.value)
        verifyBlocking(pubkyService, times(2)) { signIn("local-secret") }
        verifyBlocking(pubkyService, never()) { signUp(any(), any(), any()) }
        verifyBlocking(pubkyService, never()) { signOut() }
        verifyBlocking(pubkyService, never()) { forgetSessionAccess() }
        verify(keychain, never()).delete(Keychain.Key.PUBKY_SECRET_KEY.name)
    }

    @Test
    fun `createIdentity preserves the existing identity when profile publication fails`() = test {
        authenticateForTesting(publicKey = VALID_SELF_KEY)
        val existingProfile = sut.profile.value
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn("local-secret")
        whenever(pubkyService.publicKeyFromSecret("local-secret")).thenReturn(VALID_SELF_KEY)
        whenever(pubkyService.publishPaykitProfile(any()))
            .thenAnswer { throw TestAppError("offline") }
            .thenReturn(mock())

        val firstResult = sut.createIdentity("Updated", "", emptyList(), emptyList(), null)

        assertEquals("offline", firstResult.exceptionOrNull()?.message)
        assertEquals(VALID_SELF_KEY, sut.publicKey.value)
        assertEquals(existingProfile, sut.profile.value)
        assertTrue(sut.isAuthenticated.value)

        assertTrue(sut.createIdentity("Updated", "", emptyList(), emptyList(), null).isSuccess)
        verifyBlocking(pubkyService, never()) { signIn(any()) }
        verifyBlocking(pubkyService, never()) { signUp(any(), any(), any()) }
        verifyBlocking(pubkyService, never()) { signOut() }
        verifyBlocking(pubkyService, never()) { forgetSessionAccess() }
        verify(keychain, never()).delete(Keychain.Key.PUBKY_SECRET_KEY.name)
    }

    @Test
    fun `createIdentity preserves local recovery after cancellation`() = test {
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn("local-secret")
        whenever(pubkyService.publicKeyFromSecret("local-secret")).thenReturn(VALID_SELF_KEY)
        var cancelDuringSignIn: Boolean? = true
        var operationStarted = CompletableDeferred<Unit>()
        whenever(pubkyService.signIn("local-secret")).doSuspendableAnswer {
            if (cancelDuringSignIn == true) {
                operationStarted.complete(Unit)
                awaitCancellation()
            }
            Unit
        }
        whenever(pubkyService.publishPaykitProfile(any())).doSuspendableAnswer {
            if (cancelDuringSignIn == false) {
                operationStarted.complete(Unit)
                awaitCancellation()
            }
            mock()
        }
        for (duringSignIn in listOf(true, false)) {
            cancelDuringSignIn = duringSignIn
            operationStarted = CompletableDeferred()

            val result = async { sut.createIdentity("Test", "", emptyList(), emptyList(), null) }
            operationStarted.await()
            result.cancelAndJoin()

            assertTrue(result.isCancelled)
            assertNull(sut.publicKey.value)
        }

        cancelDuringSignIn = null
        assertTrue(sut.createIdentity("Test", "", emptyList(), emptyList(), null).isSuccess)
        verifyBlocking(pubkyService, never()) { signUp(any(), any(), any()) }
        verifyBlocking(pubkyService, never()) { signOut() }
        verifyBlocking(pubkyService, never()) { forgetSessionAccess() }
        verify(keychain, never()).delete(Keychain.Key.PUBKY_SECRET_KEY.name)
    }

    @Test
    fun `createIdentity signs up when a Ring session has no local key`() = test {
        val httpClient = identityHttpClient()
        sut = createSut(httpClient)
        stubSignupKeys()
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn("ring-session")
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn("")
        whenever(pubkyService.publishPaykitProfile(any())).thenReturn(mock())

        val result = sut.createIdentity("Test", "", emptyList(), emptyList(), null)

        assertTrue(result.isSuccess)
        assertEquals(VALID_SELF_KEY, sut.publicKey.value)
        verifyBlocking(pubkyService) { signUp("secret", "test-homeserver", "test-code") }
        verifyBlocking(pubkyService, never()) { signIn(any()) }
        httpClient.close()
    }

    @Test
    fun `createIdentity should preserve signup session when pending profile publication fails`() = test {
        val registeredSession = mock<PubkySessionBootstrapResult>()
        stubSignupKeys()
        whenever(pubkyService.registerIdentity("secret", "homeserver", "invite")).thenReturn(registeredSession)
        assertTrue(sut.approveSignupAuth(ringSignupRequest()).isSuccess)
        clearInvocations(pubkyService)
        whenever(pubkyService.publishPaykitProfile(any())).thenAnswer { throw TestAppError("Publish failed") }

        val result = sut.createIdentity(
            name = "Test",
            bio = "",
            links = emptyList(),
            tags = emptyList(),
            avatarBytes = null,
        )

        assertTrue(result.isFailure)
        verifyBlocking(pubkyService) { publishPaykitProfile(any()) }
        verifyBlocking(pubkyService, never()) { signUp(any(), any(), any()) }
        verifyBlocking(pubkyService, never()) { signIn(any()) }
        verifyBlocking(pubkyService, never()) { signOut() }
        assertTrue(profileSetupPending.value)
    }

    @Test
    fun `createIdentity should keep session when canceled during contact load`() = test {
        val contactsLoadStarted = CompletableDeferred<Unit>()
        val finishContactsLoad = CompletableDeferred<Unit>()
        val httpClient = identityHttpClient()
        sut = createSut(httpClient)
        whenever(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn("test mnemonic")
        whenever(pubkyService.deriveSecretKey("test mnemonic")).thenReturn("test-secret")
        whenever(pubkyService.publicKeyFromSecret("test-secret")).thenReturn(VALID_SELF_KEY.removePrefix("pubky"))
        whenever(pubkyService.signUp("test-secret", "test-homeserver", "test-code")).thenReturn(Unit)
        whenever(pubkyService.publishPaykitProfile(any())).thenReturn(mock())
        whenever(pubkyService.resolveContactProfile(VALID_SELF_KEY, true))
            .thenReturn(createResolution(VALID_SELF_KEY, pubkyProfile = createPubkyProfile()))
        whenever(pubkyService.contactRecords()).doSuspendableAnswer {
            contactsLoadStarted.complete(Unit)
            finishContactsLoad.await()
            emptyList()
        }

        val result = async {
            sut.createIdentity(
                name = "Test",
                bio = "",
                links = emptyList(),
                tags = emptyList(),
                avatarBytes = null,
            )
        }
        contactsLoadStarted.await()

        assertTrue(sut.isAuthenticated.value)
        result.cancel()
        finishContactsLoad.complete(Unit)
        result.join()
        httpClient.close()

        assertTrue(sut.isAuthenticated.value)
        verifyBlocking(pubkyService, never()) { signOut() }
    }

    @Test
    fun `createIdentity returns cache failure after profile loading`() = test {
        authenticateForTesting(publicKey = VALID_SELF_KEY)
        profileSetupPending.value = true
        whenever(pubkyService.publishPaykitProfile(any())).thenReturn(mock())
        var cacheUpdateCount = 0
        whenever { pubkyStore.update(any()) }.thenAnswer {
            cacheUpdateCount += 1
            if (cacheUpdateCount == 2) throw TestAppError("cache failed")
            Unit
        }

        val result = sut.createIdentity("Test", "", emptyList(), emptyList(), null)

        assertEquals("cache failed", result.exceptionOrNull()?.message)
        assertEquals(2, cacheUpdateCount)
    }

    @Test
    fun `createIdentity retries a profile that is not yet readable after publishing`() = test {
        authenticateForTesting(publicKey = VALID_SELF_KEY)
        profileSetupPending.value = true
        whenever(pubkyService.publishPaykitProfile(any())).thenReturn(mock())
        whenever(pubkyService.resolveContactProfile(VALID_SELF_KEY, true))
            .thenReturn(null)
            .thenReturn(createResolution(VALID_SELF_KEY, pubkyProfile = createPubkyProfile(name = "Published")))
        clearInvocations(pubkyService)

        val result = sut.createIdentity("Test", "", emptyList(), emptyList(), null)

        assertTrue(result.isSuccess)
        assertEquals("Published", sut.profile.value?.name)
        verify(pubkyService, times(2)).resolveContactProfile(VALID_SELF_KEY, true)
    }

    @Test
    fun `wipe completes while identity creation waits for contact loading`() = test {
        authenticateForTesting(publicKey = VALID_SELF_KEY)
        profileSetupPending.value = true
        val contactLoadStarted = CompletableDeferred<Unit>()
        val finishContactLoad = CompletableDeferred<Unit>()
        val profilePublished = CompletableDeferred<Unit>()
        whenever(pubkyService.contactRecords()).doSuspendableAnswer {
            if (!contactLoadStarted.isCompleted) {
                contactLoadStarted.complete(Unit)
                finishContactLoad.await()
            }
            emptyList()
        }
        whenever(pubkyService.publishPaykitProfile(any())).doSuspendableAnswer {
            profilePublished.complete(Unit)
            mock()
        }
        val activeContactLoad = async { sut.loadContacts() }
        contactLoadStarted.await()

        val creation = async {
            sut.createIdentity("Test", "", emptyList(), emptyList(), null)
        }
        profilePublished.await()

        try {
            sut.wipeLocalState()

            assertFalse(creation.isCompleted)
            assertNull(sut.publicKey.value)
        } finally {
            finishContactLoad.complete(Unit)
        }

        activeContactLoad.await()
        assertTrue(creation.await().isSuccess)
        assertNull(sut.publicKey.value)
    }

    @Test
    fun `loadProfile should update profile on success`() = test {
        authenticateForTesting()

        val pk = checkNotNull(sut.publicKey.value) { "publicKey should be set after authentication" }
        val pubkyProfile = createPubkyProfile(
            name = "Profile Name",
            bio = "A bio",
            image = "pubky://image_uri",
            status = "active",
        )
        whenever(pubkyService.resolveContactProfile(pk, true))
            .thenReturn(createResolution(pk, pubkyProfile = pubkyProfile))

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
        whenever(pubkyService.resolveContactProfile(pk, true)).thenAnswer { throw TestAppError("Network error") }

        sut.loadProfile()

        assertEquals(existingProfile, sut.profile.value)
        assertFalse(sut.isLoadingProfile.value)
    }

    @Test
    fun `loadProfile retries a failed own profile resolution once`() = test {
        authenticateForTesting(publicKey = VALID_SELF_KEY)
        whenever(pubkyService.resolveContactProfile(VALID_SELF_KEY, true))
            .thenAnswer { throw TestAppError("Network error") }
        clearInvocations(pubkyService)

        sut.loadProfile()

        verify(pubkyService, times(2)).resolveContactProfile(VALID_SELF_KEY, true)
    }

    @Test
    fun `loadProfile should return early when no public key`() = test {
        sut.loadProfile()

        verify(pubkyService, never()).resolveContactProfile(any(), any(), any())
    }

    @Test
    fun `loadProfile should cache metadata on success`() = test {
        authenticateForTesting()

        val pk = checkNotNull(sut.publicKey.value) { "publicKey should be set after authentication" }
        val pubkyProfile = createPubkyProfile(name = "Cached Name", image = "pubky://cached_image")
        whenever(pubkyService.resolveContactProfile(pk, true))
            .thenReturn(createResolution(pk, pubkyProfile = pubkyProfile))

        sut.loadProfile()

        verifyBlocking(pubkyStore, atLeastOnce()) { update(any()) }
    }

    @Test
    fun `loadProfile drops a result that a profile save overtook`() = test {
        var cached = PubkyStoreData()
        whenever(pubkyStore.update(any())).thenAnswer {
            cached = it.getArgument<(PubkyStoreData) -> PubkyStoreData>(0)(cached)
            Unit
        }
        authenticateForTesting(publicKey = VALID_SELF_KEY, profileName = "Old")
        val loadStarted = CompletableDeferred<Unit>()
        val finishLoad = CompletableDeferred<Unit>()
        whenever(pubkyService.resolveContactProfile(VALID_SELF_KEY, true)).doSuspendableAnswer {
            loadStarted.complete(Unit)
            finishLoad.await()
            createResolution(VALID_SELF_KEY, pubkyProfile = createPubkyProfile(name = "Old"))
        }
        val load = async { sut.loadProfile() }
        loadStarted.await()

        val save = sut.saveProfile(name = "New", bio = "", links = emptyList(), tags = emptyList(), imageUrl = null)
        finishLoad.complete(Unit)
        load.await()

        assertTrue(save.isSuccess)
        assertEquals("New", sut.profile.value?.name)
        assertEquals("New", cached.cachedName)
    }

    @Test
    fun `loadProfile marks the profile as loading before its first suspension`() = test {
        authenticateForTesting(publicKey = VALID_SELF_KEY)
        val finishLoad = CompletableDeferred<Unit>()
        whenever(pubkyService.resolveContactProfile(VALID_SELF_KEY, true)).doSuspendableAnswer {
            finishLoad.await()
            createResolution(VALID_SELF_KEY, pubkyProfile = createPubkyProfile())
        }
        assertFalse(sut.isLoadingProfile.value)

        val load = launch(start = CoroutineStart.UNDISPATCHED) { sut.loadProfile() }

        assertTrue(sut.isLoadingProfile.value)
        finishLoad.complete(Unit)
        load.join()
        assertFalse(sut.isLoadingProfile.value)
    }

    @Test
    fun `loadProfile drops a result that a profile deletion overtook`() = test {
        var cached = PubkyStoreData()
        whenever(pubkyStore.update(any())).thenAnswer {
            cached = it.getArgument<(PubkyStoreData) -> PubkyStoreData>(0)(cached)
            Unit
        }
        whenever(pubkyStore.reset()).thenAnswer {
            cached = PubkyStoreData()
            Unit
        }
        authenticateForTesting(publicKey = VALID_SELF_KEY, profileName = "Old")
        val loadStarted = CompletableDeferred<Unit>()
        val finishLoad = CompletableDeferred<Unit>()
        whenever(pubkyService.resolveContactProfile(VALID_SELF_KEY, true)).doSuspendableAnswer {
            loadStarted.complete(Unit)
            finishLoad.await()
            createResolution(VALID_SELF_KEY, pubkyProfile = createPubkyProfile(name = "Loaded"))
        }
        val load = async { sut.loadProfile() }
        loadStarted.await()

        val delete = sut.deleteProfile()
        finishLoad.complete(Unit)
        load.await()

        assertTrue(delete.isSuccess)
        assertNull(sut.profile.value)
        assertNull(cached.cachedName)
        assertNull(cached.cachedProfileOwner)
        assertNull(cached.ownerPublicKey)
    }

    @Test
    fun `loadProfile does not overwrite cached metadata saved after its in-memory check`() = test {
        val store = stubGatedPubkyStore()
        authenticateForTesting(publicKey = VALID_SELF_KEY, profileName = "Old")
        gateNextProfileLoadStoreWrite(store, loadedName = "Old")
        val load = async { sut.loadProfile() }
        store.gatedUpdateStarted.await()

        val save = sut.saveProfile(name = "New", bio = "", links = emptyList(), tags = emptyList(), imageUrl = null)
        store.releaseGatedUpdate.complete(Unit)
        load.await()

        assertTrue(save.isSuccess)
        assertEquals("New", sut.profile.value?.name)
        assertEquals("New", store.data.cachedName)
        assertEquals(VALID_SELF_KEY, store.data.cachedProfileOwner)
    }

    @Test
    fun `loadProfile does not restore cached metadata that sign out cleared after its in-memory check`() = test {
        val store = stubGatedPubkyStore()
        authenticateForTesting(publicKey = VALID_SELF_KEY, profileName = "Old")
        gateNextProfileLoadStoreWrite(store, loadedName = "Loaded")
        val load = async { sut.loadProfile() }
        store.gatedUpdateStarted.await()

        val signOut = sut.signOut()
        store.releaseGatedUpdate.complete(Unit)
        load.await()

        assertTrue(signOut.isSuccess)
        assertNull(store.data.cachedName)
        assertNull(store.data.cachedProfileOwner)
        assertNull(store.data.ownerPublicKey)
    }

    @Test
    fun `loadProfile does not restore cached metadata written right after sign out resets the store`() = test {
        val store = stubGatedPubkyStore()
        whenever(pubkyStore.reset()).doSuspendableAnswer {
            store.data = PubkyStoreData()
            store.releaseGatedUpdate.complete(Unit)
            store.gatedUpdateApplied.await()
        }
        authenticateForTesting(publicKey = VALID_SELF_KEY, profileName = "Old")
        gateNextProfileLoadStoreWrite(store, loadedName = "Loaded")
        val load = async { sut.loadProfile() }
        store.gatedUpdateStarted.await()

        val signOut = sut.signOut()
        load.await()

        assertTrue(signOut.isSuccess)
        assertTrue(store.gatedUpdateApplied.isCompleted)
        assertNull(store.data.cachedName)
        assertNull(store.data.cachedProfileOwner)
        assertNull(store.data.ownerPublicKey)
    }

    @Test
    fun `fetchDisplayProfile resolves once without retrying a missing profile or an error`() = test {
        whenever(pubkyService.resolveContactProfile(VALID_CONTACT_KEY_A, true)).thenReturn(null)
        whenever(pubkyService.resolveContactProfile(VALID_CONTACT_KEY_B, true))
            .thenAnswer { throw TestAppError("Unreachable") }

        val missing = sut.fetchDisplayProfile(VALID_CONTACT_KEY_A.removePrefix("pubky"))
        val failed = sut.fetchDisplayProfile(VALID_CONTACT_KEY_B)

        assertTrue(missing.isSuccess)
        assertNull(missing.getOrNull())
        assertTrue(failed.isFailure)
        verify(pubkyService, times(1)).resolveContactProfile(VALID_CONTACT_KEY_A, true)
        verify(pubkyService, times(1)).resolveContactProfile(VALID_CONTACT_KEY_B, true)
    }

    @Test
    fun `saveProfile should mark backup state changed`() = test {
        authenticateForTesting(publicKey = VALID_SELF_KEY, secret = "test_session", profileName = "Alice")
        val backupVersion = sut.backupStateVersion.value

        val result = sut.saveProfile(
            name = "Alice Updated",
            bio = "Updated bio",
            links = emptyList(),
            tags = emptyList(),
            imageUrl = null,
        )

        assertTrue(result.isSuccess)
        assertEquals(backupVersion + 1, sut.backupStateVersion.value)
        verifyBlocking(pubkyService) { publishPaykitProfile(any()) }
    }

    @Test
    fun `hasSecretKey should mark backup state changed when stale local secret is removed`() = test {
        authenticateForTesting(publicKey = VALID_SELF_KEY)
        val staleSecret = "stale_secret"
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(staleSecret)
        whenever(pubkyService.publicKeyFromSecret(staleSecret)).thenReturn(VALID_CONTACT_KEY_A.removePrefix("pubky"))
        val backupVersion = sut.backupStateVersion.value

        val result = sut.hasSecretKey()

        assertFalse(result)
        assertEquals(backupVersion + 1, sut.backupStateVersion.value)
        verifyBlocking(keychain) { delete(Keychain.Key.PUBKY_SECRET_KEY.name) }
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
    fun `signOut should clear public Paykit sharing settings`() = test {
        authenticateForTesting()
        settingsFlow.value = SettingsData(
            hasConfirmedPublicPaykitEndpoints = true,
            sharesPublicPaykitEndpoints = true,
            sharesPrivatePaykitEndpoints = true,
            publicPaykitBolt11 = "lnbc1old",
            publicPaykitBolt11PaymentHash = "010203",
            publicPaykitBolt11ExpiresAtMillis = 123L,
        )

        val result = sut.signOut()

        assertTrue(result.isSuccess)
        assertFalse(settingsFlow.value.hasConfirmedPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        assertEquals("", settingsFlow.value.publicPaykitBolt11)
        assertEquals("", settingsFlow.value.publicPaykitBolt11PaymentHash)
        assertEquals(0, settingsFlow.value.publicPaykitBolt11ExpiresAtMillis)
    }

    @Test
    fun `removeBitkitPaymentEndpoints delegates to Pubky service cleanup`() = test {
        authenticateForTesting(publicKey = VALID_SELF_KEY)

        val result = sut.removeBitkitPaymentEndpoints()

        assertTrue(result.isSuccess)
        verifyBlocking(pubkyService) { removeBitkitPaymentEndpoints() }
    }

    @Test
    fun `signOut should continue when endpoint cleanup fails`() = test {
        authenticateForTesting(publicKey = VALID_SELF_KEY)
        settingsFlow.value = SettingsData(
            hasConfirmedPublicPaykitEndpoints = true,
            sharesPublicPaykitEndpoints = true,
            publicPaykitBolt11 = "lnbc1old",
            publicPaykitBolt11PaymentHash = "010203",
            publicPaykitBolt11ExpiresAtMillis = 123L,
        )
        whenever(pubkyService.removeBitkitPaymentEndpoints()).thenAnswer { throw TestAppError("Cleanup failed") }

        val result = sut.signOut()

        assertTrue(result.isSuccess)
        assertNull(sut.publicKey.value)
        assertFalse(sut.isAuthenticated.value)
        assertTrue(settingsFlow.value.publicPaykitCleanupPending)
        assertFalse(settingsFlow.value.hasConfirmedPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertEquals("", settingsFlow.value.publicPaykitBolt11)
        assertEquals("", settingsFlow.value.publicPaykitBolt11PaymentHash)
        assertEquals(0, settingsFlow.value.publicPaykitBolt11ExpiresAtMillis)
        verifyBlocking(pubkyService) { signOut() }
        verifyBlocking(keychain, atLeastOnce()) { delete(Keychain.Key.PAYKIT_SESSION.name) }
    }

    @Test
    fun `signOut should keep cleanup pending when private-only endpoint cleanup fails`() = test {
        authenticateForTesting(publicKey = VALID_SELF_KEY)
        settingsFlow.value = SettingsData(sharesPrivatePaykitEndpoints = true)
        whenever(pubkyService.removeBitkitPaymentEndpoints()).thenAnswer { throw TestAppError("Cleanup failed") }

        val result = sut.signOut()

        assertTrue(result.isSuccess)
        assertNull(sut.publicKey.value)
        assertFalse(sut.isAuthenticated.value)
        assertTrue(settingsFlow.value.publicPaykitCleanupPending)
        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        verifyBlocking(pubkyService) { signOut() }
        verifyBlocking(keychain, atLeastOnce()) { delete(Keychain.Key.PAYKIT_SESSION.name) }
    }

    @Test
    fun `signOut should evict pubky images from caches`() = test {
        authenticateForTesting()
        val memoryCache = mock<MemoryCache>()
        val diskCache = mock<DiskCache>()
        val memoryCacheKey = MemoryCache.Key("pubky://image_uri")
        whenever(memoryCache.keys).thenReturn(setOf(memoryCacheKey))
        whenever(imageLoader.memoryCache).thenReturn(memoryCache)
        whenever(imageLoader.diskCache).thenReturn(diskCache)

        sut.signOut()

        verify(memoryCache).remove(memoryCacheKey)
        verify(diskCache).clear()
        verify(diskCache, never()).remove(any())
    }

    @Test
    fun `signOut advances the pubky image cache epoch before clearing the disk cache`() = test {
        authenticateForTesting()
        val diskCache = mock<DiskCache>()
        whenever(imageLoader.diskCache).thenReturn(diskCache)
        val epochBeforeSignOut = imageCacheEpoch.current()
        var epochAtClear: Long? = null
        doAnswer { epochAtClear = imageCacheEpoch.current() }.whenever(diskCache).clear()

        sut.signOut()

        assertEquals(epochBeforeSignOut + 1, epochAtClear)
    }

    @Test
    fun `signOut completes when clearing the pubky image disk cache fails`() = test {
        authenticateForTesting()
        val diskCache = mock<DiskCache>()
        doThrow(IllegalStateException("disk cache unavailable")).whenever(diskCache).clear()
        whenever(imageLoader.diskCache).thenReturn(diskCache)

        val result = sut.signOut()

        assertTrue(result.isSuccess)
        assertNull(sut.publicKey.value)
    }

    @Test
    fun `adoptRingIdentity clears the pubky image disk cache when the identity changes`() = test {
        authenticateForTesting(publicKey = VALID_CONTACT_KEY_A, profileName = "Previous")
        val diskCache = mock<DiskCache>()
        whenever(imageLoader.diskCache).thenReturn(diskCache)
        val ringPubky = stubRingCredential()
        whenever(pubkyService.signIn("ring_secret")).thenReturn(Unit)

        sut.adoptRingIdentity(ringPubky)

        verify(diskCache).clear()
    }

    @Test
    fun `adoptRingIdentity keeps the pubky image disk cache for the same identity`() = test {
        authenticateForTesting(publicKey = VALID_SELF_KEY)
        val diskCache = mock<DiskCache>()
        whenever(imageLoader.diskCache).thenReturn(diskCache)
        val ringPubky = stubRingCredential()
        whenever(pubkyService.signIn("ring_secret")).thenReturn(Unit)

        sut.adoptRingIdentity(ringPubky)

        verify(diskCache, never()).clear()
    }

    @Test
    fun `deleteProfile should fail when signOut fails`() = test {
        authenticateForTesting()
        settingsFlow.value = SettingsData(
            sharesPublicPaykitEndpoints = true,
            sharesPrivatePaykitEndpoints = true,
        )
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn("test_secret")
        whenever(pubkyService.signOut()).thenAnswer { throw TestAppError("Sign out failed") }

        val result = sut.deleteProfile()

        assertTrue(result.isFailure)
        assertFalse(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertFalse(settingsFlow.value.sharesPrivatePaykitEndpoints)
        assertTrue(settingsFlow.value.publicPaykitCleanupPending)
    }

    @Test
    fun `deleteProfileWithSessionRetry should refresh session and retry delete`() = test {
        val expiredSession = "expired_session"
        val newSession = "new_session"
        val secretKey = "local_secret"
        authenticateForTesting(publicKey = VALID_SELF_KEY, secret = expiredSession)
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn(expiredSession, newSession)
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(secretKey)
        whenever(pubkyService.deletePaykitProfile()).thenAnswer { throw TestAppError("Expired") }.thenReturn(Unit)
        whenever(pubkyService.signIn(secretKey)).thenReturn(Unit)
        whenever(pubkyService.publicKeyFromSecret(secretKey)).thenReturn(VALID_SELF_KEY.removePrefix("pubky"))

        val result = sut.deleteProfileWithSessionRetry()

        assertTrue(result.isSuccess)
        verifyBlocking(pubkyService, times(2)) { deletePaykitProfile() }
    }

    @Test
    fun `deleteProfileWithSessionRetry should return failure when session cannot refresh`() = test {
        val expiredSession = "expired_session"
        authenticateForTesting(publicKey = VALID_SELF_KEY, secret = expiredSession)
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn(expiredSession)
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(null)
        whenever(pubkyService.deletePaykitProfile()).thenAnswer { throw TestAppError("Expired") }

        val result = sut.deleteProfileWithSessionRetry()

        assertTrue(result.isFailure)
        verifyBlocking(pubkyService) { deletePaykitProfile() }
        verifyBlocking(pubkyService, never()) { signIn(any()) }
    }

    @Test
    fun `signOut should preserve local state when grant revocation fails`() = test {
        authenticateForTesting()
        settingsFlow.value = SettingsData(
            sharesPublicPaykitEndpoints = true,
            sharesPrivatePaykitEndpoints = true,
        )
        whenever(pubkyService.signOut()).thenAnswer { throw TestAppError("Server error") }

        val result = sut.signOut()

        assertTrue(result.isFailure)
        verifyBlocking(pubkyService, never()) { forgetSessionAccess() }
        verifyBlocking(keychain, never()) { delete(Keychain.Key.PAYKIT_SESSION.name) }
        assertTrue(sut.isAuthenticated.value)
        assertTrue(settingsFlow.value.sharesPublicPaykitEndpoints)
        assertTrue(settingsFlow.value.sharesPrivatePaykitEndpoints)
        assertTrue(settingsFlow.value.publicPaykitCleanupPending)
    }

    @Test
    fun `signOut should finish clearing state after caller cancellation`() = test {
        val revocationStarted = CompletableDeferred<Unit>()
        val finishRevocation = CompletableDeferred<Unit>()
        authenticateForTesting()
        whenever(pubkyService.signOut()).doSuspendableAnswer {
            revocationStarted.complete(Unit)
            finishRevocation.await()
        }

        val result = async { sut.signOut() }
        revocationStarted.await()
        result.cancel()
        finishRevocation.complete(Unit)
        result.join()

        assertFalse(sut.isAuthenticated.value)
        assertNull(sut.publicKey.value)
        verifyBlocking(keychain, atLeastOnce()) { delete(Keychain.Key.PAYKIT_SESSION.name) }
    }

    @Test
    fun `clearPendingImport should only clear pending import state`() = test {
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
        whenever(pubkyService.resolveContactProfile(pendingContactKey, true, PaykitReadLane.Interactive))
            .thenReturn(createResolution(pendingContactKey, paykitProfile = createPaykitProfile("Pending Contact")))

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
    fun `prepareImport reuses the loaded profile of the active identity`() = test {
        authenticateForTesting(publicKey = VALID_SELF_KEY, profileName = "Alice")
        whenever(pubkyService.getContacts(VALID_SELF_KEY)).thenReturn(emptyList())
        clearInvocations(pubkyService)

        val result = sut.prepareImport()

        assertTrue(result.isSuccess)
        assertEquals("Alice", sut.pendingImportProfile.value?.name)
        assertEquals(sut.profile.value, sut.pendingImportProfile.value)
        verify(pubkyService, never()).resolveContactProfile(any(), any(), any())
    }

    @Test
    fun `prepareImport resolves each follow once without retrying a missing profile or an error`() = test {
        authenticateForTesting(publicKey = VALID_SELF_KEY)
        whenever(pubkyService.getContacts(VALID_SELF_KEY)).thenReturn(listOf(VALID_CONTACT_KEY_A, VALID_CONTACT_KEY_B))
        whenever(pubkyService.resolveContactProfile(VALID_CONTACT_KEY_A, true, PaykitReadLane.Interactive))
            .thenReturn(null)
        whenever(pubkyService.resolveContactProfile(VALID_CONTACT_KEY_B, true, PaykitReadLane.Interactive))
            .thenAnswer { throw TestAppError("Unreachable") }

        val result = sut.prepareImport()

        assertTrue(result.isSuccess)
        assertEquals(
            setOf(PubkyProfile.placeholder(VALID_CONTACT_KEY_A), PubkyProfile.placeholder(VALID_CONTACT_KEY_B)),
            sut.pendingImportContacts.value.toSet(),
        )
        verify(pubkyService, times(1)).resolveContactProfile(VALID_CONTACT_KEY_A, true, PaykitReadLane.Interactive)
        verify(pubkyService, times(1)).resolveContactProfile(VALID_CONTACT_KEY_B, true, PaykitReadLane.Interactive)
    }

    @Test
    fun `prepareImport looks up the follows and the own profile on the interactive lane`() = test {
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn("test_secret")
        whenever(pubkyService.importSession("test_secret")).thenReturn(VALID_SELF_KEY)
        whenever(pubkyService.contactRecords()).thenReturn(emptyList())
        sut.initialize()
        assertNull(sut.profile.value)
        whenever(pubkyService.getContacts(VALID_SELF_KEY)).thenReturn(listOf(VALID_CONTACT_KEY_A))
        listOf(VALID_SELF_KEY to "Me", VALID_CONTACT_KEY_A to "Alice").forEach { (key, name) ->
            whenever(pubkyService.resolveContactProfile(key, true, PaykitReadLane.Interactive))
                .thenReturn(createResolution(key, paykitProfile = createPaykitProfile(name)))
        }

        assertTrue(sut.prepareImport().isSuccess)

        assertEquals("Me", sut.pendingImportProfile.value?.name)
        assertEquals(listOf("Alice"), sut.pendingImportContacts.value.map { it.name })
        verify(pubkyService, never()).resolveContactProfile(any(), any(), eq(PaykitReadLane.Bulk))
    }

    @Test
    fun `prepareImport discards its results when the identity changes meanwhile`() = test {
        authenticateForTesting(publicKey = VALID_SELF_KEY)
        val followsStarted = CompletableDeferred<Unit>()
        val finishFollows = CompletableDeferred<Unit>()
        whenever(pubkyService.getContacts(VALID_SELF_KEY)).doSuspendableAnswer {
            followsStarted.complete(Unit)
            finishFollows.await()
            listOf(VALID_CONTACT_KEY_A)
        }
        whenever(pubkyService.resolveContactProfile(VALID_CONTACT_KEY_A, true, PaykitReadLane.Interactive))
            .thenReturn(createResolution(VALID_CONTACT_KEY_A, paykitProfile = createPaykitProfile("Bob")))
        val preparation = async { sut.prepareImport() }
        followsStarted.await()

        sut.wipeLocalState()
        finishFollows.complete(Unit)

        assertTrue(preparation.await().isFailure)
        assertNull(sut.pendingImportProfile.value)
        assertTrue(sut.pendingImportContacts.value.isEmpty())
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
    fun `profile cache records the authenticated identity owner`() = test {
        var cached = PubkyStoreData()
        whenever { pubkyStore.update(any()) }.thenAnswer {
            cached = it.getArgument<(PubkyStoreData) -> PubkyStoreData>(0)(cached)
            Unit
        }

        authenticateForTesting(publicKey = VALID_SELF_KEY)

        assertEquals(VALID_SELF_KEY, cached.ownerPublicKey)
        assertEquals(VALID_SELF_KEY, cached.cachedProfileOwner)
    }

    @Test
    fun `contact profile overrides keep the cached profile owner of the previous identity`() = test {
        var cached = PubkyStoreData()
        whenever { pubkyStore.update(any()) }.thenAnswer {
            cached = it.getArgument<(PubkyStoreData) -> PubkyStoreData>(0)(cached)
            Unit
        }
        authenticateForTesting(publicKey = VALID_CONTACT_KEY_A, profileName = "Previous")
        val ringPubky = stubRingCredential()
        whenever(pubkyService.signIn("ring_secret")).thenReturn(Unit)
        whenever(pubkyService.resolveContactProfile(VALID_SELF_KEY, true)).thenReturn(null)
        sut.adoptRingIdentity(ringPubky)

        sut.restoreContactProfileOverrides(emptyMap())

        assertEquals(VALID_SELF_KEY, cached.ownerPublicKey)
        assertEquals(VALID_CONTACT_KEY_A, cached.cachedProfileOwner)
        assertEquals("Previous", cached.cachedName)
    }

    @Test
    fun `cachedProfile exposes the cached profile with its owner`() = test {
        val data = PubkyStoreData(
            cachedProfileOwner = VALID_SELF_KEY,
            cachedName = "Cached",
            cachedImageUri = "pubky://a",
        )
        whenever(pubkyStore.data).thenReturn(flowOf(data))
        sut = createSut()

        sut.cachedProfile.test(timeout = 500.milliseconds) {
            assertEquals(PubkyCachedProfile(VALID_SELF_KEY, "Cached", "pubky://a"), awaitItem())
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
    fun `snapshotSessionBackupState should return null for an adopted ring identity`() = test {
        whenever(keychain.exists(Keychain.Key.SHARED_PUBKY_SOURCE.name)).thenReturn(true)
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn("session_secret")

        val result = sut.snapshotSessionBackupState()

        assertNull(result.getOrNull())
    }

    @Test
    fun `adoptRingIdentity should reject a mismatching credential without changing the source`() = test {
        val ringPubky = VALID_SELF_KEY.removePrefix("pubky")
        whenever(sharedPubkyClient.ringCredential(ringPubky)).thenReturn(Result.success("ring_secret"))
        whenever(pubkyService.publicKeyFromSecret("ring_secret"))
            .thenReturn(VALID_CONTACT_KEY_A.removePrefix("pubky"))

        val result = sut.adoptRingIdentity(ringPubky)

        assertTrue(result.isFailure)
        assertNull(sut.publicKey.value)
        verifyBlocking(pubkyService, never()) { signIn(any()) }
        verifyBlocking(keychain, never()) { upsertString(eq(Keychain.Key.SHARED_PUBKY_SOURCE.name), any()) }
        verifyBlocking(keychain, never()) { delete(Keychain.Key.SHARED_PUBKY_SOURCE.name) }
    }

    @Test
    fun `adoptRingIdentity should not sign up when sign in fails for a published identity`() = test {
        val ringPubky = stubRingCredential()
        whenever(pubkyService.signIn("ring_secret")).thenAnswer { throw TestAppError("Relay unavailable") }
        whenever(pubkyService.hasIdentityRecord(ringPubky)).thenReturn(true)

        val result = sut.adoptRingIdentity(ringPubky)

        assertTrue(result.isFailure)
        assertNull(sut.publicKey.value)
        verifyBlocking(pubkyService, never()) { signUp(any(), any(), any()) }
        verifyBlocking(keychain) { delete(Keychain.Key.SHARED_PUBKY_SOURCE.name) }
    }

    @Test
    fun `adoptRingIdentity should not sign up when the identity record check fails`() = test {
        val ringPubky = stubRingCredential()
        whenever(pubkyService.signIn("ring_secret")).thenAnswer { throw TestAppError("Relay unavailable") }
        whenever(pubkyService.hasIdentityRecord(ringPubky)).thenAnswer { throw TestAppError("No responses") }

        val result = sut.adoptRingIdentity(ringPubky)

        assertTrue(result.isFailure)
        verifyBlocking(pubkyService, never()) { signUp(any(), any(), any()) }
        verifyBlocking(keychain) { delete(Keychain.Key.SHARED_PUBKY_SOURCE.name) }
    }

    @Test
    fun `adoptRingIdentity should sign up a ring identity without a published record`() = test {
        val httpClient = identityHttpClient()
        sut = createSut(httpClient)
        val ringPubky = stubRingCredential()
        whenever(pubkyService.signIn("ring_secret")).thenAnswer { throw TestAppError("No homeserver") }
        whenever(pubkyService.hasIdentityRecord(ringPubky)).thenReturn(false)
        whenever(pubkyService.signUp("ring_secret", "test-homeserver", "test-code")).thenReturn(Unit)

        val result = sut.adoptRingIdentity(ringPubky)
        httpClient.close()

        assertTrue(result.isSuccess)
        assertEquals(VALID_SELF_KEY, sut.publicKey.value)
        verifyBlocking(pubkyService) { signUp("ring_secret", "test-homeserver", "test-code") }
    }

    @Test
    fun `adoptRingIdentity should mark profile setup pending when the pubky has no profile`() = test {
        val ringPubky = stubRingCredential()
        whenever(pubkyService.signIn("ring_secret")).thenReturn(Unit)
        whenever(pubkyService.resolveContactProfile(VALID_SELF_KEY, true)).thenReturn(null)

        val result = sut.adoptRingIdentity(ringPubky)

        assertEquals(false, result.getOrNull())
        assertTrue(profileSetupPending.value)
        assertEquals(VALID_SELF_KEY, sut.publicKey.value)
    }

    @Test
    fun `adoptRingIdentity should clear profile setup pending when the pubky has a profile`() = test {
        profileSetupPending.value = true
        val ringPubky = stubRingCredential()
        whenever(pubkyService.signIn("ring_secret")).thenReturn(Unit)
        whenever(pubkyService.resolveContactProfile(VALID_SELF_KEY, true))
            .thenReturn(createResolution(VALID_SELF_KEY, pubkyProfile = createPubkyProfile()))

        val result = sut.adoptRingIdentity(ringPubky)

        assertEquals(true, result.getOrNull())
        assertFalse(profileSetupPending.value)
    }

    @Test
    fun `adoptRingIdentity clears the previous identity while the new profile is unavailable`() = test {
        authenticateForTesting(publicKey = VALID_CONTACT_KEY_A, profileName = "Previous")
        val ringPubky = stubRingCredential()
        whenever(pubkyService.signIn("ring_secret")).thenReturn(Unit)
        whenever(pubkyService.resolveContactProfile(VALID_SELF_KEY, true)).thenReturn(null)

        val result = sut.adoptRingIdentity(ringPubky)

        assertEquals(false, result.getOrNull())
        assertEquals(VALID_SELF_KEY, sut.publicKey.value)
        assertNull(sut.profile.value)
        assertTrue(sut.contacts.value.isEmpty())
    }

    @Test
    fun `adoptRingIdentity seeds the handed off profile without resolving it again`() = test {
        var cached = PubkyStoreData()
        whenever(pubkyStore.update(any())).thenAnswer {
            cached = it.getArgument<(PubkyStoreData) -> PubkyStoreData>(0)(cached)
            Unit
        }
        profileSetupPending.value = true
        val ringPubky = stubRingCredential()
        whenever(pubkyService.signIn("ring_secret")).thenReturn(Unit)
        val knownProfile = PubkyProfile.forDisplay(ringPubky, name = "Ring Profile", imageUrl = "pubky://avatar")

        val result = sut.adoptRingIdentity(ringPubky) { knownProfile }

        assertEquals(true, result.getOrNull())
        assertEquals(knownProfile.copy(publicKey = VALID_SELF_KEY), sut.profile.value)
        assertFalse(profileSetupPending.value)
        assertEquals(VALID_SELF_KEY, cached.ownerPublicKey)
        assertEquals("Ring Profile", cached.cachedName)
        verify(pubkyService, never()).resolveContactProfile(any(), any(), any())
    }

    @Test
    fun `adoptRingIdentity reads the handed off profile once sign-in completes`() = test {
        val ringPubky = stubRingCredential()
        val knownProfile = PubkyProfile.forDisplay(ringPubky, name = "Ring Profile", imageUrl = null)
        var rowProfile: PubkyProfile? = null
        whenever(pubkyService.signIn("ring_secret")).doSuspendableAnswer { rowProfile = knownProfile }

        val result = sut.adoptRingIdentity(ringPubky) { rowProfile }

        assertEquals(true, result.getOrNull())
        assertEquals("Ring Profile", sut.profile.value?.name)
        verify(pubkyService, never()).resolveContactProfile(any(), any(), any())
    }

    @Test
    fun `adoptRingIdentity resolves the profile when none is handed off`() = test {
        val ringPubky = stubRingCredential()
        whenever(pubkyService.signIn("ring_secret")).thenReturn(Unit)
        whenever(pubkyService.resolveContactProfile(VALID_SELF_KEY, true))
            .thenReturn(createResolution(VALID_SELF_KEY, pubkyProfile = createPubkyProfile(name = "Remote")))

        val result = sut.adoptRingIdentity(ringPubky, knownProfile = { null })

        assertEquals(true, result.getOrNull())
        assertEquals("Remote", sut.profile.value?.name)
        verify(pubkyService).resolveContactProfile(VALID_SELF_KEY, true)
    }

    @Test
    fun `adoptRingIdentity ignores a handed off profile for another key`() = test {
        val ringPubky = stubRingCredential()
        whenever(pubkyService.signIn("ring_secret")).thenReturn(Unit)
        whenever(pubkyService.resolveContactProfile(VALID_SELF_KEY, true)).thenReturn(null)
        val otherProfile = PubkyProfile.forDisplay(VALID_CONTACT_KEY_A, name = "Other", imageUrl = null)

        val result = sut.adoptRingIdentity(ringPubky) { otherProfile }

        assertEquals(false, result.getOrNull())
        assertNull(sut.profile.value)
        assertTrue(profileSetupPending.value)
        verify(pubkyService, atLeastOnce()).resolveContactProfile(VALID_SELF_KEY, true)
    }

    @Test
    fun `adoptRingIdentity revokes a session installed by a failed attempt`() = test {
        val ringPubky = VALID_SELF_KEY.removePrefix("pubky")
        var session: String? = null
        var source: String? = null
        whenever(sharedPubkyClient.ringCredential(ringPubky)).thenReturn(Result.success("ring_secret"))
        whenever(pubkyService.publicKeyFromSecret("ring_secret")).thenReturn(ringPubky)
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenAnswer { session }
        whenever(keychain.loadString(Keychain.Key.SHARED_PUBKY_SOURCE.name)).thenAnswer { source }
        whenever(keychain.upsertString(eq(Keychain.Key.SHARED_PUBKY_SOURCE.name), any())).thenAnswer {
            source = it.getArgument(1)
            Unit
        }
        whenever(keychain.delete(Keychain.Key.SHARED_PUBKY_SOURCE.name)).thenAnswer {
            source = null
            Unit
        }
        whenever(pubkyService.signIn("ring_secret")).thenAnswer {
            session = "installed_session"
            throw TestAppError("Cache reset failed")
        }
        whenever(pubkyService.hasIdentityRecord(ringPubky)).thenReturn(true)
        whenever(pubkyService.signOut()).thenAnswer {
            session = null
            Unit
        }

        val result = sut.adoptRingIdentity(ringPubky)

        assertTrue(result.isFailure)
        assertNull(session)
        assertNull(source)
        assertNull(sut.publicKey.value)
        verifyBlocking(pubkyService) { signOut() }
        verifyBlocking(pubkyService, never()) { signUp(any(), any(), any()) }
    }

    @Test
    fun `adoptRingIdentity should not sign up when sign in saved a session before failing`() = test {
        val httpClient = identityHttpClient()
        sut = createSut(httpClient)
        val ringPubky = stubRingCredential()
        val reference = SharedPubkyContract.RING_SOURCE_PREFIX + ringPubky
        var session: String? = null
        var sourceAtSignOut: String? = null
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenAnswer { session }
        whenever(pubkyService.signIn("ring_secret")).thenAnswer {
            session = "installed_session"
            throw TestAppError("Activation failed")
        }
        whenever(pubkyService.hasIdentityRecord(ringPubky)).thenReturn(false)
        whenever(pubkyService.signUp("ring_secret", "test-homeserver", "test-code")).thenReturn(Unit)
        whenever(pubkyService.signOut()).thenAnswer {
            sourceAtSignOut = adoptedSource
            session = null
            Unit
        }

        val result = sut.adoptRingIdentity(ringPubky)
        httpClient.close()

        assertTrue(result.isFailure)
        assertNull(session)
        assertNull(adoptedSource)
        assertEquals(reference, sourceAtSignOut)
        assertNull(sut.publicKey.value)
        verifyBlocking(pubkyService) { signOut() }
        verifyBlocking(pubkyService, never()) { signUp(any(), any(), any()) }
    }

    @Test
    fun `wipe completes while adopted identity profile loading remains in flight`() = test {
        sut.awaitInitialization()
        val ringPubky = stubRingCredential()
        whenever(pubkyService.signIn("ring_secret")).thenReturn(Unit)
        val profileLoadStarted = CompletableDeferred<Unit>()
        val finishProfileLoad = CompletableDeferred<Unit>()
        whenever(pubkyService.resolveContactProfile(VALID_SELF_KEY, true)).doSuspendableAnswer {
            profileLoadStarted.complete(Unit)
            finishProfileLoad.await()
            createResolution(VALID_SELF_KEY, pubkyProfile = createPubkyProfile())
        }
        clearInvocations(pubkyStore)
        val adoption = async { sut.adoptRingIdentity(ringPubky) }
        profileLoadStarted.await()

        try {
            val wipe = async { sut.wipeLocalState() }
            wipe.await()

            assertFalse(adoption.isCompleted)
            assertNull(sut.publicKey.value)
        } finally {
            finishProfileLoad.complete(Unit)
        }

        assertTrue(adoption.await().isFailure)
        assertNull(sut.profile.value)
        assertTrue(sut.contacts.value.isEmpty())
        verify(pubkyStore).reset()
    }

    @Test
    fun `canceling an adopted identity after sign in signs out and clears the ring reference`() = test {
        sut.awaitInitialization()
        val ringPubky = stubRingCredential()
        val reference = SharedPubkyContract.RING_SOURCE_PREFIX + ringPubky
        var session: String? = null
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenAnswer { session }
        whenever(pubkyService.signIn("ring_secret")).thenAnswer {
            session = "installed_session"
            Unit
        }
        whenever(pubkyService.signOut()).thenAnswer {
            session = null
            adoptedSource = null
            Unit
        }
        val profileLoadStarted = CompletableDeferred<Unit>()
        whenever(pubkyService.resolveContactProfile(VALID_SELF_KEY, true)).doSuspendableAnswer {
            profileLoadStarted.complete(Unit)
            awaitCancellation()
        }
        val adoption = async { sut.adoptRingIdentity(ringPubky) }
        profileLoadStarted.await()
        assertEquals(reference, adoptedSource)
        assertEquals(VALID_SELF_KEY, sut.publicKey.value)

        adoption.cancelAndJoin()

        assertNull(session)
        assertNull(adoptedSource)
        assertNull(sut.publicKey.value)
        assertNull(sut.profile.value)
        assertFalse(profileSetupPending.value)
        verifyBlocking(pubkyService) { signOut() }
    }

    @Test
    fun `canceling an adopted identity clears the session even when the revocation fails`() = test {
        sut.awaitInitialization()
        val ringPubky = stubRingCredential()
        var session: String? = null
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenAnswer { session }
        whenever(keychain.delete(Keychain.Key.PAYKIT_SESSION.name)).thenAnswer {
            session = null
            Unit
        }
        whenever(pubkyService.signIn("ring_secret")).thenAnswer {
            session = "installed_session"
            Unit
        }
        whenever(pubkyService.signOut()).thenAnswer { throw TestAppError("Offline") }
        whenever(pubkyService.forgetSessionAccess()).thenAnswer {
            session = null
            adoptedSource = null
            Unit
        }
        val profileLoadStarted = CompletableDeferred<Unit>()
        whenever(pubkyService.resolveContactProfile(VALID_SELF_KEY, true)).doSuspendableAnswer {
            profileLoadStarted.complete(Unit)
            awaitCancellation()
        }
        val adoption = async { sut.adoptRingIdentity(ringPubky) }
        profileLoadStarted.await()

        adoption.cancelAndJoin()

        assertNull(session)
        assertNull(adoptedSource)
        assertNull(sut.publicKey.value)
        verifyBlocking(pubkyService) { forgetSessionAccess() }
    }

    @Test
    fun `canceling an older pick keeps a newer pick of the same pubky`() = test {
        sut.awaitInitialization()
        val ringPubky = stubRingCredential()
        val reference = SharedPubkyContract.RING_SOURCE_PREFIX + ringPubky
        var session: String? = null
        var signIns = 0
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenAnswer { session }
        whenever(pubkyService.signIn("ring_secret")).thenAnswer {
            session = "session_${++signIns}"
            Unit
        }
        whenever(pubkyService.signOut()).thenAnswer {
            session = null
            adoptedSource = null
            Unit
        }
        val firstProfileLoadStarted = CompletableDeferred<Unit>()
        var profileLoads = 0
        whenever(pubkyService.resolveContactProfile(VALID_SELF_KEY, true)).doSuspendableAnswer {
            if (++profileLoads == 1) {
                firstProfileLoadStarted.complete(Unit)
                awaitCancellation()
            }
            createResolution(VALID_SELF_KEY, pubkyProfile = createPubkyProfile())
        }
        val firstPick = async { sut.adoptRingIdentity(ringPubky) }
        firstProfileLoadStarted.await()
        val secondPick = async { sut.adoptRingIdentity(ringPubky) }
        assertEquals(1, signIns)

        firstPick.cancelAndJoin()

        assertTrue(secondPick.await().isSuccess)
        assertEquals(2, signIns)
        assertEquals("session_2", session)
        assertEquals(reference, adoptedSource)
        assertEquals(VALID_SELF_KEY, sut.publicKey.value)
        verifyBlocking(pubkyService) { signOut() }
    }

    @Test
    fun `canceling a pick after a failed pick of another pubky signs out the installed session`() = test {
        sut.awaitInitialization()
        val ringPubky = stubRingCredential()
        val otherPubky = VALID_CONTACT_KEY_A.removePrefix("pubky")
        whenever(sharedPubkyClient.ringCredential(otherPubky)).thenReturn(Result.failure(TestAppError("Denied")))
        var session: String? = null
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenAnswer { session }
        whenever(pubkyService.signIn("ring_secret")).thenAnswer {
            session = "installed_session"
            Unit
        }
        whenever(pubkyService.signOut()).thenAnswer {
            session = null
            adoptedSource = null
            Unit
        }
        val profileLoadStarted = CompletableDeferred<Unit>()
        whenever(pubkyService.resolveContactProfile(VALID_SELF_KEY, true)).doSuspendableAnswer {
            profileLoadStarted.complete(Unit)
            awaitCancellation()
        }
        val firstPick = async { sut.adoptRingIdentity(ringPubky) }
        profileLoadStarted.await()
        val secondPick = async { sut.adoptRingIdentity(otherPubky) }

        firstPick.cancelAndJoin()

        assertTrue(secondPick.await().isFailure)
        assertNull(session)
        assertNull(adoptedSource)
        assertNull(sut.publicKey.value)
        verifyBlocking(pubkyService) { signOut() }
    }

    @Test
    fun `canceling a pick while another pubky fails to sign in leaves no session or ring reference`() = test {
        sut.awaitInitialization()
        val ringPubky = stubRingCredential()
        val otherPubky = VALID_CONTACT_KEY_A.removePrefix("pubky")
        whenever(sharedPubkyClient.ringCredential(otherPubky)).thenReturn(Result.success("other_secret"))
        whenever(pubkyService.publicKeyFromSecret("other_secret")).thenReturn(otherPubky)
        whenever(pubkyService.signIn("other_secret")).thenAnswer { throw TestAppError("Relay unavailable") }
        whenever(pubkyService.hasIdentityRecord(otherPubky)).thenReturn(true)
        var session: String? = null
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenAnswer { session }
        whenever(pubkyService.signIn("ring_secret")).thenAnswer {
            session = "installed_session"
            Unit
        }
        whenever(pubkyService.signOut()).thenAnswer {
            session = null
            Unit
        }
        val profileLoadStarted = CompletableDeferred<Unit>()
        whenever(pubkyService.resolveContactProfile(VALID_SELF_KEY, true)).doSuspendableAnswer {
            profileLoadStarted.complete(Unit)
            awaitCancellation()
        }
        val firstPick = async { sut.adoptRingIdentity(ringPubky) }
        profileLoadStarted.await()
        val secondPick = async { sut.adoptRingIdentity(otherPubky) }

        firstPick.cancelAndJoin()

        assertTrue(secondPick.await().isFailure)
        assertNull(session)
        assertNull(adoptedSource)
        assertNull(sut.publicKey.value)
        verifyBlocking(pubkyService) { signOut() }
    }

    @Test
    fun `adopted identity loads after previous identity loads finish`() = test {
        val oldPublicKey = VALID_SELF_KEY
        val newPublicKey = VALID_CONTACT_KEY_A
        authenticateForTesting(publicKey = oldPublicKey)
        val profileLoadStarted = CompletableDeferred<Unit>()
        val contactsLoadStarted = CompletableDeferred<Unit>()
        val finishOldProfileLoad = CompletableDeferred<Unit>()
        val finishOldContactsLoad = CompletableDeferred<Unit>()
        whenever(pubkyService.resolveContactProfile(oldPublicKey, true)).doSuspendableAnswer {
            profileLoadStarted.complete(Unit)
            finishOldProfileLoad.await()
            createResolution(oldPublicKey, pubkyProfile = createPubkyProfile(name = "Old Profile"))
        }
        whenever(pubkyService.contactRecords()).doSuspendableAnswer {
            if (sut.publicKey.value == oldPublicKey) {
                contactsLoadStarted.complete(Unit)
                finishOldContactsLoad.await()
                emptyList()
            } else {
                listOf(createContactRecord(VALID_CONTACT_KEY_B, profile = createPaykitProfile("New Contact")))
            }
        }
        val oldProfileLoad = async { sut.loadProfile() }
        val oldContactsLoad = async { sut.loadContacts() }
        profileLoadStarted.await()
        contactsLoadStarted.await()

        sut.wipeLocalState()
        val ringPubky = newPublicKey.removePrefix("pubky")
        whenever(sharedPubkyClient.ringCredential(ringPubky)).thenReturn(Result.success("new_ring_secret"))
        whenever(pubkyService.publicKeyFromSecret("new_ring_secret")).thenReturn(ringPubky)
        whenever(pubkyService.signIn("new_ring_secret")).thenReturn(Unit)
        whenever(pubkyService.resolveContactProfile(newPublicKey, true))
            .thenReturn(createResolution(newPublicKey, pubkyProfile = createPubkyProfile(name = "New Profile")))
        val adoption = async { sut.adoptRingIdentity(ringPubky) }

        assertFalse(adoption.isCompleted)
        finishOldProfileLoad.complete(Unit)
        finishOldContactsLoad.complete(Unit)
        oldProfileLoad.await()
        oldContactsLoad.await()
        assertTrue(adoption.await().isSuccess)

        assertEquals(newPublicKey, sut.publicKey.value)
        assertEquals("New Profile", sut.profile.value?.name)
        assertEquals(listOf("New Contact"), sut.contacts.value.map { it.name })
    }

    @Test
    fun `initialize should clear an adopted identity that is gone from pubky ring`() = test {
        stubAdoptedRingSource()
        whenever(sharedPubkyClient.listRingIdentities())
            .thenReturn(Result.success(persistentListOf(VALID_CONTACT_KEY_A.removePrefix("pubky"))))

        sut.initialize()

        assertTrue(sut.adoptedSourceLost.value)
        verifyBlocking(pubkyService) { clearSessionAccess() }
    }

    @Test
    fun `initialize should keep an adopted identity when the ring listing fails`() = test {
        stubAdoptedRingSource()
        whenever(sharedPubkyClient.listRingIdentities()).thenReturn(Result.failure(TestAppError("Unavailable")))

        sut.initialize()

        assertFalse(sut.adoptedSourceLost.value)
        verifyBlocking(pubkyService, never()) { clearSessionAccess() }
    }

    @Test
    fun `checkAdoptedSource should clear an adopted identity removed from pubky ring after startup`() = test {
        stubAdoptedRingSource()
        whenever(sharedPubkyClient.listRingIdentities())
            .thenReturn(Result.success(persistentListOf(VALID_CONTACT_KEY_A.removePrefix("pubky"))))

        val result = sut.checkAdoptedSource()

        assertTrue(result.isSuccess)
        assertTrue(sut.adoptedSourceLost.value)
        verifyBlocking(pubkyService) { clearSessionAccess() }
    }

    @Test
    fun `stale adopted source check does not clear a newly adopted identity`() = test {
        sut.awaitInitialization()
        val oldReference = SharedPubkyContract.RING_SOURCE_PREFIX + VALID_CONTACT_KEY_B.removePrefix("pubky")
        var source: String? = oldReference
        whenever(keychain.loadString(Keychain.Key.SHARED_PUBKY_SOURCE.name)).thenAnswer { source }
        whenever(keychain.upsertString(eq(Keychain.Key.SHARED_PUBKY_SOURCE.name), any())).thenAnswer {
            source = it.getArgument(1)
            Unit
        }
        whenever(keychain.delete(Keychain.Key.SHARED_PUBKY_SOURCE.name)).thenAnswer {
            source = null
            Unit
        }
        val listingStarted = CompletableDeferred<Unit>()
        val finishListing = CompletableDeferred<Unit>()
        whenever(sharedPubkyClient.listRingIdentities()).doSuspendableAnswer {
            listingStarted.complete(Unit)
            finishListing.await()
            Result.success(persistentListOf())
        }
        val sourceCheck = async { sut.checkAdoptedSource() }
        listingStarted.await()

        val ringPubky = stubRingCredential()
        whenever(pubkyService.signIn("ring_secret")).thenReturn(Unit)
        whenever(pubkyService.resolveContactProfile(VALID_SELF_KEY, true))
            .thenReturn(createResolution(VALID_SELF_KEY, pubkyProfile = createPubkyProfile()))
        val adoption = sut.adoptRingIdentity(ringPubky)
        finishListing.complete(Unit)
        sourceCheck.await()

        assertTrue(adoption.isSuccess)
        assertEquals(VALID_SELF_KEY, sut.publicKey.value)
        assertFalse(sut.adoptedSourceLost.value)
        verifyBlocking(pubkyService, never()) { clearSessionAccess() }
    }

    @Test
    fun `checkAdoptedSource should skip while initialization is running`() = test {
        val imported = CompletableDeferred<String>()
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn("saved_session")
        whenever(pubkyService.importSession("saved_session")).doSuspendableAnswer { imported.await() }
        stubAdoptedRingSource()
        whenever(sharedPubkyClient.listRingIdentities()).thenReturn(Result.success(persistentListOf()))
        val repo = createSut()

        repo.checkAdoptedSource()

        assertFalse(repo.adoptedSourceLost.value)
        verifyBlocking(pubkyService, never()) { clearSessionAccess() }
    }

    @Test
    fun `snapshotSessionBackupState should return null when no pubky credentials exist`() = test {
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(null)
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn(null)

        val result = sut.snapshotSessionBackupState()

        assertNull(result.getOrNull())
    }

    @Test
    fun `awaitInitialization shares startup and preserves it when a waiter is cancelled`() = test {
        val imported = CompletableDeferred<String>()
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn("saved_session")
        whenever(pubkyService.importSession("saved_session")).doSuspendableAnswer { imported.await() }
        val repo = createSut()
        val cancelledWaiter = async { repo.awaitInitialization() }
        val waiter = async { repo.awaitInitialization() }

        assertFalse(waiter.isCompleted)
        assertNull(repo.publicKey.value)
        cancelledWaiter.cancelAndJoin()
        imported.complete(VALID_SELF_KEY)
        waiter.await()

        assertEquals(VALID_SELF_KEY, repo.publicKey.value)
        verify(pubkyService).importSession("saved_session")
    }

    @Test
    fun `awaitInitialization completes before startup profile loading`() = test {
        val profileLoad = CompletableDeferred<Unit>()
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn("saved_session")
        whenever(pubkyService.importSession("saved_session")).thenReturn(VALID_SELF_KEY)
        whenever(pubkyService.resolveContactProfile(VALID_SELF_KEY, true)).doSuspendableAnswer {
            profileLoad.await()
            createResolution(VALID_SELF_KEY, pubkyProfile = createPubkyProfile())
        }
        val repo = createSut()
        val waiter = async { repo.awaitInitialization() }

        try {
            assertTrue(waiter.isCompleted)
            assertEquals(VALID_SELF_KEY, repo.publicKey.value)
        } finally {
            profileLoad.complete(Unit)
        }
        waiter.await()
    }

    @Test
    fun `awaitInitialization completes without identity after startup failure`() = test {
        whenever(pubkyService.initialize()).thenAnswer { throw TestAppError("Startup failed") }
        val repo = createSut()

        repo.awaitInitialization()

        assertNull(repo.publicKey.value)
        assertFalse(repo.sessionRestorationFailed.value)
        verify(pubkyService, never()).importSession(any())
    }

    @Test
    fun `initialize should flag session restoration failure when service startup fails with identity error`() = test {
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn("saved_session")
        whenever(pubkyService.initialize()).thenAnswer {
            throw AppError(PaykitException.Identity("identity_error", "Missing capabilities"))
        }
        val repo = createSut()

        repo.awaitInitialization()

        assertTrue(repo.sessionRestorationFailed.value)
        assertFalse(repo.isAuthenticated.value)
        verify(pubkyService, never()).importSession(any())
        verifyBlocking(keychain, never()) { delete(Keychain.Key.PAYKIT_SESSION.name) }
        verifyBlocking(keychain, never()) { delete(Keychain.Key.PUBKY_SECRET_KEY.name) }
    }

    @Test
    fun `initialize should not flag session restoration failure when service startup fails with non-identity error`() =
        test {
            whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn("saved_session")
            whenever(pubkyService.initialize()).thenAnswer {
                throw AppError(PaykitException.Storage("storage_error", "Corrupted state"))
            }
            val repo = createSut()

            repo.awaitInitialization()

            assertFalse(repo.sessionRestorationFailed.value)
            verify(pubkyService, never()).importSession(any())
        }

    @Test
    fun `initialize should not flag session restoration failure on identity error without saved session`() = test {
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn(null)
        whenever(pubkyService.initialize()).thenAnswer {
            throw AppError(PaykitException.Identity("identity_error", "Missing capabilities"))
        }
        val repo = createSut()

        repo.awaitInitialization()

        assertFalse(repo.sessionRestorationFailed.value)
        assertFalse(repo.isAuthenticated.value)
        verify(pubkyService, never()).importSession(any())
    }

    @Test
    fun `initialize should restore saved session with prefixed public key`() = test {
        val session = "saved_session"
        val unprefixedPublicKey = VALID_SELF_KEY.removePrefix("pubky")
        val pubkyProfile = createPubkyProfile(name = "Restored User")
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn(session)
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(null)
        whenever(pubkyService.importSession(session)).thenReturn(unprefixedPublicKey)
        whenever(pubkyService.resolveContactProfile(VALID_SELF_KEY, true))
            .thenReturn(createResolution(VALID_SELF_KEY, pubkyProfile = pubkyProfile))

        sut.initialize()

        assertEquals(VALID_SELF_KEY, sut.publicKey.value)
        assertTrue(sut.isAuthenticated.value)
    }

    @Test
    fun `initialize should complete contacts load after contact fetch failure`() = test {
        val session = "saved_session"
        val unprefixedPublicKey = VALID_SELF_KEY.removePrefix("pubky")
        val pubkyProfile = createPubkyProfile(name = "Restored User")
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn(session)
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(null)
        whenever(pubkyService.importSession(session)).thenReturn(unprefixedPublicKey)
        whenever(pubkyService.resolveContactProfile(VALID_SELF_KEY, true))
            .thenReturn(createResolution(VALID_SELF_KEY, pubkyProfile = pubkyProfile))
        whenever(pubkyService.contactRecords()).thenAnswer { throw TestAppError("Offline") }

        sut.initialize()

        assertEquals(1L, sut.contactsLoadCompletionVersion.value)
        assertEquals(0L, sut.contactsLoadVersion.value)
        assertTrue(sut.contacts.value.isEmpty())
    }

    @Test
    fun `initialize should restore session from local secret key when saved session is missing`() = test {
        val secretKey = "local_secret"
        val publicKey = VALID_SELF_KEY.removePrefix("pubky")
        val pubkyProfile = createPubkyProfile(name = "Recovered User")
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn(null)
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(secretKey)
        whenever(pubkyService.signIn(secretKey)).thenReturn(Unit)
        whenever(pubkyService.publicKeyFromSecret(secretKey)).thenReturn(publicKey)
        whenever(pubkyService.resolveContactProfile(VALID_SELF_KEY, true))
            .thenReturn(createResolution(VALID_SELF_KEY, pubkyProfile = pubkyProfile))

        sut.initialize()

        assertEquals(VALID_SELF_KEY, sut.publicKey.value)
        assertTrue(sut.isAuthenticated.value)
    }

    @Test
    fun `initialize should keep saved session when re-sign-in is unavailable`() = test {
        val session = "stale_session"
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn(session)
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(null)
        whenever(pubkyService.importSession(session)).thenAnswer { throw TestAppError("Expired") }

        sut.initialize()

        assertTrue(sut.sessionRestorationFailed.value)
        assertFalse(sut.isAuthenticated.value)
        verifyBlocking(keychain, never()) { delete(Keychain.Key.PAYKIT_SESSION.name) }
    }

    @Test
    fun `initialize should re-sign in an adopted identity with the ring credential`() = test {
        val session = "stale_session"
        stubAdoptedRingSource()
        val ringPubky = stubRingCredential()
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn(session)
        whenever(pubkyService.importSession(session)).thenAnswer { throw TestAppError("Expired") }
        whenever(sharedPubkyClient.listRingIdentities()).thenReturn(Result.success(persistentListOf(ringPubky)))
        whenever(pubkyService.resolveContactProfile(VALID_SELF_KEY, true))
            .thenReturn(createResolution(VALID_SELF_KEY, pubkyProfile = createPubkyProfile(name = "Ring User")))

        sut.initialize()

        assertEquals(VALID_SELF_KEY, sut.publicKey.value)
        assertFalse(sut.sessionRestorationFailed.value)
        verifyBlocking(pubkyService) { signIn("ring_secret") }
        verifyBlocking(pubkyService, never()) { signUp(any(), any(), any()) }
    }

    @Test
    fun `initialize should keep an adopted session when the ring credential is unavailable`() = test {
        val session = "stale_session"
        stubAdoptedRingSource()
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn(session)
        whenever(pubkyService.importSession(session)).thenAnswer { throw TestAppError("Expired") }
        whenever(sharedPubkyClient.ringCredential(VALID_SELF_KEY.removePrefix("pubky")))
            .thenReturn(Result.failure(TestAppError("Unavailable")))
        whenever(sharedPubkyClient.listRingIdentities()).thenReturn(Result.failure(TestAppError("Unavailable")))

        sut.initialize()

        assertTrue(sut.sessionRestorationFailed.value)
        verifyBlocking(pubkyService, never()) { signIn(any()) }
        verifyBlocking(keychain, never()) { delete(Keychain.Key.PAYKIT_SESSION.name) }
        verifyBlocking(keychain, never()) { delete(Keychain.Key.SHARED_PUBKY_SOURCE.name) }
    }

    @Test
    fun `failed restoration preserves profile data and credentials for retry`() = test {
        val session = "saved_session"
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn(session)
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn("local_secret")
        var canRestore = false
        whenever(pubkyService.importSession(session)).thenAnswer {
            if (canRestore) VALID_SELF_KEY else throw TestAppError("Clock skew")
        }
        whenever(pubkyService.signIn("local_secret")).thenAnswer { throw TestAppError("Clock skew") }
        clearInvocations(pubkyStore, keychain)

        sut.initialize()

        assertTrue(sut.sessionRestorationFailed.value)
        assertFalse(sut.isAuthenticated.value)
        verify(pubkyStore, never()).reset()
        verifyBlocking(keychain, never()) { delete(any()) }

        sut.restoreSessionIfNeeded()
        assertTrue(sut.sessionRestorationFailed.value)

        canRestore = true
        sut.restoreSessionIfNeeded()

        assertEquals(VALID_SELF_KEY, sut.publicKey.value)
        assertTrue(sut.isAuthenticated.value)
        assertFalse(sut.sessionRestorationFailed.value)
    }

    @Test
    fun `restoration retry recovers service startup failure and skips an active session`() = test {
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn("saved_session")
        var isOnline = false
        whenever(pubkyService.initialize()).thenAnswer {
            if (!isOnline) throw TestAppError("Offline")
            Unit
        }
        whenever(pubkyService.importSession("saved_session")).thenReturn(VALID_SELF_KEY)
        val repo = createSut()
        repo.awaitInitialization()
        assertNull(repo.publicKey.value)

        isOnline = true
        repo.restoreSessionIfNeeded()
        assertEquals(VALID_SELF_KEY, repo.publicKey.value)
        clearInvocations(pubkyService)

        repo.restoreSessionIfNeeded()
        verify(pubkyService, never()).importSession(any())
        verify(pubkyService, never()).signIn(any())
    }

    @Test
    fun `unreadable credentials preserve cached profile and remain retryable`() = test {
        var readable = false
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenAnswer {
            if (!readable) throw TestAppError("Keychain unavailable")
            "saved_session"
        }
        clearInvocations(pubkyStore)

        sut.initialize()

        assertTrue(sut.sessionRestorationFailed.value)
        verify(pubkyStore, never()).reset()
        readable = true
        whenever(pubkyService.importSession("saved_session")).thenReturn(VALID_SELF_KEY)

        sut.restoreSessionIfNeeded()

        assertEquals(VALID_SELF_KEY, sut.publicKey.value)
    }

    @Test
    fun `restoration retry publishes a readable empty identity check`() = test {
        var readable = false
        whenever(keychain.loadString(any())).thenAnswer {
            if (!readable) throw TestAppError("Keychain unavailable")
            null
        }
        sut.initialize()
        val previousVersion = sut.identityRefreshVersion.value

        readable = true
        sut.restoreSessionIfNeeded()

        assertEquals(previousVersion + 1, sut.identityRefreshVersion.value)
        assertFalse(sut.hasIdentity())
    }

    @Test
    fun `awaitIdentityReady waits for an in-flight restore retry and restores once`() = test {
        val restore = stubSavedSessionRestore()
        sut.initialize()
        assertNull(sut.publicKey.value)
        clearInvocations(pubkyService)
        val retryStarted = CompletableDeferred<Unit>()
        val finishRetry = CompletableDeferred<Unit>()
        restore.answer = {
            retryStarted.complete(Unit)
            finishRetry.await()
            VALID_SELF_KEY
        }
        val retry = async { sut.restoreSessionIfNeeded() }
        retryStarted.await()

        val readiness = async { sut.awaitIdentityReady() }
        assertFalse(readiness.isCompleted)
        finishRetry.complete(Unit)

        assertEquals(PubkyIdentityReadiness.Ready, readiness.await())
        retry.await()
        verify(pubkyService, times(1)).importSession("saved_session")
    }

    @Test
    fun `awaitIdentityReady retries a failed startup restore of a saved identity`() = test {
        val restore = stubSavedSessionRestore()
        sut.initialize()
        assertNull(sut.publicKey.value)
        restore.answer = { VALID_SELF_KEY }

        assertEquals(PubkyIdentityReadiness.Ready, sut.awaitIdentityReady())

        assertEquals(VALID_SELF_KEY, sut.publicKey.value)
    }

    @Test
    fun `awaitIdentityReady reports a missing identity without a network call`() = test {
        sut.awaitInitialization()
        clearInvocations(pubkyService)

        assertEquals(PubkyIdentityReadiness.Missing, sut.awaitIdentityReady())

        verify(pubkyService, never()).initialize()
        verify(pubkyService, never()).importSession(any())
        verify(pubkyService, never()).signIn(any())
    }

    @Test
    fun `awaitIdentityReady reports a saved identity it cannot restore without clearing it`() = test {
        stubSavedSessionRestore()
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn("local_secret")
        whenever(pubkyService.signIn("local_secret")).thenAnswer { throw TestAppError("Clock skew") }
        sut.initialize()
        sut.clearSessionRestorationFailed()
        clearInvocations(pubkyStore, keychain)

        assertEquals(PubkyIdentityReadiness.Unavailable, sut.awaitIdentityReady())

        assertNull(sut.publicKey.value)
        assertFalse(sut.sessionRestorationFailed.value)
        verify(pubkyStore, never()).reset()
        verifyBlocking(keychain, never()) { delete(any()) }
    }

    @Test
    fun `awaitIdentityReady reports an unreadable identity check as unavailable`() = test {
        sut.awaitInitialization()
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenAnswer {
            throw TestAppError("Keychain unavailable")
        }

        assertEquals(PubkyIdentityReadiness.Unavailable, sut.awaitIdentityReady())
    }

    @Test
    fun `awaitIdentityReady waits for an in-flight adoption`() = test {
        sut.awaitInitialization()
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn("saved_session")
        val ringPubky = stubRingCredential()
        val signInStarted = CompletableDeferred<Unit>()
        val finishSignIn = CompletableDeferred<Unit>()
        whenever(pubkyService.signIn("ring_secret")).doSuspendableAnswer {
            signInStarted.complete(Unit)
            finishSignIn.await()
        }
        whenever(pubkyService.resolveContactProfile(VALID_SELF_KEY, true))
            .thenReturn(createResolution(VALID_SELF_KEY, pubkyProfile = createPubkyProfile()))

        val adoption = async { sut.adoptRingIdentity(ringPubky) }
        signInStarted.await()
        val readiness = async { sut.awaitIdentityReady() }
        assertFalse(readiness.isCompleted)
        finishSignIn.complete(Unit)

        assertTrue(adoption.await().isSuccess)
        assertEquals(PubkyIdentityReadiness.Ready, readiness.await())
        verify(pubkyService, never()).importSession(any())
    }

    @Test
    fun `cancelling an identity wait does not cancel its restore`() = test {
        val restore = stubSavedSessionRestore()
        sut.initialize()
        val retryStarted = CompletableDeferred<Unit>()
        val finishRetry = CompletableDeferred<Unit>()
        restore.answer = {
            retryStarted.complete(Unit)
            finishRetry.await()
            VALID_SELF_KEY
        }
        val caller = launch { sut.awaitIdentityReady() }
        retryStarted.await()

        caller.cancelAndJoin()
        finishRetry.complete(Unit)

        assertEquals(VALID_SELF_KEY, sut.publicKey.value)
    }

    @Test
    fun `awaitIdentityReady returns without waiting for contacts`() = test {
        val restore = stubSavedSessionRestore()
        sut.initialize()
        restore.answer = { VALID_SELF_KEY }
        whenever(pubkyService.resolveContactProfile(VALID_SELF_KEY, true))
            .thenReturn(createResolution(VALID_SELF_KEY, pubkyProfile = createPubkyProfile()))
        val contactsRequested = CompletableDeferred<Unit>()
        val finishContacts = CompletableDeferred<List<ContactRecord>>()
        whenever(pubkyService.contactRecords()).doSuspendableAnswer {
            contactsRequested.complete(Unit)
            finishContacts.await()
        }

        assertEquals(PubkyIdentityReadiness.Ready, sut.awaitIdentityReady())

        contactsRequested.await()
        assertTrue(sut.isLoadingContacts.value)
        finishContacts.complete(emptyList())
        assertFalse(sut.isLoadingContacts.value)
    }

    @Test
    fun `adoption excludes a queued restoration retry`() = test {
        sut.awaitInitialization()
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn("saved_session")
        val ringPubky = stubRingCredential()
        val signInStarted = CompletableDeferred<Unit>()
        val finishSignIn = CompletableDeferred<Unit>()
        whenever(pubkyService.signIn("ring_secret")).doSuspendableAnswer {
            signInStarted.complete(Unit)
            finishSignIn.await()
        }
        whenever(pubkyService.resolveContactProfile(VALID_SELF_KEY, true))
            .thenReturn(createResolution(VALID_SELF_KEY, pubkyProfile = createPubkyProfile()))

        val adoption = async { sut.adoptRingIdentity(ringPubky) }
        signInStarted.await()
        val retry = async { sut.restoreSessionIfNeeded() }
        assertFalse(retry.isCompleted)

        finishSignIn.complete(Unit)

        assertTrue(adoption.await().isSuccess)
        retry.await()
        assertEquals(VALID_SELF_KEY, sut.publicKey.value)
        verify(pubkyService, never()).importSession(any())
    }

    @Test
    fun `wipe waits for restoration then prevents a queued retry from resurrecting identity`() = test {
        val credentials = mutableMapOf(Keychain.Key.PAYKIT_SESSION.name to "saved_session")
        whenever(keychain.loadString(any())).thenAnswer { credentials[it.getArgument<String>(0)] }
        whenever(keychain.delete(any())).thenAnswer {
            credentials.remove(it.getArgument<String>(0))
            Unit
        }
        val restoreStarted = CompletableDeferred<Unit>()
        val finishRestore = CompletableDeferred<Unit>()
        whenever(pubkyService.importSession("saved_session")).doSuspendableAnswer {
            restoreStarted.complete(Unit)
            finishRestore.await()
            VALID_SELF_KEY
        }
        val restore = async { sut.restoreSessionIfNeeded() }
        restoreStarted.await()
        val wipe = async { sut.wipeLocalState() }
        assertFalse(wipe.isCompleted)
        finishRestore.complete(Unit)
        restore.await()
        wipe.await()
        clearInvocations(pubkyService)

        sut.restoreSessionIfNeeded()

        assertNull(sut.publicKey.value)
        assertFalse(sut.hasIdentity())
        verify(pubkyService, never()).importSession(any())
    }

    @Test
    fun `wipe completes while restoration profile loading remains in flight`() = test {
        sut.awaitInitialization()
        val credentials = mutableMapOf(Keychain.Key.PAYKIT_SESSION.name to "saved_session")
        whenever(keychain.loadString(any())).thenAnswer { credentials[it.getArgument<String>(0)] }
        whenever(keychain.delete(any())).thenAnswer {
            credentials.remove(it.getArgument<String>(0))
            Unit
        }
        whenever(pubkyService.importSession("saved_session")).thenReturn(VALID_SELF_KEY)
        val profileLoadStarted = CompletableDeferred<Unit>()
        val finishProfileLoad = CompletableDeferred<Unit>()
        whenever(pubkyService.resolveContactProfile(VALID_SELF_KEY, true)).doSuspendableAnswer {
            profileLoadStarted.complete(Unit)
            finishProfileLoad.await()
            createResolution(VALID_SELF_KEY, pubkyProfile = createPubkyProfile())
        }
        clearInvocations(pubkyStore)
        val restore = async { sut.restoreSessionIfNeeded() }
        profileLoadStarted.await()

        try {
            val wipe = async { sut.wipeLocalState() }
            wipe.await()

            assertFalse(restore.isCompleted)
            assertNull(sut.publicKey.value)
        } finally {
            finishProfileLoad.complete(Unit)
        }
        restore.await()

        assertNull(sut.profile.value)
        assertTrue(sut.contacts.value.isEmpty())
        verify(pubkyStore).reset()
    }

    @Test
    fun `refreshSessionIfPossible should refresh session when local secret key exists`() = test {
        val secretKey = "local_secret"
        whenever(keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)).thenReturn(secretKey)
        whenever(pubkyService.signIn(secretKey)).thenReturn(Unit)
        whenever(pubkyService.publicKeyFromSecret(secretKey)).thenReturn(VALID_SELF_KEY.removePrefix("pubky"))

        val result = sut.refreshSessionIfPossible()

        assertEquals(true, result.getOrNull())
        assertEquals(VALID_SELF_KEY, sut.publicKey.value)
        assertTrue(sut.isAuthenticated.value)
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
        whenever(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn("test mnemonic")
        whenever(pubkyService.deriveSecretKey("test mnemonic")).thenReturn("derived_secret")
        whenever(pubkyService.signIn("derived_secret")).thenReturn(Unit)
        whenever(pubkyService.publicKeyFromSecret("derived_secret")).thenReturn(VALID_SELF_KEY.removePrefix("pubky"))

        val result = sut.restoreSessionBackupState(
            PubkySessionBackupV1(kind = PubkySessionBackupKind.LocalSeed),
        )

        assertTrue(result.isSuccess)
        assertEquals(VALID_SELF_KEY, sut.publicKey.value)
        verifyBlocking(keychain) { upsertString(Keychain.Key.PUBKY_SECRET_KEY.name, "derived_secret") }
        verifyBlocking(keychain) { delete(Keychain.Key.PAYKIT_SESSION.name) }
        verifyBlocking(keychain, never()) { loadString(Keychain.Key.BIP39_PASSPHRASE.name) }
    }

    @Test
    fun `restoreSessionBackupState should keep local secret when local seed sign in fails`() = test {
        whenever(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn("test mnemonic")
        whenever(pubkyService.deriveSecretKey("test mnemonic")).thenReturn("derived_secret")
        whenever(pubkyService.signIn("derived_secret")).thenThrow(RuntimeException("offline"))

        val result = sut.restoreSessionBackupState(
            PubkySessionBackupV1(kind = PubkySessionBackupKind.LocalSeed),
        )

        assertTrue(result.isFailure)
        verifyBlocking(keychain) { upsertString(Keychain.Key.PUBKY_SECRET_KEY.name, "derived_secret") }
        verifyBlocking(keychain) { delete(Keychain.Key.PAYKIT_SESSION.name) }
        verifyBlocking(keychain, never()) { loadString(Keychain.Key.BIP39_PASSPHRASE.name) }
        assertNull(sut.publicKey.value)
        assertFalse(sut.isAuthenticated.value)
    }

    @Test
    fun `restoreSessionBackupState should restore no identity for legacy external session backups`() = test {
        val result = sut.restoreSessionBackupState(
            PubkySessionBackupV1(kind = PubkySessionBackupKind.ExternalSession),
        )

        assertTrue(result.isSuccess)
        assertNull(sut.publicKey.value)
        assertFalse(sut.isAuthenticated.value)
    }

    @Test
    fun `restoreSessionBackupState should forget current session when backup has no pubky state`() = test {
        authenticateForTesting(publicKey = VALID_SELF_KEY)
        clearInvocations(pubkyService, keychain)

        val result = sut.restoreSessionBackupState(null)

        assertTrue(result.isSuccess)
        assertFalse(sut.isAuthenticated.value)
        assertNull(sut.publicKey.value)
        verifyBlocking(pubkyService) { forgetSessionAccess() }
        verifyBlocking(keychain) { delete(Keychain.Key.PAYKIT_SESSION.name) }
        verifyBlocking(keychain) { delete(Keychain.Key.PUBKY_SECRET_KEY.name) }
    }

    @Test
    fun `restore without backup clears credentials when forgetting current session fails`() = test {
        authenticateForTesting(publicKey = VALID_SELF_KEY)
        whenever(pubkyService.forgetSessionAccess()).thenAnswer { throw TestAppError("Forget failed") }

        val result = sut.restoreSessionBackupState(null)

        assertTrue(result.isSuccess)
        assertFalse(sut.isAuthenticated.value)
        assertNull(sut.publicKey.value)
        verifyBlocking(keychain) { delete(Keychain.Key.PAYKIT_SESSION.name) }
        verifyBlocking(keychain) { delete(Keychain.Key.PUBKY_SECRET_KEY.name) }
    }

    @Test
    fun `loadContacts should populate contacts on success`() = test {
        authenticateForTesting()
        val contactKey = "pubkycontact1"
        whenever(pubkyService.contactRecords())
            .thenReturn(listOf(createContactRecord(contactKey, profile = createPaykitProfile("Alice", bio = "Hello"))))

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
    fun `addContact should canonicalize key before persistence`() = test {
        authenticateForTesting()
        val profile = PubkyProfile.placeholder(NON_CANONICAL_CONTACT_KEY_A)
        whenever { pubkyService.discoverRelevantReceiverPaths(VALID_CONTACT_KEY_A) }.thenReturn(emptyList())

        val result = sut.addContact(NON_CANONICAL_CONTACT_KEY_A, existingProfile = profile)

        assertTrue(result.isSuccess)
        assertEquals(VALID_CONTACT_KEY_A, sut.contacts.value.single().publicKey)
        verifyBlocking(pubkyService) {
            saveContact(VALID_CONTACT_KEY_A, profile.name, emptyList(), restorePrivateConnection = true)
        }
    }

    @Test
    fun `refreshContactReceiverPaths should update saved contact receiver paths`() = test {
        authenticateForTesting()
        val contact = PubkyProfile(
            publicKey = VALID_CONTACT_KEY_B,
            name = "Alice",
            bio = "",
            imageUrl = null,
            links = emptyList(),
            tags = emptyList(),
            status = null,
        )
        sut.addContact(VALID_CONTACT_KEY_B, existingProfile = contact)
        clearInvocations(pubkyService)
        whenever(pubkyService.discoverRelevantReceiverPaths(VALID_CONTACT_KEY_B))
            .thenReturn(listOf("bitkit/wallet", "bitkit/server"))

        val result = sut.refreshContactReceiverPaths(VALID_CONTACT_KEY_B)

        assertTrue(result.isSuccess)
        verifyBlocking(pubkyService) {
            saveContact(
                VALID_CONTACT_KEY_B,
                "Alice",
                listOf("bitkit/wallet", "bitkit/server"),
            )
        }
    }

    @Test
    fun `refreshContactReceiverPaths should preserve a loaded noncanonical key`() = test {
        authenticateForTesting()
        whenever(pubkyService.contactRecords()).thenReturn(
            listOf(
                createContactRecord(
                    publicKey = NON_CANONICAL_CONTACT_KEY_A,
                    profile = createPaykitProfile("Alice"),
                ),
            ),
        )
        sut.loadContacts()
        clearInvocations(pubkyService)
        whenever(pubkyService.discoverRelevantReceiverPaths(NON_CANONICAL_CONTACT_KEY_A))
            .thenReturn(listOf("bitkit/wallet", "bitkit/server"))

        val result = sut.refreshContactReceiverPaths(NON_CANONICAL_CONTACT_KEY_A)

        assertTrue(result.isSuccess)
        verifyBlocking(pubkyService) {
            saveContact(
                NON_CANONICAL_CONTACT_KEY_A,
                "Alice",
                listOf("bitkit/wallet", "bitkit/server"),
            )
        }
    }

    @Test
    fun `deletion during initial load preserves other saved contacts`() = test {
        authenticateForTesting()
        val snapshotReady = CompletableDeferred<Unit>()
        val resumeLoad = CompletableDeferred<Unit>()
        val survivor = createContactRecord(VALID_CONTACT_KEY_A, profile = createPaykitProfile("Survivor"))
        var firstRead = true
        whenever(pubkyService.contactRecords()).doSuspendableAnswer {
            if (!firstRead) return@doSuspendableAnswer listOf(survivor)
            firstRead = false
            snapshotReady.complete(Unit)
            resumeLoad.await()
            listOf(survivor, createContactRecord(VALID_CONTACT_KEY_B, profile = createPaykitProfile("Deleted")))
        }
        val load = launch { sut.loadContacts() }
        snapshotReady.await()
        assertTrue(sut.removeContact(VALID_CONTACT_KEY_B).isSuccess)
        resumeLoad.complete(Unit)
        load.join()
        assertEquals(listOf(VALID_CONTACT_KEY_A), sut.contacts.value.map { it.publicKey })
        assertFalse(sut.isLoadingContacts.value)
    }

    @Test
    fun `removeContact normalizes only wrapped active subscription errors`() = test {
        authenticateForTesting()
        whenever(pubkyService.contactRecords()).thenReturn(
            listOf(createContactRecord(VALID_CONTACT_KEY_A, profile = createPaykitProfile("Contact"))),
        )
        sut.loadContacts()
        val activeSubscriptionError = AppError(PubkyContactError.ActiveSubscription)
        var serviceError = activeSubscriptionError
        whenever(pubkyService.removeContact(VALID_CONTACT_KEY_A)).thenAnswer { throw serviceError }

        val activeResult = sut.removeContact(VALID_CONTACT_KEY_A)

        assertSame(PubkyContactError.ActiveSubscription, activeResult.exceptionOrNull())
        assertEquals(listOf(VALID_CONTACT_KEY_A), sut.contacts.value.map { it.publicKey })

        val unrelatedError = AppError(TestAppError("remove failed"))
        serviceError = unrelatedError

        val unrelatedResult = sut.removeContact(VALID_CONTACT_KEY_A)

        assertSame(unrelatedError, unrelatedResult.exceptionOrNull())
        assertEquals(listOf(VALID_CONTACT_KEY_A), sut.contacts.value.map { it.publicKey })
    }

    @Test
    fun `loadContacts should return early when no public key`() = test {
        sut.loadContacts()

        verify(pubkyService, never()).contactRecords()
    }

    @Test
    fun `loadContacts should allow retry when failure completion is observed`() = test {
        authenticateForTesting()
        val completionVersion = sut.contactsLoadCompletionVersion.value
        clearInvocations(pubkyService)
        whenever(pubkyService.contactRecords())
            .thenAnswer { throw TestAppError("Offline") }
            .thenReturn(emptyList())
        val retry = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            sut.contactsLoadCompletionVersion.first { it > completionVersion }
            sut.loadContacts()
        }

        sut.loadContacts()
        retry.join()

        verifyBlocking(pubkyService, times(2)) { contactRecords() }
        assertEquals(completionVersion + 2, sut.contactsLoadCompletionVersion.value)
    }

    @Test
    fun `loadContacts should use placeholder when profile fetch fails`() = test {
        authenticateForTesting()
        val contactKey = "pubkycontact2"
        whenever(pubkyService.contactRecords()).thenReturn(listOf(createContactRecord(contactKey)))
        whenever(pubkyService.resolveContactProfile(contactKey, true, PaykitReadLane.Bulk))
            .thenAnswer { throw TestAppError("Network error") }

        sut.loadContacts()

        val contacts = sut.contacts.value
        assertEquals(1, contacts.size)
        assertEquals(contactKey, contacts.first().publicKey)
        assertFalse(sut.isLoadingContacts.value)
    }

    @Test
    fun `loadContacts resolves each contact once without retrying a missing profile or an error`() = test {
        authenticateForTesting()
        whenever(pubkyService.contactRecords())
            .thenReturn(listOf(createContactRecord(VALID_CONTACT_KEY_A), createContactRecord(VALID_CONTACT_KEY_B)))
        whenever(pubkyService.resolveContactProfile(VALID_CONTACT_KEY_A, true, PaykitReadLane.Bulk)).thenReturn(null)
        whenever(pubkyService.resolveContactProfile(VALID_CONTACT_KEY_B, true, PaykitReadLane.Bulk))
            .thenAnswer { throw TestAppError("Unreachable") }

        sut.loadContacts()

        assertEquals(
            setOf(VALID_CONTACT_KEY_A, VALID_CONTACT_KEY_B),
            sut.contacts.value.map { it.publicKey }.toSet(),
        )
        verify(pubkyService, times(1)).resolveContactProfile(VALID_CONTACT_KEY_A, true, PaykitReadLane.Bulk)
        verify(pubkyService, times(1)).resolveContactProfile(VALID_CONTACT_KEY_B, true, PaykitReadLane.Bulk)
    }

    @Test
    fun `addContact retries a failed contact resolution once`() = test {
        authenticateForTesting(publicKey = VALID_SELF_KEY)
        whenever(pubkyService.resolveContactProfile(VALID_CONTACT_KEY_A, true))
            .thenAnswer { throw TestAppError("Network error") }
            .thenReturn(createResolution(VALID_CONTACT_KEY_A, paykitProfile = createPaykitProfile("Alice")))

        assertTrue(sut.addContact(VALID_CONTACT_KEY_A).isSuccess)

        assertEquals(listOf("Alice"), sut.contacts.value.map { it.name })
        verify(pubkyService, times(2)).resolveContactProfile(VALID_CONTACT_KEY_A, true)
    }

    @Test
    fun `importContacts saves the prepared profiles and keeps unresolved follows as placeholders`() = test {
        authenticateForTesting(publicKey = VALID_SELF_KEY)
        whenever(pubkyService.getContacts(VALID_SELF_KEY)).thenReturn(listOf(VALID_CONTACT_KEY_A, VALID_CONTACT_KEY_B))
        whenever(pubkyService.resolveContactProfile(VALID_CONTACT_KEY_A, true, PaykitReadLane.Interactive))
            .thenReturn(createResolution(VALID_CONTACT_KEY_A, paykitProfile = createPaykitProfile("Alice")))
        whenever(pubkyService.resolveContactProfile(VALID_CONTACT_KEY_B, true, PaykitReadLane.Interactive))
            .thenAnswer { throw TestAppError("Unreachable") }
        whenever(pubkyService.discoverRelevantReceiverPaths(VALID_CONTACT_KEY_A, PaykitReadLane.Bulk))
            .thenReturn(listOf("bitkit/wallet", "bitkit/server"))
        assertTrue(sut.prepareImport().isSuccess)
        clearInvocations(pubkyService)
        val placeholder = PubkyProfile.placeholder(VALID_CONTACT_KEY_B)

        val result = sut.importContacts(sut.pendingImportContacts.value)

        assertTrue(result.isSuccess)
        assertEquals(setOf("Alice", placeholder.name), sut.contacts.value.map { it.name }.toSet())
        verify(pubkyService, never()).resolveContactProfile(any(), any(), any())
        verifyBlocking(pubkyService) {
            saveContact(VALID_CONTACT_KEY_A, "Alice", listOf("bitkit/wallet", "bitkit/server"), true)
        }
        verifyBlocking(pubkyService) {
            saveContact(VALID_CONTACT_KEY_B, placeholder.name, listOf("bitkit/wallet"), true)
        }
        verifyBlocking(pubkyService, never()) { discoverRelevantReceiverPaths(eq(VALID_CONTACT_KEY_B), any()) }
    }

    @Test
    fun `importContacts keeps the saved label of a contact whose follow it could not resolve`() = test {
        authenticateForTesting(publicKey = VALID_SELF_KEY)
        whenever(pubkyService.getContacts(VALID_SELF_KEY)).thenReturn(listOf(VALID_CONTACT_KEY_A, VALID_CONTACT_KEY_B))
        whenever(pubkyService.resolveContactProfile(any(), any(), any()))
            .thenAnswer { throw TestAppError("Unreachable") }
        assertTrue(sut.prepareImport().isSuccess)
        whenever(pubkyService.contactRecords()).thenReturn(listOf(createContactRecord(VALID_CONTACT_KEY_A, "Alice")))
        val placeholder = PubkyProfile.placeholder(VALID_CONTACT_KEY_B)

        assertTrue(sut.importContacts(sut.pendingImportContacts.value).isSuccess)

        verifyBlocking(pubkyService) { saveContact(VALID_CONTACT_KEY_A, "Alice", listOf("bitkit/wallet"), true) }
        verifyBlocking(pubkyService) {
            saveContact(VALID_CONTACT_KEY_B, placeholder.name, listOf("bitkit/wallet"), true)
        }
    }

    @Test
    fun `importContacts discovers receivers of a contact it has no record of resolving`() = test {
        authenticateForTesting(publicKey = VALID_SELF_KEY)
        val placeholder = PubkyProfile.placeholder(VALID_CONTACT_KEY_A)
        whenever(pubkyService.discoverRelevantReceiverPaths(VALID_CONTACT_KEY_A, PaykitReadLane.Bulk))
            .thenReturn(listOf("bitkit/wallet", "bitkit/server"))

        assertTrue(sut.importContacts(listOf(placeholder)).isSuccess)

        verifyBlocking(pubkyService) {
            saveContact(VALID_CONTACT_KEY_A, placeholder.name, listOf("bitkit/wallet", "bitkit/server"), true)
        }
    }

    @Test
    fun `importContacts keeps saving after its caller is cancelled`() = test {
        authenticateForTesting(publicKey = VALID_SELF_KEY)
        val alice = PubkyProfile.placeholder(VALID_CONTACT_KEY_A).copy(name = "Alice")
        val saveStarted = CompletableDeferred<Unit>()
        val finishSave = CompletableDeferred<Unit>()
        whenever(pubkyService.saveContact(eq(VALID_CONTACT_KEY_A), any(), any(), any())).doSuspendableAnswer {
            saveStarted.complete(Unit)
            finishSave.await()
            createContactRecord(VALID_CONTACT_KEY_A)
        }
        assertFalse(sut.isImportingContacts.value)
        val caller = launch { sut.importContacts(listOf(alice)) }
        saveStarted.await()
        assertTrue(sut.isImportingContacts.value)

        caller.cancelAndJoin()
        finishSave.complete(Unit)

        assertEquals(listOf(alice), sut.contacts.value)
        assertFalse(sut.isImportingContacts.value)
    }

    @Test
    fun `importContacts stops saving once the identity changes`() = test {
        authenticateForTesting(publicKey = VALID_SELF_KEY)
        val alice = PubkyProfile.placeholder(VALID_CONTACT_KEY_A).copy(name = "Alice")
        val discoveryStarted = CompletableDeferred<Unit>()
        val finishDiscovery = CompletableDeferred<Unit>()
        whenever(pubkyService.discoverRelevantReceiverPaths(VALID_CONTACT_KEY_A, PaykitReadLane.Bulk))
            .doSuspendableAnswer {
                discoveryStarted.complete(Unit)
                finishDiscovery.await()
                listOf("bitkit/wallet")
            }
        val import = async { sut.importContacts(listOf(alice)) }
        discoveryStarted.await()

        sut.wipeLocalState()
        finishDiscovery.complete(Unit)

        assertTrue(import.await().isFailure)
        verifyBlocking(pubkyService, never()) { saveContact(any(), any(), any(), any()) }
        assertTrue(sut.contacts.value.isEmpty())
        assertFalse(sut.isImportingContacts.value)
    }

    @Test
    fun `importContacts clears the pending import once it succeeds`() = test {
        authenticateForTesting(publicKey = VALID_SELF_KEY)
        whenever(pubkyService.getContacts(VALID_SELF_KEY)).thenReturn(listOf(VALID_CONTACT_KEY_A))
        assertTrue(sut.prepareImport().isSuccess)
        assertEquals(1, sut.pendingImportContacts.value.size)

        assertTrue(sut.importContacts(sut.pendingImportContacts.value).isSuccess)

        assertNull(sut.pendingImportProfile.value)
        assertTrue(sut.pendingImportContacts.value.isEmpty())
        assertEquals(listOf(VALID_CONTACT_KEY_A), sut.contacts.value.map { it.publicKey })
    }

    @Test
    fun `importContacts leaves out only a contact whose save fails`() = test {
        authenticateForTesting(publicKey = VALID_SELF_KEY)
        val alice = PubkyProfile.placeholder(VALID_CONTACT_KEY_A).copy(name = "Alice")
        val bob = PubkyProfile.placeholder(VALID_CONTACT_KEY_B).copy(name = "Bob")
        whenever(pubkyService.saveContact(eq(VALID_CONTACT_KEY_B), any(), any(), any()))
            .thenAnswer { throw TestAppError("Save failed") }

        val result = sut.importContacts(listOf(alice, bob))

        assertTrue(result.isSuccess)
        assertEquals(listOf(alice), sut.contacts.value)
    }

    @Test
    fun `loadContacts publishes saved records before their profile lookups finish`() = test {
        authenticateForTesting()
        whenever(pubkyService.contactRecords()).thenReturn(
            listOf(
                createContactRecord(VALID_CONTACT_KEY_A, label = "Saved label"),
                createContactRecord(VALID_CONTACT_KEY_B, profile = createPaykitProfile("Bob")),
            ),
        )
        val lookup = CompletableDeferred<ContactProfileResolution?>()
        whenever(pubkyService.resolveContactProfile(VALID_CONTACT_KEY_A, true, PaykitReadLane.Bulk))
            .doSuspendableAnswer { lookup.await() }
        val loadVersion = sut.contactsLoadVersion.value
        val completionVersion = sut.contactsLoadCompletionVersion.value

        sut.loadContacts()

        assertEquals(listOf("Bob", "Saved label"), sut.contacts.value.map { it.name })
        assertFalse(sut.isLoadingContacts.value)
        assertEquals(loadVersion + 1, sut.contactsLoadVersion.value)
        assertEquals(completionVersion + 1, sut.contactsLoadCompletionVersion.value)

        lookup.complete(createResolution(VALID_CONTACT_KEY_A, paykitProfile = createPaykitProfile("Alice")))

        assertEquals(listOf("Alice", "Bob"), sut.contacts.value.map { it.name })
        assertEquals(listOf(VALID_CONTACT_KEY_A, VALID_CONTACT_KEY_B), sut.contacts.value.map { it.publicKey })
        assertEquals(loadVersion + 1, sut.contactsLoadVersion.value)
        assertEquals(completionVersion + 1, sut.contactsLoadCompletionVersion.value)
        verify(pubkyService, never()).resolveContactProfile(eq(VALID_CONTACT_KEY_B), any(), any())
    }

    @Test
    fun `loadContacts shows a profile resolved earlier in the session while it refreshes`() = test {
        authenticateForTesting(publicKey = VALID_SELF_KEY)
        whenever(pubkyService.getContacts(VALID_SELF_KEY)).thenReturn(listOf(VALID_CONTACT_KEY_A))
        whenever(pubkyService.resolveContactProfile(VALID_CONTACT_KEY_A, true, PaykitReadLane.Interactive))
            .thenReturn(createResolution(VALID_CONTACT_KEY_A, paykitProfile = createPaykitProfile("Alice")))
        assertTrue(sut.prepareImport().isSuccess)
        val lookup = CompletableDeferred<ContactProfileResolution?>()
        whenever(pubkyService.resolveContactProfile(VALID_CONTACT_KEY_A, true, PaykitReadLane.Bulk))
            .doSuspendableAnswer { lookup.await() }
        whenever(pubkyService.contactRecords()).thenReturn(listOf(createContactRecord(VALID_CONTACT_KEY_A)))

        sut.loadContacts()

        assertEquals(listOf("Alice"), sut.contacts.value.map { it.name })
        lookup.complete(createResolution(VALID_CONTACT_KEY_A, paykitProfile = createPaykitProfile("Alice Renamed")))
        assertEquals(listOf("Alice Renamed"), sut.contacts.value.map { it.name })
    }

    @Test
    fun `sign out drops session contact profiles and stops their refresh`() = test {
        authenticateForTesting(publicKey = VALID_SELF_KEY)
        whenever(pubkyService.getContacts(VALID_SELF_KEY)).thenReturn(listOf(VALID_CONTACT_KEY_A))
        whenever(pubkyService.resolveContactProfile(VALID_CONTACT_KEY_A, true, PaykitReadLane.Interactive))
            .thenReturn(createResolution(VALID_CONTACT_KEY_A, paykitProfile = createPaykitProfile("Alice")))
        assertTrue(sut.prepareImport().isSuccess)
        val lookup = CompletableDeferred<ContactProfileResolution?>()
        whenever(pubkyService.resolveContactProfile(VALID_CONTACT_KEY_A, true, PaykitReadLane.Bulk))
            .doSuspendableAnswer { lookup.await() }
        whenever(pubkyService.contactRecords()).thenReturn(listOf(createContactRecord(VALID_CONTACT_KEY_A)))
        sut.loadContacts()

        sut.wipeLocalState()
        lookup.complete(createResolution(VALID_CONTACT_KEY_A, paykitProfile = createPaykitProfile("Alice")))
        assertTrue(sut.contacts.value.isEmpty())
        authenticateForTesting(publicKey = VALID_SELF_KEY)
        whenever(pubkyService.contactRecords()).thenReturn(listOf(createContactRecord(VALID_CONTACT_KEY_A)))
        whenever(pubkyService.resolveContactProfile(VALID_CONTACT_KEY_A, true, PaykitReadLane.Bulk))
            .doSuspendableAnswer { awaitCancellation() }
        sut.loadContacts()

        assertEquals(listOf(PubkyProfile.placeholder(VALID_CONTACT_KEY_A).name), sut.contacts.value.map { it.name })
    }

    @Test
    fun `a contact profile refresh leaves an edited or removed contact alone`() = test {
        authenticateForTesting()
        whenever(pubkyService.contactRecords()).thenReturn(
            listOf(createContactRecord(VALID_CONTACT_KEY_A), createContactRecord(VALID_CONTACT_KEY_B)),
        )
        val lookups = listOf(VALID_CONTACT_KEY_A, VALID_CONTACT_KEY_B).associateWith { CompletableDeferred<Unit>() }
        lookups.forEach { (key, gate) ->
            whenever(pubkyService.resolveContactProfile(key, true, PaykitReadLane.Bulk)).doSuspendableAnswer {
                gate.await()
                createResolution(key, paykitProfile = createPaykitProfile("Resolved"))
            }
        }
        sut.loadContacts()

        assertTrue(sut.updateContact(VALID_CONTACT_KEY_A, "Edited", "", null, emptyList(), emptyList()).isSuccess)
        assertTrue(sut.removeContact(VALID_CONTACT_KEY_B).isSuccess)
        lookups.values.forEach { it.complete(Unit) }

        assertEquals(listOf("Edited"), sut.contacts.value.map { it.name })
    }

    @Test
    fun `resolvePendingContactProfile looks up a label-only contact on the interactive lane`() = test {
        authenticateForTesting()
        whenever(pubkyService.contactRecords()).thenReturn(listOf(createContactRecord(VALID_CONTACT_KEY_A, "Saved")))
        whenever(pubkyService.resolveContactProfile(VALID_CONTACT_KEY_A, true, PaykitReadLane.Bulk))
            .doSuspendableAnswer { awaitCancellation() }
        val alice = createPaykitProfile("Alice", image = "pubky://a")
        whenever(pubkyService.resolveContactProfile(VALID_CONTACT_KEY_A, true, PaykitReadLane.Interactive))
            .thenReturn(createResolution(VALID_CONTACT_KEY_A, paykitProfile = alice))
        sut.loadContacts()

        sut.resolvePendingContactProfile(VALID_CONTACT_KEY_A)
        sut.resolvePendingContactProfile(VALID_CONTACT_KEY_A)

        assertEquals(listOf("Alice" to "pubky://a"), sut.contacts.value.map { it.name to it.imageUrl })
        verify(pubkyService, times(1)).resolveContactProfile(VALID_CONTACT_KEY_A, true, PaykitReadLane.Interactive)
    }

    @Test
    fun `resolvePendingContactProfile leaves a contact showing a profile alone`() = test {
        authenticateForTesting(publicKey = VALID_SELF_KEY)
        whenever(pubkyService.getContacts(VALID_SELF_KEY)).thenReturn(listOf(VALID_CONTACT_KEY_A))
        whenever(pubkyService.resolveContactProfile(VALID_CONTACT_KEY_A, true, PaykitReadLane.Interactive))
            .thenReturn(createResolution(VALID_CONTACT_KEY_A, paykitProfile = createPaykitProfile("Alice")))
        assertTrue(sut.prepareImport().isSuccess)
        clearInvocations(pubkyService)
        whenever(pubkyService.resolveContactProfile(VALID_CONTACT_KEY_A, true, PaykitReadLane.Bulk))
            .doSuspendableAnswer { awaitCancellation() }
        whenever(pubkyService.contactRecords()).thenReturn(
            listOf(
                createContactRecord(VALID_CONTACT_KEY_A),
                createContactRecord(VALID_CONTACT_KEY_B, profile = createPaykitProfile("Bob")),
            ),
        )
        sut.loadContacts()

        sut.resolvePendingContactProfile(VALID_CONTACT_KEY_A)
        sut.resolvePendingContactProfile(VALID_CONTACT_KEY_B)

        assertEquals(listOf("Alice", "Bob"), sut.contacts.value.map { it.name })
        listOf(VALID_CONTACT_KEY_A, VALID_CONTACT_KEY_B).forEach {
            verify(pubkyService, never()).resolveContactProfile(it, true, PaykitReadLane.Interactive)
        }
    }

    @Test
    fun `repeated contact loads share one background profile refresh`() = test {
        authenticateForTesting()
        whenever(pubkyService.contactRecords()).thenReturn(listOf(createContactRecord(VALID_CONTACT_KEY_A)))
        val lookup = CompletableDeferred<ContactProfileResolution?>()
        whenever(pubkyService.resolveContactProfile(VALID_CONTACT_KEY_A, true, PaykitReadLane.Bulk))
            .doSuspendableAnswer { lookup.await() }

        sut.loadContacts()
        sut.loadContacts()
        lookup.complete(createResolution(VALID_CONTACT_KEY_A, paykitProfile = createPaykitProfile("Alice")))

        assertEquals(listOf("Alice"), sut.contacts.value.map { it.name })
        verify(pubkyService, times(1)).resolveContactProfile(VALID_CONTACT_KEY_A, true, PaykitReadLane.Bulk)
    }

    @Test
    fun `loadContacts should treat empty SDK contact records as empty`() = test {
        authenticateForTesting()
        whenever(pubkyService.contactRecords()).thenReturn(emptyList())

        sut.loadContacts()

        assertTrue(sut.contacts.value.isEmpty())
        assertFalse(sut.isLoadingContacts.value)
    }

    @Test
    fun `fetchContactProfile should return paykit profile when available`() = test {
        val contactKey = VALID_CONTACT_KEY_A
        whenever(pubkyService.resolveContactProfile(contactKey, true))
            .thenReturn(createResolution(contactKey, paykitProfile = createPaykitProfile("Bob", bio = "Bio")))

        val result = sut.fetchContactProfile(contactKey)

        assertTrue(result.isSuccess)
        assertEquals("Bob", result.getOrNull()?.name)
        assertEquals("Bio", result.getOrNull()?.bio)
        verify(pubkyService).resolveContactProfile(contactKey, true)
    }

    @Test
    fun `fetchContactProfile should use pubky profile fallback when SDK resolves one`() = test {
        val contactKey = VALID_CONTACT_KEY_A
        val contactProfile = createPubkyProfile(name = "Bob", bio = "Bio")
        whenever(pubkyService.resolveContactProfile(contactKey, true))
            .thenReturn(createResolution(contactKey, pubkyProfile = contactProfile))

        val result = sut.fetchContactProfile(contactKey)

        assertTrue(result.isSuccess)
        assertEquals("Bob", result.getOrNull()?.name)
    }

    @Test
    fun `fetchContactProfile should fall back to placeholder when remote profile is missing`() = test {
        val contactKey = VALID_CONTACT_KEY_A
        whenever(pubkyService.resolveContactProfile(contactKey, true)).thenReturn(null)

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
    fun `uploadAvatar should publish avatar through Paykit SDK`() = test {
        val session = "test_session"
        val currentPublicKey = "pubkyalice"
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn(session)
        whenever(pubkyService.uploadProfileAvatar(any(), any())).thenReturn("pubky://avatar")

        authenticateForTesting(publicKey = currentPublicKey, secret = session, profileName = "Alice")

        val result = sut.uploadAvatar(byteArrayOf(1, 2, 3))

        assertTrue(result.isSuccess)
        assertEquals("pubky://avatar", result.getOrNull())
        verifyBlocking(pubkyService) { uploadProfileAvatar(any(), any()) }
    }

    @Test
    fun `uploadAvatar should fail when session is missing`() = test {
        val currentPublicKey = "pubkyalice"
        val session = "test_session"

        authenticateForTesting(publicKey = currentPublicKey, secret = session, profileName = "Alice")
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn(null)

        val result = sut.uploadAvatar(byteArrayOf(1, 2, 3))

        assertTrue(result.isFailure)
        verifyBlocking(pubkyService, never()) { uploadProfileAvatar(any(), any()) }
    }

    @Test
    fun `signOut should clear contacts`() = test {
        authenticateForTesting()
        val contactKey = "pubkycontact4"
        whenever(pubkyService.contactRecords()).thenReturn(
            listOf(createContactRecord(contactKey, profile = createPaykitProfile("Charlie"))),
        )

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
        whenever(pubkyService.resolveContactProfile(pendingContactKey, true, PaykitReadLane.Interactive))
            .thenReturn(createResolution(pendingContactKey, paykitProfile = createPaykitProfile("Pending Contact")))

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
        verifyBlocking(pubkyService) { forgetSessionAccess() }
        verifyBlocking(pubkyStore) { reset() }
    }

    @Test
    fun `loadContacts should use contact label when profile is unavailable`() = test {
        authenticateForTesting()
        val contactKey = "pubkyabc123"
        whenever(pubkyService.contactRecords())
            .thenReturn(listOf(createContactRecord(contactKey, label = "Extracted")))
        whenever(pubkyService.resolveContactProfile(contactKey, true, PaykitReadLane.Bulk)).thenReturn(null)

        sut.loadContacts()

        assertEquals("Extracted", sut.contacts.value.first().name)
        assertEquals(contactKey, sut.contacts.value.first().publicKey)
    }

    @Test
    fun `loadContacts should use contact label when paykit profile has blank name`() = test {
        authenticateForTesting()
        val contactKey = "pubkyblankprofile"
        whenever(pubkyService.contactRecords())
            .thenReturn(
                listOf(
                    createContactRecord(
                        contactKey,
                        label = "Extracted",
                        profile = createPaykitProfile(name = "", bio = "Bio", image = "pubky://avatar"),
                    ),
                ),
            )

        sut.loadContacts()

        val contact = sut.contacts.value.first()
        assertEquals("Extracted", contact.name)
        assertEquals("Bio", contact.bio)
        assertEquals("pubky://avatar", contact.imageUrl)
    }

    private suspend fun authenticateForTesting(
        publicKey: String = "test_pk_12345",
        secret: String = "test_secret",
        profileName: String = "Test",
    ) {
        val prefixedPublicKey = publicKey.ensurePubkyPrefixForTest()
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn(secret)
        whenever { pubkyService.importSession(secret) }.thenReturn(publicKey)
        whenever { pubkyService.resolveContactProfile(prefixedPublicKey, true) }
            .thenReturn(createResolution(prefixedPublicKey, pubkyProfile = createPubkyProfile(name = profileName)))
        whenever { pubkyService.contactRecords() }.thenReturn(emptyList())

        sut.initialize()
    }

    private fun stubSavedSessionRestore(): SavedSessionRestore {
        val restore = SavedSessionRestore()
        whenever { keychain.loadString(Keychain.Key.PAYKIT_SESSION.name) }.thenReturn("saved_session")
        whenever { pubkyService.importSession("saved_session") }.doSuspendableAnswer { restore.answer() }
        return restore
    }

    private fun stubGatedPubkyStore(): GatedPubkyStore {
        val store = GatedPubkyStore()
        whenever { pubkyStore.update(any()) }.doSuspendableAnswer {
            val isGated = store.gateNextUpdate
            if (isGated) {
                store.gateNextUpdate = false
                store.gatedUpdateStarted.complete(Unit)
                store.releaseGatedUpdate.await()
            }
            store.data = it.getArgument<(PubkyStoreData) -> PubkyStoreData>(0)(store.data)
            if (isGated) store.gatedUpdateApplied.complete(Unit)
            Unit
        }
        whenever { pubkyStore.reset() }.thenAnswer {
            store.data = PubkyStoreData()
            Unit
        }
        return store
    }

    private fun gateNextProfileLoadStoreWrite(store: GatedPubkyStore, loadedName: String) {
        whenever { pubkyService.resolveContactProfile(VALID_SELF_KEY, true) }.doSuspendableAnswer {
            store.gateNextUpdate = true
            createResolution(VALID_SELF_KEY, pubkyProfile = createPubkyProfile(name = loadedName))
        }
    }

    private fun identityHttpClient() = HttpClient(
        MockEngine {
            respond(
                content = """{"signupCode":"test-code","homeserverPubky":"test-homeserver"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    ) {
        install(ContentNegotiation) { json() }
    }

    private fun createPubkyProfile(
        name: String = "Test",
        bio: String = "",
        image: String? = null,
        status: String? = null,
    ) = SdkPubkyProfile(
        name = name,
        bio = bio,
        image = image,
        links = emptyList(),
        status = status,
    )

    private suspend fun stubRingCredential(): String {
        val ringPubky = VALID_SELF_KEY.removePrefix("pubky")
        whenever(sharedPubkyClient.ringCredential(ringPubky)).thenReturn(Result.success("ring_secret"))
        whenever(pubkyService.publicKeyFromSecret("ring_secret")).thenReturn(ringPubky)
        return ringPubky
    }

    private suspend fun stubSignupKeys() {
        whenever(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn("seed words")
        whenever(pubkyService.deriveSecretKey("seed words")).thenReturn("secret")
        whenever(pubkyService.publicKeyFromSecret("secret")).thenReturn(VALID_SELF_KEY)
    }

    private fun ringSignupRequest() = PubkyAuthRequest.parseSignup(
        "pubkyring://signup?hs=homeserver&relay=https%3A%2F%2Frelay.example" +
            "&secret=request&caps=%2Fpub%2Fexample%2F%3Arw&st=invite",
    ).getOrThrow()

    private fun directSignupRequest() = PubkyAuthRequest.parseSignup(
        "pubkyauth://direct_signup?hs=homeserver&st=invite",
    ).getOrThrow()

    private fun createPaykitProfile(
        name: String,
        bio: String = "",
        image: String? = null,
    ) = PubkyProfile(
        publicKey = "",
        name = name,
        bio = bio,
        imageUrl = image,
        links = emptyList(),
        tags = emptyList(),
        status = null,
    ).toProfileData().toPaykitProfile()

    private fun createContactRecord(
        publicKey: String,
        label: String? = null,
        profile: PaykitProfile? = null,
    ) = ContactRecord(
        publicKey = publicKey,
        receiverPaths = listOf("bitkit/wallet"),
        label = label,
        profile = profile,
        profileFetchedAt = null,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
        publicContactMarkerStatus = PublicationStatus.NOT_PUBLISHED,
        publicContactMarkerReceiverPath = null,
        publicContactPublishedAt = null,
        publicContactRemovedAt = null,
        publicContactLastError = null,
    )

    private fun createResolution(
        publicKey: String,
        paykitProfile: PaykitProfile? = null,
        pubkyProfile: SdkPubkyProfile? = null,
    ) = ContactProfileResolution(
        publicKey = publicKey,
        source = if (paykitProfile != null) {
            ContactProfileSource.PAYKIT_PROFILE
        } else {
            ContactProfileSource.PUBKY_PROFILE
        },
        displayName = paykitProfile?.displayName ?: pubkyProfile?.name,
        imageUri = paykitProfile?.imageUri ?: pubkyProfile?.image,
        paykitProfile = paykitProfile,
        pubkyProfile = pubkyProfile,
        fetchedAt = "2026-01-01T00:00:00Z",
    )
}

private class TestAppError(message: String) : AppError(message)

private class SavedSessionRestore {
    var answer: suspend () -> String = { throw TestAppError("Offline") }
}

private class GatedPubkyStore {
    var data = PubkyStoreData()
    var gateNextUpdate = false
    val gatedUpdateStarted = CompletableDeferred<Unit>()
    val releaseGatedUpdate = CompletableDeferred<Unit>()
    val gatedUpdateApplied = CompletableDeferred<Unit>()
}

private fun String.ensurePubkyPrefixForTest(): String =
    if (startsWith("pubky")) this else "pubky$this"

package to.bitkit.repositories

import app.cash.turbine.test
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import com.synonym.paykit.ContactProfileResolution
import com.synonym.paykit.ContactProfileSource
import com.synonym.paykit.ContactRecord
import com.synonym.paykit.PaykitProfile
import com.synonym.paykit.PubkyAuthCompanionClaim
import com.synonym.paykit.PublicationStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.clearInvocations
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import to.bitkit.data.PubkyStore
import to.bitkit.data.PubkyStoreData
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.models.PubkyAuthClaim
import to.bitkit.models.PubkyAuthRequest
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyRingAuthCallback
import to.bitkit.models.PubkyRingAuthCallbackHandlingResult
import to.bitkit.models.PubkySessionBackupKind
import to.bitkit.models.PubkySessionBackupV1
import to.bitkit.services.PubkyService
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import com.synonym.paykit.PubkyProfile as SdkPubkyProfile

@Suppress("LargeClass")
class PubkyRepoTest : BaseUnitTest() {
    companion object {
        // Valid 52-char z-base-32 key (+ "pubky" prefix = 57 chars)
        private const val VALID_CONTACT_KEY_A = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        private const val VALID_CONTACT_KEY_B = "pubky1rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        private const val VALID_SELF_KEY = "pubky5rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
    }

    private lateinit var sut: PubkyRepo

    private val pubkyService = mock<PubkyService>()
    private val keychain = mock<Keychain>()
    private val imageLoader = mock<ImageLoader>()
    private val pubkyStore = mock<PubkyStore>()
    private val settingsStore = mock<SettingsStore>()
    private val settingsFlow = MutableStateFlow(SettingsData())
    private val profileSetupPending = MutableStateFlow(false)

    @Before
    fun setUp() = runBlocking {
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
        sut = createSut()
    }

    private fun createSut(httpClient: HttpClient = mock()) = PubkyRepo(
        ioDispatcher = testDispatcher,
        pubkyService = pubkyService,
        keychain = keychain,
        imageLoader = imageLoader,
        pubkyStore = pubkyStore,
        settingsStore = settingsStore,
        httpClient = httpClient,
    )

    @Test
    fun `initial state should have no public key`() = test {
        assertNull(sut.publicKey.value)
        assertFalse(sut.isAuthenticated.value)
    }

    @Test
    fun `Ring signup registers and authorizes before activating the local session`() = test {
        val events = mutableListOf<String>()
        val request = ringSignupRequest()
        stubSignupKeys()
        whenever(pubkyService.registerIdentity("secret", "homeserver", "invite")).thenAnswer {
            events += "register"
        }
        whenever(pubkyService.approveRingAuth(request.authorizationUrl, "secret")).thenAnswer {
            events += "authorize"
        }
        whenever(pubkyService.signIn("secret")).thenAnswer { events += "activate" }

        val result = sut.approveSignupAuth(request)

        assertTrue(result.isSuccess)
        assertEquals(listOf("register", "authorize", "activate"), events)
        assertTrue(profileSetupPending.value)
        assertEquals(VALID_SELF_KEY, sut.publicKey.value)
    }

    @Test
    fun `Ring signup marks profile setup pending before local activation`() = test {
        val request = ringSignupRequest()
        stubSignupKeys()
        whenever(pubkyService.signIn("secret")).thenAnswer {
            assertTrue(profileSetupPending.value)
            throw TestAppError("activation failed")
        }

        assertTrue(sut.approveSignupAuth(request).isFailure)
        assertTrue(profileSetupPending.value)
        assertNull(sut.publicKey.value)
    }

    @Test
    fun `Ring signup stops when registration fails`() = test {
        val request = ringSignupRequest()
        stubSignupKeys()
        whenever(pubkyService.registerIdentity("secret", "homeserver", "invite"))
            .thenThrow(IllegalStateException("registration failed"))

        assertTrue(sut.approveSignupAuth(request).isFailure)
        verifyBlocking(pubkyService, never()) { approveRingAuth(any(), any()) }
        verifyBlocking(pubkyService, never()) { signIn(any()) }
        assertFalse(profileSetupPending.value)
    }

    @Test
    fun `identity check fails closed when secure storage cannot be read`() = test {
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenThrow(IllegalStateException("unavailable"))

        assertTrue(runCatching { sut.hasIdentity() }.isFailure)
    }

    @Test
    fun `startAuthentication should return auth uri on success`() = test {
        val authUri = "pubky://auth?capabilities=..."
        whenever(pubkyService.startAuth()).thenReturn(authUri)

        val result = sut.startAuthentication()

        assertTrue(result.isSuccess)
        assertEquals(authUri, result.getOrNull()?.authUrl)
        assertNotNull(result.getOrNull()?.callbackNonce)
    }

    @Test
    fun `startAuthentication should reset state on failure`() = test {
        whenever(pubkyService.startAuth()).thenAnswer { throw TestAppError("Auth failed") }

        val result = sut.startAuthentication()

        assertTrue(result.isFailure)
        sut.isAuthenticated.test(timeout = 500.milliseconds) {
            assertFalse(awaitItem())
        }
    }

    @Test
    fun `completeAuthentication should save session and update state`() = test {
        val testSecret = "session_secret"
        val testPk = VALID_SELF_KEY.removePrefix("pubky")
        whenever(pubkyService.startAuth()).thenReturn("auth_uri")
        whenever(pubkyService.completeAuth()).thenReturn(Unit)
        whenever(pubkyService.currentPublicKey()).thenReturn(testPk)

        val pubkyProfile = createPubkyProfile(name = "User")
        whenever(pubkyService.resolveContactProfile(VALID_SELF_KEY, true))
            .thenReturn(createResolution(VALID_SELF_KEY, pubkyProfile = pubkyProfile))
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn(testSecret)

        val authRequest = startAuthForTesting()
        approveAuthForTesting(authRequest)
        val result = sut.completeAuthentication()

        assertTrue(result.isSuccess)
        assertEquals(VALID_SELF_KEY, sut.publicKey.value)
        assertTrue(sut.isAuthenticated.value)
    }

    @Test
    fun `completeAuthentication should load contacts automatically`() = test {
        val testSecret = "session_secret"
        val testPk = VALID_SELF_KEY.removePrefix("pubky")
        whenever(pubkyService.startAuth()).thenReturn("auth_uri")
        whenever(pubkyService.completeAuth()).thenReturn(Unit)
        whenever(pubkyService.currentPublicKey()).thenReturn(testPk)
        val pubkyProfile = createPubkyProfile(name = "User")
        whenever(pubkyService.resolveContactProfile(VALID_SELF_KEY, true))
            .thenReturn(createResolution(VALID_SELF_KEY, pubkyProfile = pubkyProfile))
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn(testSecret)

        val authRequest = startAuthForTesting()
        approveAuthForTesting(authRequest)
        val result = sut.completeAuthentication()

        assertTrue(result.isSuccess)
        verify(pubkyService).contactRecords()
    }

    @Test
    fun `completeAuthentication should reset state on failure`() = test {
        whenever(pubkyService.startAuth()).thenReturn("auth_uri")
        whenever(pubkyService.completeAuth()).thenAnswer { throw TestAppError("Failed") }

        val authRequest = startAuthForTesting()
        approveAuthForTesting(authRequest)
        val result = sut.completeAuthentication()

        assertTrue(result.isFailure)
        assertFalse(sut.isAuthenticated.value)
        assertNull(sut.publicKey.value)
        verifyBlocking(pubkyService) { signOut() }
    }

    @Test
    fun `completeAuthentication should fail when auth attempt inactive`() = test {
        val result = sut.completeAuthentication()

        assertTrue(result.isFailure)
        verifyBlocking(pubkyService, never()) { completeAuth() }
    }

    @Test
    fun `completeAuthentication should fail when auth is canceled before approval`() = test {
        whenever(pubkyService.startAuth()).thenReturn("auth_uri")
        sut.startAuthentication()

        val result = async { sut.completeAuthentication() }
        sut.cancelAuthentication()

        assertTrue(result.await().isFailure)
        verifyBlocking(pubkyService, never()) { completeAuth() }
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
    fun `completeAuthentication should forget session when canceled session revocation fails`() = test {
        whenever(pubkyService.startAuth()).thenReturn("auth_uri")
        whenever(pubkyService.completeAuth()).thenAnswer {
            runBlocking { sut.cancelAuthentication() }
            Unit
        }
        whenever(pubkyService.signOut()).thenAnswer { throw TestAppError("Server error") }

        val authRequest = startAuthForTesting()
        approveAuthForTesting(authRequest)
        val result = sut.completeAuthentication()

        assertTrue(result.isFailure)
        verifyBlocking(pubkyService) { signOut() }
        verifyBlocking(pubkyService) { forgetSessionAccess() }
    }

    @Test
    fun `completeAuthentication clears credentials when abandoned session cleanup fails`() = test {
        whenever(pubkyService.startAuth()).thenReturn("auth_uri")
        whenever(pubkyService.completeAuth()).thenAnswer {
            runBlocking { sut.cancelAuthentication() }
            Unit
        }
        whenever(pubkyService.signOut()).thenAnswer { throw TestAppError("Server error") }
        whenever(pubkyService.forgetSessionAccess()).thenAnswer { throw TestAppError("Cleanup error") }

        val authRequest = startAuthForTesting()
        approveAuthForTesting(authRequest)
        val result = sut.completeAuthentication()

        assertTrue(result.isFailure)
        assertFalse(sut.isAuthenticated.value)
        verify(keychain).delete(Keychain.Key.PAYKIT_SESSION.name)
        verify(keychain).delete(Keychain.Key.PUBKY_SECRET_KEY.name)
    }

    @Test
    fun `completeAuthentication should revoke session when canceled during completion`() = test {
        val completionStarted = CompletableDeferred<Unit>()
        val finishCompletion = CompletableDeferred<Unit>()
        whenever(pubkyService.startAuth()).thenReturn("auth_uri")
        whenever(pubkyService.completeAuth()).doSuspendableAnswer {
            completionStarted.complete(Unit)
            finishCompletion.await()
        }

        val authRequest = startAuthForTesting()
        approveAuthForTesting(authRequest)
        val result = async { sut.completeAuthentication() }
        completionStarted.await()

        result.cancel()
        verifyBlocking(pubkyService, never()) { signOut() }
        finishCompletion.complete(Unit)
        result.join()

        verifyBlocking(pubkyService) { signOut() }
    }

    @Test
    fun `completeAuthentication should keep session when canceled during profile load`() = test {
        val profileLoadStarted = CompletableDeferred<Unit>()
        val finishProfileLoad = CompletableDeferred<Unit>()
        whenever(pubkyService.startAuth()).thenReturn("auth_uri")
        whenever(pubkyService.completeAuth()).thenReturn(Unit)
        whenever(pubkyService.currentPublicKey()).thenReturn(VALID_SELF_KEY.removePrefix("pubky"))
        whenever(pubkyService.resolveContactProfile(VALID_SELF_KEY, true)).doSuspendableAnswer {
            profileLoadStarted.complete(Unit)
            finishProfileLoad.await()
            createResolution(VALID_SELF_KEY, pubkyProfile = createPubkyProfile())
        }

        val authRequest = startAuthForTesting()
        approveAuthForTesting(authRequest)
        val result = async { sut.completeAuthentication() }
        profileLoadStarted.await()

        assertTrue(sut.isAuthenticated.value)
        result.cancel()
        finishProfileLoad.complete(Unit)
        result.join()

        assertTrue(sut.isAuthenticated.value)
        verifyBlocking(pubkyService, never()) { signOut() }
    }

    @Test
    fun `cancelAuthentication should reset state to idle`() = test {
        whenever(pubkyService.startAuth()).thenReturn("auth_uri")
        sut.startAuthentication()

        sut.cancelAuthentication()

        assertFalse(sut.isAuthenticated.value)
    }

    @Test
    fun `cancelAuthentication should keep restored profile authenticated`() = test {
        authenticateForTesting()
        whenever(pubkyService.startAuth()).thenReturn("auth_uri")
        sut.startAuthentication()

        sut.cancelAuthentication()

        assertTrue(sut.isAuthenticated.value)
        assertNotNull(sut.publicKey.value)
    }

    @Test
    fun `handleAuthCallback should reject invalid success nonce`() = test {
        whenever(pubkyService.startAuth()).thenReturn("auth_uri")
        sut.startAuthentication()

        val result = sut.handleAuthCallback(PubkyRingAuthCallback.Success(nonce = "invalid"))

        assertEquals(PubkyRingAuthCallbackHandlingResult.Ignored, result)
        verifyBlocking(pubkyService, never()) { cancelAuth() }
    }

    @Test
    fun `handleAuthCallback should trust missing success nonce for active auth`() = test {
        val testPk = VALID_SELF_KEY.removePrefix("pubky")
        whenever(pubkyService.startAuth()).thenReturn("auth_uri")
        whenever(pubkyService.completeAuth()).thenReturn(Unit)
        whenever(pubkyService.currentPublicKey()).thenReturn(testPk)
        whenever(pubkyService.resolveContactProfile(VALID_SELF_KEY, true))
            .thenReturn(createResolution(VALID_SELF_KEY, pubkyProfile = createPubkyProfile()))
        sut.startAuthentication()

        val callbackResult = sut.handleAuthCallback(PubkyRingAuthCallback.Success(nonce = null))
        val result = sut.completeAuthentication()

        assertEquals(PubkyRingAuthCallbackHandlingResult.Handled, callbackResult)
        assertTrue(result.isSuccess)
        assertTrue(sut.isAuthenticated.value)
    }

    @Test
    fun `handleAuthCallback should ignore invalid cancel nonce`() = test {
        whenever(pubkyService.startAuth()).thenReturn("auth_uri")
        sut.startAuthentication()

        val result = sut.handleAuthCallback(PubkyRingAuthCallback.Cancel(nonce = "invalid"))

        assertEquals(PubkyRingAuthCallbackHandlingResult.Ignored, result)
        assertFalse(sut.isAuthenticated.value)
        verifyBlocking(pubkyService, never()) { cancelAuth() }
    }

    @Test
    fun `handleAuthCallback should ignore invalid error nonce`() = test {
        whenever(pubkyService.startAuth()).thenReturn("auth_uri")
        sut.startAuthentication()

        val result = sut.handleAuthCallback(
            PubkyRingAuthCallback.Error(message = "Forged error", nonce = "invalid"),
        )

        assertEquals(PubkyRingAuthCallbackHandlingResult.Ignored, result)
        verifyBlocking(pubkyService, never()) { cancelAuth() }
    }

    @Test
    fun `handleAuthCallback should keep active auth after missing cancel nonce`() = test {
        val testPk = VALID_SELF_KEY.removePrefix("pubky")
        whenever(pubkyService.startAuth()).thenReturn("auth_uri")
        whenever(pubkyService.completeAuth()).thenReturn(Unit)
        whenever(pubkyService.currentPublicKey()).thenReturn(testPk)
        whenever(pubkyService.resolveContactProfile(VALID_SELF_KEY, true))
            .thenReturn(createResolution(VALID_SELF_KEY, pubkyProfile = createPubkyProfile()))
        val authRequest = startAuthForTesting()

        val callbackResult = sut.handleAuthCallback(PubkyRingAuthCallback.Cancel(nonce = null))
        approveAuthForTesting(authRequest)
        val result = sut.completeAuthentication()

        assertEquals(PubkyRingAuthCallbackHandlingResult.Ignored, callbackResult)
        assertTrue(result.isSuccess)
        assertTrue(sut.isAuthenticated.value)
        verifyBlocking(pubkyService, never()) { cancelAuth() }
    }

    @Test
    fun `handleAuthCallback should keep active auth after missing error nonce`() = test {
        val testPk = VALID_SELF_KEY.removePrefix("pubky")
        whenever(pubkyService.startAuth()).thenReturn("auth_uri")
        whenever(pubkyService.completeAuth()).thenReturn(Unit)
        whenever(pubkyService.currentPublicKey()).thenReturn(testPk)
        whenever(pubkyService.resolveContactProfile(VALID_SELF_KEY, true))
            .thenReturn(createResolution(VALID_SELF_KEY, pubkyProfile = createPubkyProfile()))
        val authRequest = startAuthForTesting()

        val callbackResult = sut.handleAuthCallback(
            PubkyRingAuthCallback.Error(message = "Forged error", nonce = null),
        )
        approveAuthForTesting(authRequest)
        val result = sut.completeAuthentication()

        assertEquals(PubkyRingAuthCallbackHandlingResult.Ignored, callbackResult)
        assertTrue(result.isSuccess)
        assertTrue(sut.isAuthenticated.value)
        verifyBlocking(pubkyService, never()) { cancelAuth() }
    }

    @Test
    fun `handleAuthCallback should keep active auth after invalid cancel nonce`() = test {
        val testPk = VALID_SELF_KEY.removePrefix("pubky")
        whenever(pubkyService.startAuth()).thenReturn("auth_uri")
        whenever(pubkyService.completeAuth()).thenReturn(Unit)
        whenever(pubkyService.currentPublicKey()).thenReturn(testPk)
        whenever(pubkyService.resolveContactProfile(VALID_SELF_KEY, true))
            .thenReturn(createResolution(VALID_SELF_KEY, pubkyProfile = createPubkyProfile()))
        val authRequest = startAuthForTesting()

        val callbackResult = sut.handleAuthCallback(PubkyRingAuthCallback.Cancel(nonce = "invalid"))
        approveAuthForTesting(authRequest)
        val result = sut.completeAuthentication()

        assertEquals(PubkyRingAuthCallbackHandlingResult.Ignored, callbackResult)
        assertTrue(result.isSuccess)
        assertTrue(sut.isAuthenticated.value)
        verifyBlocking(pubkyService, never()) { cancelAuth() }
    }

    @Test
    fun `handleAuthCallback should keep active auth after invalid error nonce`() = test {
        val testPk = VALID_SELF_KEY.removePrefix("pubky")
        whenever(pubkyService.startAuth()).thenReturn("auth_uri")
        whenever(pubkyService.completeAuth()).thenReturn(Unit)
        whenever(pubkyService.currentPublicKey()).thenReturn(testPk)
        whenever(pubkyService.resolveContactProfile(VALID_SELF_KEY, true))
            .thenReturn(createResolution(VALID_SELF_KEY, pubkyProfile = createPubkyProfile()))
        val authRequest = startAuthForTesting()

        val callbackResult = sut.handleAuthCallback(
            PubkyRingAuthCallback.Error(message = "Forged error", nonce = "invalid"),
        )
        approveAuthForTesting(authRequest)
        val result = sut.completeAuthentication()

        assertEquals(PubkyRingAuthCallbackHandlingResult.Ignored, callbackResult)
        assertTrue(result.isSuccess)
        assertTrue(sut.isAuthenticated.value)
        verifyBlocking(pubkyService, never()) { cancelAuth() }
    }

    @Test
    fun `handleAuthCallback should trust matching error nonce`() = test {
        whenever(pubkyService.startAuth()).thenReturn("auth_uri")
        val authRequest = checkNotNull(sut.startAuthentication().getOrNull()) {
            "Auth request should be returned"
        }

        val result = sut.handleAuthCallback(
            PubkyRingAuthCallback.Error(message = "Ring failed", nonce = authRequest.callbackNonce),
        )

        assertEquals(PubkyRingAuthCallbackHandlingResult.TrustedError("Ring failed"), result)
        verifyBlocking(pubkyService) { cancelAuth() }
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
    fun `loadProfile should return early when no public key`() = test {
        sut.loadProfile()

        verify(pubkyService, never()).resolveContactProfile(any(), any())
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
        val pk = checkNotNull(sut.publicKey.value)
        val pubkyProfile = createPubkyProfile(name = "Test", image = "pubky://image_uri")
        whenever(pubkyService.resolveContactProfile(pk, true))
            .thenReturn(createResolution(pk, pubkyProfile = pubkyProfile))
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
        whenever(pubkyService.resolveContactProfile(pendingContactKey, true))
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
    fun `restoreSessionBackupState should save external session backups`() = test {
        whenever(pubkyService.importExternalSession("external_session")).thenReturn(VALID_SELF_KEY)

        val result = sut.restoreSessionBackupState(
            PubkySessionBackupV1(
                kind = PubkySessionBackupKind.ExternalSession,
                sessionSecret = "external_session",
            ),
        )

        assertTrue(result.isSuccess)
        assertEquals(VALID_SELF_KEY, sut.publicKey.value)
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
    fun `restoreSessionBackupState should import external session when forgetting current session fails`() = test {
        whenever(pubkyService.forgetSessionAccess()).thenAnswer { throw TestAppError("Forget failed") }
        whenever(pubkyService.importExternalSession("external_session")).thenReturn(VALID_SELF_KEY)

        val result = sut.restoreSessionBackupState(
            PubkySessionBackupV1(
                kind = PubkySessionBackupKind.ExternalSession,
                sessionSecret = "external_session",
            ),
        )

        assertTrue(result.isSuccess)
        assertEquals(VALID_SELF_KEY, sut.publicKey.value)
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
        whenever(pubkyService.completeAuth()).thenReturn(Unit)
        whenever(pubkyService.currentPublicKey()).thenReturn(newPublicKey)
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn(newSecret)
        whenever(pubkyService.contactRecords()).thenReturn(emptyList())
        val staleProfile = createPubkyProfile(name = "Stale Old")
        whenever(pubkyService.resolveContactProfile(oldPublicKey.ensurePubkyPrefixForTest(), true)).thenAnswer {
            runBlocking {
                approveAuthForTesting(startAuthForTesting())
                sut.completeAuthentication()
            }
            createResolution(oldPublicKey.ensurePubkyPrefixForTest(), pubkyProfile = staleProfile)
        }

        sut.loadProfile()

        assertEquals(newPublicKey.ensurePubkyPrefixForTest(), sut.publicKey.value)
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

        authenticateForTesting(
            publicKey = oldPublicKey,
            secret = oldSecret,
            profileName = "Initial Old",
        )
        sut.addContact(existingContact.publicKey, existingProfile = existingContact)

        whenever(pubkyService.completeAuth()).thenReturn(Unit)
        whenever(pubkyService.currentPublicKey()).thenReturn(newPublicKey)
        val newProfile = createPubkyProfile(name = "New User")
        whenever(pubkyService.resolveContactProfile(newPublicKey.ensurePubkyPrefixForTest(), true))
            .thenReturn(createResolution(newPublicKey.ensurePubkyPrefixForTest(), pubkyProfile = newProfile))
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn(oldSecret)
        whenever(pubkyService.contactRecords()).thenAnswer {
            runBlocking {
                approveAuthForTesting(startAuthForTesting())
                sut.completeAuthentication()
            }
            listOf(createContactRecord(staleContactKey, profile = createPaykitProfile("Stale Contact")))
        }

        sut.loadContacts()

        val contacts = sut.contacts.value
        assertEquals(newPublicKey.ensurePubkyPrefixForTest(), sut.publicKey.value)
        assertEquals(1, contacts.size)
        assertEquals(existingContact.publicKey, contacts.first().publicKey)
        assertEquals(existingContact.name, contacts.first().name)
    }

    @Test
    fun `loadContacts should return early when no public key`() = test {
        sut.loadContacts()

        verify(pubkyService, never()).contactRecords()
    }

    @Test
    fun `loadContacts should use placeholder when profile fetch fails`() = test {
        authenticateForTesting()
        val contactKey = "pubkycontact2"
        whenever(pubkyService.contactRecords()).thenReturn(listOf(createContactRecord(contactKey)))
        whenever(pubkyService.resolveContactProfile(contactKey, true))
            .thenAnswer { throw TestAppError("Network error") }

        sut.loadContacts()

        val contacts = sut.contacts.value
        assertEquals(1, contacts.size)
        assertEquals(contactKey, contacts.first().publicKey)
        assertFalse(sut.isLoadingContacts.value)
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
        whenever(pubkyService.resolveContactProfile(pendingContactKey, true))
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
        whenever(pubkyService.resolveContactProfile(contactKey, true)).thenReturn(null)

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
        whenever { pubkyService.completeAuth() }.thenReturn(Unit)
        whenever { pubkyService.currentPublicKey() }.thenReturn(publicKey)
        val pubkyProfile = createPubkyProfile(name = profileName)
        whenever { pubkyService.resolveContactProfile(prefixedPublicKey, true) }
            .thenReturn(createResolution(prefixedPublicKey, pubkyProfile = pubkyProfile))
        whenever(keychain.loadString(Keychain.Key.PAYKIT_SESSION.name)).thenReturn(secret)
        whenever { pubkyService.contactRecords() }.thenReturn(emptyList())

        approveAuthForTesting(startAuthForTesting())
        sut.completeAuthentication()
    }

    private suspend fun startAuthForTesting(authUri: String = "auth_uri"): PubkyRingAuthRequest {
        whenever { pubkyService.startAuth() }.thenReturn(authUri)
        return checkNotNull(sut.startAuthentication().getOrNull()) {
            "Auth request should be returned"
        }
    }

    private suspend fun approveAuthForTesting(authRequest: PubkyRingAuthRequest) {
        sut.handleAuthCallback(PubkyRingAuthCallback.Success(nonce = authRequest.callbackNonce))
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

    private suspend fun stubSignupKeys() {
        whenever(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn("seed words")
        whenever(pubkyService.deriveSecretKey("seed words")).thenReturn("secret")
        whenever(pubkyService.publicKeyFromSecret("secret")).thenReturn(VALID_SELF_KEY)
    }

    private fun ringSignupRequest() = PubkyAuthRequest.parseRingSignup(
        "pubkyring://signup?hs=homeserver&relay=https%3A%2F%2Frelay.example" +
            "&secret=request&caps=%2Fpub%2Fexample%2F%3Arw&st=invite",
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

private fun String.ensurePubkyPrefixForTest(): String =
    if (startsWith("pubky")) this else "pubky$this"

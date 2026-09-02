package to.bitkit.ui.screens.profile

import android.content.Context
import com.synonym.paykit.PubkyAuthCompanionClaimApprovalException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.models.PreparedWatchOnlyAccountClaim
import to.bitkit.models.PubkyAuthClaim
import to.bitkit.models.PubkyAuthPermission
import to.bitkit.models.PubkyAuthRequest
import to.bitkit.models.PubkyProfile
import to.bitkit.models.WatchOnlyAccountRecord
import to.bitkit.models.WatchOnlyAccountSetupState
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.WatchOnlyAccountAuthorizationStartError
import to.bitkit.repositories.WatchOnlyAccountRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class PubkyAuthApprovalViewModelTest : BaseUnitTest() {
    private val clientId = "paykit.test"
    private val context: Context = mock()
    private val profileFlow = MutableStateFlow<PubkyProfile?>(null)
    private val publicKeyFlow = MutableStateFlow<String?>(null)
    private val displayNameFlow = MutableStateFlow<String?>(null)
    private val displayImageUriFlow = MutableStateFlow<String?>(null)
    private val pubkyRepo: PubkyRepo = mock {
        on { profile } doReturn profileFlow
        on { publicKey } doReturn publicKeyFlow
        on { displayName } doReturn displayNameFlow
        on { displayImageUri } doReturn displayImageUriFlow
    }
    private val watchOnlyAccountRepo: WatchOnlyAccountRepo = mock()

    @Before
    fun setUp() {
        whenever(context.getString(R.string.profile__auth_approval_service_unknown)).thenReturn("Unknown service")
        whenever(context.getString(R.string.profile__auth_error_title)).thenReturn("Authorization failed")
        whenever(
            context.getString(R.string.profile__auth_approval_watch_only_account_default_name, "paykit")
        ).thenReturn("paykit server")
    }

    @Test
    fun `initial state is loading`() {
        val sut = createSut()

        assertEquals(ApprovalState.Loading, sut.uiState.value.state)
    }

    @Test
    fun `auth display public key omits pubky prefix`() {
        assertEquals("3rsd...w5xg", pubkyAuthDisplayPublicKey("pubky3rsd123456789w5xg"))
        assertEquals("3rsd...w5xg", pubkyAuthDisplayPublicKey("3rsd123456789w5xg"))
    }

    @Test
    fun `confirmAuthorize is ignored when load has not completed`() = test {
        val authUrl = "pubkyauth://signin?caps=/pub/bitkit.to/:rw"
        val capabilities = "/pub/bitkit.to/:rw"
        val sut = createSut()

        sut.confirmAuthorize(authUrl)
        advanceUntilIdle()

        assertEquals(ApprovalState.Loading, sut.uiState.value.state)
        verifyBlocking(pubkyRepo, never()) { parseAuthUrl(authUrl) }
        verifyBlocking(pubkyRepo, never()) { approveAuth(authUrl, capabilities, clientId) }
    }

    @Test
    fun `confirmAuthorize ignores a stale auth URL after another request loads`() = test {
        val staleAuthUrl = "pubkyauth://signin?caps=/pub/stale/:rw"
        val currentAuthUrl = "pubkyauth://signin?caps=/pub/current/:rw"
        whenever { pubkyRepo.parseAuthUrl(currentAuthUrl) }.thenReturn(
            Result.success(authRequest(currentAuthUrl, "/pub/current/:rw")),
        )
        val sut = createSut()

        sut.load(currentAuthUrl)
        advanceUntilIdle()
        sut.confirmAuthorize(staleAuthUrl)
        advanceUntilIdle()

        assertEquals(currentAuthUrl, sut.uiState.value.authUrl)
        assertEquals(ApprovalState.Authorize, sut.uiState.value.state)
        verifyBlocking(pubkyRepo, never()) { parseAuthUrl(staleAuthUrl) }
        verifyBlocking(pubkyRepo, never()) { approveAuth(staleAuthUrl, "/pub/current/:rw", clientId) }
    }

    @Test
    fun `confirmAuthorize reparses the current URL and fails closed when it changes`() = test {
        val authUrl = "pubkyauth://signin?caps=/pub/current/:rw"
        val capabilities = "/pub/current/:rw"
        whenever { pubkyRepo.parseAuthUrl(authUrl) }.thenReturn(
            Result.success(authRequest(authUrl, capabilities)),
            Result.failure(IllegalArgumentException("request changed")),
        )
        val sut = createSut()

        sut.load(authUrl)
        advanceUntilIdle()
        sut.confirmAuthorize(authUrl)
        advanceUntilIdle()

        assertEquals(ApprovalState.Authorize, sut.uiState.value.state)
        verifyBlocking(pubkyRepo, times(2)) { parseAuthUrl(authUrl) }
        verifyBlocking(pubkyRepo, never()) { approveAuth(authUrl, capabilities, clientId) }
    }

    @Test
    fun `ordinary authorization uses the requested capabilities`() = test {
        val authUrl = "pubkyauth://signin?caps=/pub/example/:rw"
        val capabilities = "/pub/example/:rw"
        whenever { pubkyRepo.parseAuthUrl(authUrl) }.thenReturn(
            Result.success(authRequest(authUrl, capabilities)),
        )
        whenever { pubkyRepo.approveAuth(authUrl, capabilities, clientId) }.thenReturn(Result.success(Unit))
        val sut = createSut()

        sut.load(authUrl)
        advanceUntilIdle()
        sut.confirmAuthorize(authUrl)
        advanceUntilIdle()

        assertEquals(ApprovalState.Success, sut.uiState.value.state)
        assertEquals(clientId, sut.uiState.value.clientId)
        verifyBlocking(pubkyRepo) { approveAuth(authUrl, capabilities, clientId) }
        verifyBlocking(pubkyRepo, never()) { approveAuthWithCompanionClaim(any(), any(), any()) }
        verifyBlocking(watchOnlyAccountRepo, never()) { prepareUnsignedClaim(any(), any()) }
    }

    @Test
    fun `Ring signup delegates registration and authorization to Pubky repository`() = test {
        val authUrl = "pubkyring://signup?hs=homeserver"
        val request = authRequest(
            authUrl = authUrl,
            capabilities = "/pub/example/:rw",
        )
        whenever { pubkyRepo.parseAuthUrl(authUrl) }.thenReturn(Result.success(request))
        whenever { pubkyRepo.approveSignupAuth(request) }.thenReturn(Result.success(Unit))
        val sut = createSut()

        sut.load(authUrl)
        advanceUntilIdle()
        sut.confirmAuthorize(authUrl)
        advanceUntilIdle()

        verifyBlocking(pubkyRepo) { approveSignupAuth(request) }
        verifyBlocking(pubkyRepo, never()) { approveAuth(any(), any(), any()) }
    }

    @Test
    fun `load exposes watch-only account claim for approval`() = test {
        val authUrl = "pubkyauth://signin?caps=${PubkyAuthClaim.WATCH_ONLY_ACCOUNT_CAPABILITIES}"
        whenever { pubkyRepo.parseAuthUrl(authUrl) }.thenReturn(
            Result.success(
                authRequest(
                    authUrl = authUrl,
                    capabilities = PubkyAuthClaim.WATCH_ONLY_ACCOUNT_CAPABILITIES,
                    bitkitClaim = PubkyAuthClaim.WATCH_ONLY_ACCOUNT_V1,
                ),
            ),
        )
        val sut = createSut()

        sut.load(authUrl)
        advanceUntilIdle()

        assertEquals(ApprovalState.WatchOnlyConsent, sut.uiState.value.state)
        assertEquals(PubkyAuthClaim.WATCH_ONLY_ACCOUNT_V1, sut.uiState.value.bitkitClaim)

        sut.confirmAuthorize(authUrl)
        advanceUntilIdle()
        assertEquals(ApprovalState.WatchOnlyConsent, sut.uiState.value.state)

        sut.approveWatchOnlyConsent(authUrl)
        assertEquals(ApprovalState.Authorize, sut.uiState.value.state)

        sut.requestAuthorize(authUrl)
        runCurrent()
        assertEquals(ApprovalState.Authenticating, sut.uiState.value.state)

        sut.cancelLocalAuth(authUrl)
        assertEquals(ApprovalState.Authorize, sut.uiState.value.state)

        sut.returnToWatchOnlyConsent(authUrl)
        assertEquals(ApprovalState.WatchOnlyConsent, sut.uiState.value.state)
    }

    @Test
    fun `watch-only authorization uses combined companion approval`() = test {
        val authUrl = "pubkyauth://signin?secret=request&caps=${PubkyAuthClaim.WATCH_ONLY_ACCOUNT_CAPABILITIES}"
        val capabilities = PubkyAuthClaim.WATCH_ONLY_ACCOUNT_CAPABILITIES
        val prepared = PreparedWatchOnlyAccountClaim(
            account = watchOnlyAccount(),
            payload = ByteArray(84),
        )
        whenever { pubkyRepo.parseAuthUrl(authUrl) }.thenReturn(
            Result.success(authRequest(authUrl, capabilities, PubkyAuthClaim.WATCH_ONLY_ACCOUNT_V1)),
        )
        whenever { watchOnlyAccountRepo.prepareUnsignedClaim(authUrl, "paykit server") }.thenReturn(prepared)
        whenever { watchOnlyAccountRepo.beginAuthorization(prepared.account.id) }.thenReturn(false)
        whenever {
            pubkyRepo.approveAuthWithCompanionClaim(authUrl, clientId, prepared.payload)
        }.thenReturn(Result.success(Unit))
        whenever { watchOnlyAccountRepo.markActive(prepared.account.id) }.thenReturn(Unit)
        val sut = createSut()

        sut.load(authUrl)
        advanceUntilIdle()
        sut.approveWatchOnlyConsent(authUrl)
        sut.confirmAuthorize(authUrl)
        advanceUntilIdle()

        assertEquals(ApprovalState.Success, sut.uiState.value.state)
        verifyBlocking(watchOnlyAccountRepo) { prepareUnsignedClaim(authUrl, "paykit server") }
        verifyBlocking(pubkyRepo) { approveAuthWithCompanionClaim(authUrl, clientId, prepared.payload) }
        verifyBlocking(pubkyRepo, times(2)) { parseAuthUrl(authUrl) }
        verifyBlocking(pubkyRepo, never()) { approveAuth(authUrl, capabilities, clientId) }
        verifyBlocking(watchOnlyAccountRepo) { beginAuthorization(prepared.account.id) }
        verifyBlocking(watchOnlyAccountRepo) { markActive(prepared.account.id) }
    }

    @Test
    fun `duplicate confirmations start one companion authorization`() = test {
        val authUrl = "pubkyauth://signin?secret=request&caps=${PubkyAuthClaim.WATCH_ONLY_ACCOUNT_CAPABILITIES}"
        val capabilities = PubkyAuthClaim.WATCH_ONLY_ACCOUNT_CAPABILITIES
        val prepared = PreparedWatchOnlyAccountClaim(
            account = watchOnlyAccount(),
            payload = ByteArray(84),
        )
        whenever { pubkyRepo.parseAuthUrl(authUrl) }.thenReturn(
            Result.success(authRequest(authUrl, capabilities, PubkyAuthClaim.WATCH_ONLY_ACCOUNT_V1)),
        )
        whenever { watchOnlyAccountRepo.prepareUnsignedClaim(authUrl, "paykit server") }.thenReturn(prepared)
        whenever { watchOnlyAccountRepo.beginAuthorization(prepared.account.id) }.thenReturn(false)
        whenever {
            pubkyRepo.approveAuthWithCompanionClaim(authUrl, clientId, prepared.payload)
        }.thenReturn(Result.success(Unit))
        whenever { watchOnlyAccountRepo.markActive(prepared.account.id) }.thenReturn(Unit)
        val sut = createSut()

        sut.load(authUrl)
        advanceUntilIdle()
        sut.approveWatchOnlyConsent(authUrl)
        sut.confirmAuthorize(authUrl)
        sut.confirmAuthorize(authUrl)
        advanceUntilIdle()

        assertEquals(ApprovalState.Success, sut.uiState.value.state)
        verifyBlocking(watchOnlyAccountRepo, times(1)) { prepareUnsignedClaim(authUrl, "paykit server") }
        verifyBlocking(watchOnlyAccountRepo, times(1)) { beginAuthorization(prepared.account.id) }
        verifyBlocking(pubkyRepo, times(1)) { approveAuthWithCompanionClaim(authUrl, clientId, prepared.payload) }
        verifyBlocking(watchOnlyAccountRepo, times(1)) { markActive(prepared.account.id) }
    }

    @Test
    fun `switching requests and reopening during companion approval does not start another authorization`() = test {
        val authUrl = "pubkyauth://signin?secret=request&caps=${PubkyAuthClaim.WATCH_ONLY_ACCOUNT_CAPABILITIES}"
        val capabilities = PubkyAuthClaim.WATCH_ONLY_ACCOUNT_CAPABILITIES
        val secondAuthUrl = "pubkyauth://signin?caps=/pub/second/:rw"
        val secondCapabilities = "/pub/second/:rw"
        val prepared = PreparedWatchOnlyAccountClaim(
            account = watchOnlyAccount(),
            payload = ByteArray(84),
        )
        val approvalResult = CompletableDeferred<Result<Unit>>()
        whenever { pubkyRepo.parseAuthUrl(authUrl) }.thenReturn(
            Result.success(authRequest(authUrl, capabilities, PubkyAuthClaim.WATCH_ONLY_ACCOUNT_V1)),
        )
        whenever { pubkyRepo.parseAuthUrl(secondAuthUrl) }.thenReturn(
            Result.success(authRequest(secondAuthUrl, secondCapabilities)),
        )
        whenever { watchOnlyAccountRepo.prepareUnsignedClaim(authUrl, "paykit server") }.thenReturn(prepared)
        whenever { watchOnlyAccountRepo.beginAuthorization(prepared.account.id) }.thenReturn(false)
        whenever { pubkyRepo.approveAuthWithCompanionClaim(authUrl, clientId, prepared.payload) }
            .doSuspendableAnswer { approvalResult.await() }
        whenever { watchOnlyAccountRepo.markActive(prepared.account.id) }.thenReturn(Unit)
        val sut = createSut()

        sut.load(authUrl)
        advanceUntilIdle()
        sut.approveWatchOnlyConsent(authUrl)
        sut.confirmAuthorize(authUrl)
        runCurrent()
        assertEquals(ApprovalState.Authorizing, sut.uiState.value.state)

        sut.load(secondAuthUrl)
        advanceUntilIdle()
        assertEquals(secondAuthUrl, sut.uiState.value.authUrl)
        assertEquals(ApprovalState.Authorize, sut.uiState.value.state)
        sut.confirmAuthorize(secondAuthUrl)
        runCurrent()
        assertEquals(ApprovalState.Authorize, sut.uiState.value.state)

        sut.load(authUrl)
        sut.confirmAuthorize(authUrl)
        runCurrent()
        assertEquals(authUrl, sut.uiState.value.authUrl)
        assertEquals(ApprovalState.Authorizing, sut.uiState.value.state)
        approvalResult.complete(Result.success(Unit))
        advanceUntilIdle()

        assertEquals(ApprovalState.Success, sut.uiState.value.state)
        verifyBlocking(pubkyRepo, times(2)) { parseAuthUrl(authUrl) }
        verifyBlocking(pubkyRepo, times(1)) { parseAuthUrl(secondAuthUrl) }
        verifyBlocking(watchOnlyAccountRepo, times(1)) { prepareUnsignedClaim(authUrl, "paykit server") }
        verifyBlocking(watchOnlyAccountRepo, times(1)) { beginAuthorization(prepared.account.id) }
        verifyBlocking(pubkyRepo, times(1)) { approveAuthWithCompanionClaim(authUrl, clientId, prepared.payload) }
        verifyBlocking(pubkyRepo, never()) { approveAuth(secondAuthUrl, secondCapabilities, clientId) }
        verifyBlocking(watchOnlyAccountRepo, times(1)) { markActive(prepared.account.id) }
    }

    @Test
    fun `wrapped post-delivery authorization failure keeps account authorizing for retry`() = test {
        val authUrl = "pubkyauth://signin?secret=request&caps=${PubkyAuthClaim.WATCH_ONLY_ACCOUNT_CAPABILITIES}"
        val capabilities = PubkyAuthClaim.WATCH_ONLY_ACCOUNT_CAPABILITIES
        val prepared = PreparedWatchOnlyAccountClaim(
            account = watchOnlyAccount(),
            payload = ByteArray(84),
        )
        val authorizationError = PubkyAuthCompanionClaimApprovalException.AuthorizationFailure(
            "AuthToken delivery failed"
        )
        whenever { pubkyRepo.parseAuthUrl(authUrl) }.thenReturn(
            Result.success(authRequest(authUrl, capabilities, PubkyAuthClaim.WATCH_ONLY_ACCOUNT_V1)),
        )
        whenever { watchOnlyAccountRepo.prepareUnsignedClaim(authUrl, "paykit server") }.thenReturn(prepared)
        whenever { watchOnlyAccountRepo.beginAuthorization(prepared.account.id) }.thenReturn(false)
        whenever { pubkyRepo.approveAuthWithCompanionClaim(authUrl, clientId, prepared.payload) }
            .thenReturn(Result.failure(AppError(authorizationError)))
        val sut = createSut()

        sut.load(authUrl)
        advanceUntilIdle()
        sut.approveWatchOnlyConsent(authUrl)
        sut.confirmAuthorize(authUrl)
        advanceUntilIdle()

        assertEquals(ApprovalState.Authorize, sut.uiState.value.state)
        verifyBlocking(watchOnlyAccountRepo) { beginAuthorization(prepared.account.id) }
        verifyBlocking(watchOnlyAccountRepo, never()) { cancelAuthorization(prepared.account.id) }
        verifyBlocking(watchOnlyAccountRepo, never()) { markActive(prepared.account.id) }
    }

    @Test
    fun `companion delivery failure does not approve normal auth or activate account`() = test {
        val authUrl = "pubkyauth://signin?secret=request&caps=${PubkyAuthClaim.WATCH_ONLY_ACCOUNT_CAPABILITIES}"
        val capabilities = PubkyAuthClaim.WATCH_ONLY_ACCOUNT_CAPABILITIES
        val prepared = PreparedWatchOnlyAccountClaim(
            account = watchOnlyAccount(),
            payload = ByteArray(84),
        )
        whenever { pubkyRepo.parseAuthUrl(authUrl) }.thenReturn(
            Result.success(authRequest(authUrl, capabilities, PubkyAuthClaim.WATCH_ONLY_ACCOUNT_V1)),
        )
        whenever { watchOnlyAccountRepo.prepareUnsignedClaim(authUrl, "paykit server") }.thenReturn(prepared)
        whenever { watchOnlyAccountRepo.beginAuthorization(prepared.account.id) }.thenReturn(false)
        whenever { pubkyRepo.approveAuthWithCompanionClaim(authUrl, clientId, prepared.payload) }
            .thenReturn(Result.failure(IllegalStateException("Relay delivery failed")))
        val sut = createSut()

        sut.load(authUrl)
        advanceUntilIdle()
        sut.approveWatchOnlyConsent(authUrl)
        sut.confirmAuthorize(authUrl)
        advanceUntilIdle()

        assertEquals(ApprovalState.Authorize, sut.uiState.value.state)
        verifyBlocking(pubkyRepo, never()) { approveAuth(authUrl, capabilities, clientId) }
        verifyBlocking(watchOnlyAccountRepo) { beginAuthorization(prepared.account.id) }
        verifyBlocking(watchOnlyAccountRepo) { cancelAuthorization(prepared.account.id) }
        verifyBlocking(watchOnlyAccountRepo, never()) { markActive(prepared.account.id) }
    }

    @Test
    fun `retry delivery failure keeps a previously delivered account authorizing`() = test {
        val authUrl = "pubkyauth://signin?secret=request&caps=${PubkyAuthClaim.WATCH_ONLY_ACCOUNT_CAPABILITIES}"
        val capabilities = PubkyAuthClaim.WATCH_ONLY_ACCOUNT_CAPABILITIES
        val prepared = PreparedWatchOnlyAccountClaim(
            account = watchOnlyAccount().copy(
                isTrackingEnabled = true,
                setupState = WatchOnlyAccountSetupState.Authorizing,
            ),
            payload = ByteArray(84),
        )
        whenever { pubkyRepo.parseAuthUrl(authUrl) }.thenReturn(
            Result.success(authRequest(authUrl, capabilities, PubkyAuthClaim.WATCH_ONLY_ACCOUNT_V1)),
        )
        whenever { watchOnlyAccountRepo.prepareUnsignedClaim(authUrl, "paykit server") }.thenReturn(prepared)
        whenever { watchOnlyAccountRepo.beginAuthorization(prepared.account.id) }.thenReturn(true)
        whenever { pubkyRepo.approveAuthWithCompanionClaim(authUrl, clientId, prepared.payload) }
            .thenReturn(Result.failure(IllegalStateException("Relay delivery failed")))
        val sut = createSut()

        sut.load(authUrl)
        advanceUntilIdle()
        sut.approveWatchOnlyConsent(authUrl)
        sut.confirmAuthorize(authUrl)
        advanceUntilIdle()

        assertEquals(ApprovalState.Authorize, sut.uiState.value.state)
        verifyBlocking(watchOnlyAccountRepo) { beginAuthorization(prepared.account.id) }
        verifyBlocking(watchOnlyAccountRepo) {
            cancelAuthorization(prepared.account.id, preserveAuthorizingState = true)
        }
        verifyBlocking(watchOnlyAccountRepo, never()) { markActive(prepared.account.id) }
    }

    @Test
    fun `tracking preparation failure unloads account without attempting approval`() = test {
        val authUrl = "pubkyauth://signin?secret=request&caps=${PubkyAuthClaim.WATCH_ONLY_ACCOUNT_CAPABILITIES}"
        val prepared = PreparedWatchOnlyAccountClaim(
            account = watchOnlyAccount(),
            payload = ByteArray(84),
        )
        whenever { pubkyRepo.parseAuthUrl(authUrl) }.thenReturn(
            Result.success(
                authRequest(
                    authUrl,
                    PubkyAuthClaim.WATCH_ONLY_ACCOUNT_CAPABILITIES,
                    PubkyAuthClaim.WATCH_ONLY_ACCOUNT_V1,
                ),
            ),
        )
        whenever { watchOnlyAccountRepo.prepareUnsignedClaim(authUrl, "paykit server") }.thenReturn(prepared)
        whenever { watchOnlyAccountRepo.beginAuthorization(prepared.account.id) }
            .thenThrow(IllegalStateException("Wallet sync failed"))
        val sut = createSut()

        sut.load(authUrl)
        advanceUntilIdle()
        sut.approveWatchOnlyConsent(authUrl)
        sut.confirmAuthorize(authUrl)
        advanceUntilIdle()

        assertEquals(ApprovalState.Authorize, sut.uiState.value.state)
        verifyBlocking(watchOnlyAccountRepo) { cancelAuthorization(prepared.account.id) }
        verifyBlocking(pubkyRepo, never()) { approveAuthWithCompanionClaim(authUrl, clientId, prepared.payload) }
        verifyBlocking(watchOnlyAccountRepo, never()) { markActive(prepared.account.id) }
    }

    @Test
    fun `tracking failure uses the current authorizing disposition`() = test {
        val authUrl = "pubkyauth://signin?secret=request&caps=${PubkyAuthClaim.WATCH_ONLY_ACCOUNT_CAPABILITIES}"
        val prepared = PreparedWatchOnlyAccountClaim(
            account = watchOnlyAccount(),
            payload = ByteArray(84),
        )
        whenever { pubkyRepo.parseAuthUrl(authUrl) }.thenReturn(
            Result.success(
                authRequest(
                    authUrl,
                    PubkyAuthClaim.WATCH_ONLY_ACCOUNT_CAPABILITIES,
                    PubkyAuthClaim.WATCH_ONLY_ACCOUNT_V1,
                ),
            ),
        )
        whenever { watchOnlyAccountRepo.prepareUnsignedClaim(authUrl, "paykit server") }.thenReturn(prepared)
        whenever { watchOnlyAccountRepo.beginAuthorization(prepared.account.id) }.doSuspendableAnswer {
            throw WatchOnlyAccountAuthorizationStartError(
                preserveAuthorizingState = true,
                cause = IllegalStateException("Wallet sync failed"),
            )
        }
        val sut = createSut()

        sut.load(authUrl)
        advanceUntilIdle()
        sut.approveWatchOnlyConsent(authUrl)
        sut.confirmAuthorize(authUrl)
        advanceUntilIdle()

        assertEquals(ApprovalState.Authorize, sut.uiState.value.state)
        verifyBlocking(watchOnlyAccountRepo) {
            cancelAuthorization(prepared.account.id, preserveAuthorizingState = true)
        }
        verifyBlocking(pubkyRepo, never()) { approveAuthWithCompanionClaim(authUrl, clientId, prepared.payload) }
    }

    @Test
    fun `retry after process restart reuses account and repeats authorization`() = test {
        val authUrl = "pubkyauth://signin?secret=request&caps=${PubkyAuthClaim.WATCH_ONLY_ACCOUNT_CAPABILITIES}"
        val prepared = PreparedWatchOnlyAccountClaim(
            account = watchOnlyAccount(),
            payload = ByteArray(84),
        )
        val retryPrepared = prepared.copy(
            account = prepared.account.copy(
                isTrackingEnabled = true,
                setupState = WatchOnlyAccountSetupState.Authorizing,
            ),
        )
        whenever { pubkyRepo.parseAuthUrl(authUrl) }.thenReturn(
            Result.success(
                authRequest(
                    authUrl,
                    PubkyAuthClaim.WATCH_ONLY_ACCOUNT_CAPABILITIES,
                    PubkyAuthClaim.WATCH_ONLY_ACCOUNT_V1,
                ),
            ),
        )
        whenever { watchOnlyAccountRepo.prepareUnsignedClaim(authUrl, "paykit server") }
            .thenReturn(prepared, retryPrepared)
        whenever { watchOnlyAccountRepo.beginAuthorization(prepared.account.id) }.thenReturn(false, true)
        whenever {
            pubkyRepo.approveAuthWithCompanionClaim(authUrl, clientId, prepared.payload)
        }.thenReturn(Result.success(Unit))
        whenever { watchOnlyAccountRepo.markActive(prepared.account.id) }
            .thenThrow(IllegalStateException("Persistence failed"))
            .thenReturn(Unit)
        val initialSut = createSut()

        initialSut.load(authUrl)
        advanceUntilIdle()
        initialSut.approveWatchOnlyConsent(authUrl)
        initialSut.confirmAuthorize(authUrl)
        advanceUntilIdle()

        assertEquals(ApprovalState.Authorize, initialSut.uiState.value.state)
        verifyBlocking(watchOnlyAccountRepo) { beginAuthorization(prepared.account.id) }
        verifyBlocking(watchOnlyAccountRepo, never()) { cancelAuthorization(prepared.account.id) }

        val restartedSut = createSut()
        restartedSut.load(authUrl)
        advanceUntilIdle()
        restartedSut.approveWatchOnlyConsent(authUrl)
        restartedSut.confirmAuthorize(authUrl)
        advanceUntilIdle()

        assertEquals(ApprovalState.Success, restartedSut.uiState.value.state)
        verifyBlocking(watchOnlyAccountRepo, times(2)) { prepareUnsignedClaim(authUrl, "paykit server") }
        verifyBlocking(watchOnlyAccountRepo, times(2)) { beginAuthorization(prepared.account.id) }
        verifyBlocking(pubkyRepo, times(2)) { approveAuthWithCompanionClaim(authUrl, clientId, prepared.payload) }
        verifyBlocking(watchOnlyAccountRepo, times(2)) { markActive(prepared.account.id) }
    }

    private fun createSut() = PubkyAuthApprovalViewModel(
        context = context,
        pubkyRepo = pubkyRepo,
        watchOnlyAccountRepo = watchOnlyAccountRepo,
    )

    private fun authRequest(
        authUrl: String,
        capabilities: String,
        bitkitClaim: PubkyAuthClaim? = null,
        clientId: String = this.clientId,
    ) = PubkyAuthRequest(
        rawUrl = authUrl,
        clientId = clientId,
        relay = "https://httprelay.pubky.app/inbox/",
        capabilities = capabilities,
        permissions = listOf(PubkyAuthPermission(path = "/pub/paykit/v0/bitkit/server/", accessLevel = "rw")),
        serviceNames = listOf("paykit"),
        bitkitClaim = bitkitClaim,
        homeserverPublicKey = if (PubkyAuthRequest.isSignupUrl(authUrl)) "homeserver" else null,
    )

    private fun watchOnlyAccount() = WatchOnlyAccountRecord(
        id = "account-id",
        walletIndex = 0,
        accountIndex = 1,
        addressType = "nativeSegwit",
        xpub = "xpub",
        requestFingerprint = "request",
        createdAt = 1,
        name = "paykit server",
        isTrackingEnabled = false,
        setupState = WatchOnlyAccountSetupState.PendingDelivery,
    )
}

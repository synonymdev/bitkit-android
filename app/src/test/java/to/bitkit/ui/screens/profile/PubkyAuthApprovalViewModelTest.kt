package to.bitkit.ui.screens.profile

import android.content.Context
import com.synonym.paykit.PubkyAuthCompanionClaimApprovalException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Test
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
    private val context: Context = mock()
    private val pubkyRepo: PubkyRepo = mock()
    private val watchOnlyAccountRepo: WatchOnlyAccountRepo = mock()

    @Test
    fun `initial state is loading`() {
        val sut = createSut()

        assertEquals(ApprovalState.Loading, sut.uiState.value.state)
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
        verifyBlocking(pubkyRepo, never()) { approveAuth(authUrl, capabilities) }
    }

    @Test
    fun `confirmAuthorize ignores a stale auth URL after another request loads`() = test {
        val staleAuthUrl = "pubkyauth://signin?caps=/pub/stale/:rw"
        val currentAuthUrl = "pubkyauth://signin?caps=/pub/current/:rw"
        whenever(context.getString(R.string.profile__auth_approval_service_unknown)).thenReturn("Unknown service")
        whenever(pubkyRepo.profile).thenReturn(MutableStateFlow(null))
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
        verifyBlocking(pubkyRepo, never()) { approveAuth(staleAuthUrl, "/pub/current/:rw") }
    }

    @Test
    fun `confirmAuthorize reparses the current URL and fails closed when it changes`() = test {
        val authUrl = "pubkyauth://signin?caps=/pub/current/:rw"
        val capabilities = "/pub/current/:rw"
        whenever(context.getString(R.string.profile__auth_approval_service_unknown)).thenReturn("Unknown service")
        whenever(context.getString(R.string.profile__auth_error_title)).thenReturn("Authorization failed")
        whenever(pubkyRepo.profile).thenReturn(MutableStateFlow(null))
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
        verifyBlocking(pubkyRepo, never()) { approveAuth(authUrl, capabilities) }
    }

    @Test
    fun `load exposes watch-only account claim for approval`() = test {
        val authUrl = "pubkyauth://signin?caps=${PubkyAuthClaim.WATCH_ONLY_ACCOUNT_CAPABILITIES}"
        whenever(context.getString(R.string.profile__auth_approval_service_unknown)).thenReturn("Unknown service")
        whenever(
            context.getString(R.string.profile__auth_approval_watch_only_account_default_name, "paykit")
        ).thenReturn("paykit server")
        whenever(pubkyRepo.profile).thenReturn(MutableStateFlow(null))
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

        assertEquals(ApprovalState.Authorize, sut.uiState.value.state)
        assertEquals(PubkyAuthClaim.WATCH_ONLY_ACCOUNT_V1, sut.uiState.value.bitkitClaim)
        assertEquals("paykit server", sut.uiState.value.watchOnlyAccountName)
    }

    @Test
    fun `watch-only authorization uses combined companion approval`() = test {
        val authUrl = "pubkyauth://signin?secret=request&caps=${PubkyAuthClaim.WATCH_ONLY_ACCOUNT_CAPABILITIES}"
        val capabilities = PubkyAuthClaim.WATCH_ONLY_ACCOUNT_CAPABILITIES
        val prepared = PreparedWatchOnlyAccountClaim(
            account = watchOnlyAccount(),
            payload = ByteArray(84),
        )
        whenever(context.getString(R.string.profile__auth_approval_service_unknown)).thenReturn("Unknown service")
        whenever(
            context.getString(R.string.profile__auth_approval_watch_only_account_default_name, "paykit")
        ).thenReturn("paykit server")
        whenever(pubkyRepo.profile).thenReturn(MutableStateFlow(null))
        whenever { pubkyRepo.parseAuthUrl(authUrl) }.thenReturn(
            Result.success(authRequest(authUrl, capabilities, PubkyAuthClaim.WATCH_ONLY_ACCOUNT_V1)),
        )
        whenever { watchOnlyAccountRepo.prepareUnsignedClaim(authUrl, "paykit server") }.thenReturn(prepared)
        whenever { watchOnlyAccountRepo.beginAuthorization(prepared.account.id) }.thenReturn(false)
        whenever { pubkyRepo.approveAuthWithCompanionClaim(authUrl, prepared.payload) }.thenReturn(Result.success(Unit))
        whenever { watchOnlyAccountRepo.markActive(prepared.account.id) }.thenReturn(Unit)
        val sut = createSut()

        sut.load(authUrl)
        advanceUntilIdle()
        sut.confirmAuthorize(authUrl)
        advanceUntilIdle()

        assertEquals(ApprovalState.Success, sut.uiState.value.state)
        verifyBlocking(watchOnlyAccountRepo) { prepareUnsignedClaim(authUrl, "paykit server") }
        verifyBlocking(pubkyRepo) { approveAuthWithCompanionClaim(authUrl, prepared.payload) }
        verifyBlocking(pubkyRepo, times(2)) { parseAuthUrl(authUrl) }
        verifyBlocking(pubkyRepo, never()) { approveAuth(authUrl, capabilities) }
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
        whenever(context.getString(R.string.profile__auth_approval_service_unknown)).thenReturn("Unknown service")
        whenever(
            context.getString(R.string.profile__auth_approval_watch_only_account_default_name, "paykit")
        ).thenReturn("paykit server")
        whenever(pubkyRepo.profile).thenReturn(MutableStateFlow(null))
        whenever { pubkyRepo.parseAuthUrl(authUrl) }.thenReturn(
            Result.success(authRequest(authUrl, capabilities, PubkyAuthClaim.WATCH_ONLY_ACCOUNT_V1)),
        )
        whenever { watchOnlyAccountRepo.prepareUnsignedClaim(authUrl, "paykit server") }.thenReturn(prepared)
        whenever { watchOnlyAccountRepo.beginAuthorization(prepared.account.id) }.thenReturn(false)
        whenever { pubkyRepo.approveAuthWithCompanionClaim(authUrl, prepared.payload) }.thenReturn(Result.success(Unit))
        whenever { watchOnlyAccountRepo.markActive(prepared.account.id) }.thenReturn(Unit)
        val sut = createSut()

        sut.load(authUrl)
        advanceUntilIdle()
        sut.confirmAuthorize(authUrl)
        sut.confirmAuthorize(authUrl)
        advanceUntilIdle()

        assertEquals(ApprovalState.Success, sut.uiState.value.state)
        verifyBlocking(watchOnlyAccountRepo, times(1)) { prepareUnsignedClaim(authUrl, "paykit server") }
        verifyBlocking(watchOnlyAccountRepo, times(1)) { beginAuthorization(prepared.account.id) }
        verifyBlocking(pubkyRepo, times(1)) { approveAuthWithCompanionClaim(authUrl, prepared.payload) }
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
        whenever(context.getString(R.string.profile__auth_approval_service_unknown)).thenReturn("Unknown service")
        whenever(
            context.getString(R.string.profile__auth_approval_watch_only_account_default_name, "paykit")
        ).thenReturn("paykit server")
        whenever(pubkyRepo.profile).thenReturn(MutableStateFlow(null))
        whenever { pubkyRepo.parseAuthUrl(authUrl) }.thenReturn(
            Result.success(authRequest(authUrl, capabilities, PubkyAuthClaim.WATCH_ONLY_ACCOUNT_V1)),
        )
        whenever { pubkyRepo.parseAuthUrl(secondAuthUrl) }.thenReturn(
            Result.success(authRequest(secondAuthUrl, secondCapabilities)),
        )
        whenever { watchOnlyAccountRepo.prepareUnsignedClaim(authUrl, "paykit server") }.thenReturn(prepared)
        whenever { watchOnlyAccountRepo.beginAuthorization(prepared.account.id) }.thenReturn(false)
        whenever { pubkyRepo.approveAuthWithCompanionClaim(authUrl, prepared.payload) }
            .doSuspendableAnswer { approvalResult.await() }
        whenever { watchOnlyAccountRepo.markActive(prepared.account.id) }.thenReturn(Unit)
        val sut = createSut()

        sut.load(authUrl)
        advanceUntilIdle()
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
        verifyBlocking(pubkyRepo, times(1)) { approveAuthWithCompanionClaim(authUrl, prepared.payload) }
        verifyBlocking(pubkyRepo, never()) { approveAuth(secondAuthUrl, secondCapabilities) }
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
        whenever(context.getString(R.string.profile__auth_approval_service_unknown)).thenReturn("Unknown service")
        whenever(context.getString(R.string.profile__auth_error_title)).thenReturn("Authorization failed")
        whenever(
            context.getString(R.string.profile__auth_approval_watch_only_account_default_name, "paykit")
        ).thenReturn("paykit server")
        whenever(pubkyRepo.profile).thenReturn(MutableStateFlow(null))
        whenever { pubkyRepo.parseAuthUrl(authUrl) }.thenReturn(
            Result.success(authRequest(authUrl, capabilities, PubkyAuthClaim.WATCH_ONLY_ACCOUNT_V1)),
        )
        whenever { watchOnlyAccountRepo.prepareUnsignedClaim(authUrl, "paykit server") }.thenReturn(prepared)
        whenever { watchOnlyAccountRepo.beginAuthorization(prepared.account.id) }.thenReturn(false)
        whenever { pubkyRepo.approveAuthWithCompanionClaim(authUrl, prepared.payload) }
            .thenReturn(Result.failure(AppError(authorizationError)))
        val sut = createSut()

        sut.load(authUrl)
        advanceUntilIdle()
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
        whenever(context.getString(R.string.profile__auth_approval_service_unknown)).thenReturn("Unknown service")
        whenever(context.getString(R.string.profile__auth_error_title)).thenReturn("Authorization failed")
        whenever(
            context.getString(R.string.profile__auth_approval_watch_only_account_default_name, "paykit")
        ).thenReturn("paykit server")
        whenever(pubkyRepo.profile).thenReturn(MutableStateFlow(null))
        whenever { pubkyRepo.parseAuthUrl(authUrl) }.thenReturn(
            Result.success(authRequest(authUrl, capabilities, PubkyAuthClaim.WATCH_ONLY_ACCOUNT_V1)),
        )
        whenever { watchOnlyAccountRepo.prepareUnsignedClaim(authUrl, "paykit server") }.thenReturn(prepared)
        whenever { watchOnlyAccountRepo.beginAuthorization(prepared.account.id) }.thenReturn(false)
        whenever { pubkyRepo.approveAuthWithCompanionClaim(authUrl, prepared.payload) }
            .thenReturn(Result.failure(IllegalStateException("Relay delivery failed")))
        val sut = createSut()

        sut.load(authUrl)
        advanceUntilIdle()
        sut.confirmAuthorize(authUrl)
        advanceUntilIdle()

        assertEquals(ApprovalState.Authorize, sut.uiState.value.state)
        verifyBlocking(pubkyRepo, never()) { approveAuth(authUrl, capabilities) }
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
        whenever(context.getString(R.string.profile__auth_approval_service_unknown)).thenReturn("Unknown service")
        whenever(context.getString(R.string.profile__auth_error_title)).thenReturn("Authorization failed")
        whenever(
            context.getString(R.string.profile__auth_approval_watch_only_account_default_name, "paykit")
        ).thenReturn("paykit server")
        whenever(pubkyRepo.profile).thenReturn(MutableStateFlow(null))
        whenever { pubkyRepo.parseAuthUrl(authUrl) }.thenReturn(
            Result.success(authRequest(authUrl, capabilities, PubkyAuthClaim.WATCH_ONLY_ACCOUNT_V1)),
        )
        whenever { watchOnlyAccountRepo.prepareUnsignedClaim(authUrl, "paykit server") }.thenReturn(prepared)
        whenever { watchOnlyAccountRepo.beginAuthorization(prepared.account.id) }.thenReturn(true)
        whenever { pubkyRepo.approveAuthWithCompanionClaim(authUrl, prepared.payload) }
            .thenReturn(Result.failure(IllegalStateException("Relay delivery failed")))
        val sut = createSut()

        sut.load(authUrl)
        advanceUntilIdle()
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
        whenever(context.getString(R.string.profile__auth_approval_service_unknown)).thenReturn("Unknown service")
        whenever(context.getString(R.string.profile__auth_error_title)).thenReturn("Authorization failed")
        whenever(
            context.getString(R.string.profile__auth_approval_watch_only_account_default_name, "paykit")
        ).thenReturn("paykit server")
        whenever(pubkyRepo.profile).thenReturn(MutableStateFlow(null))
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
        sut.confirmAuthorize(authUrl)
        advanceUntilIdle()

        assertEquals(ApprovalState.Authorize, sut.uiState.value.state)
        verifyBlocking(watchOnlyAccountRepo) { cancelAuthorization(prepared.account.id) }
        verifyBlocking(pubkyRepo, never()) { approveAuthWithCompanionClaim(authUrl, prepared.payload) }
        verifyBlocking(watchOnlyAccountRepo, never()) { markActive(prepared.account.id) }
    }

    @Test
    fun `tracking failure uses the current authorizing disposition`() = test {
        val authUrl = "pubkyauth://signin?secret=request&caps=${PubkyAuthClaim.WATCH_ONLY_ACCOUNT_CAPABILITIES}"
        val prepared = PreparedWatchOnlyAccountClaim(
            account = watchOnlyAccount(),
            payload = ByteArray(84),
        )
        whenever(context.getString(R.string.profile__auth_approval_service_unknown)).thenReturn("Unknown service")
        whenever(context.getString(R.string.profile__auth_error_title)).thenReturn("Authorization failed")
        whenever(
            context.getString(R.string.profile__auth_approval_watch_only_account_default_name, "paykit")
        ).thenReturn("paykit server")
        whenever(pubkyRepo.profile).thenReturn(MutableStateFlow(null))
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
        sut.confirmAuthorize(authUrl)
        advanceUntilIdle()

        assertEquals(ApprovalState.Authorize, sut.uiState.value.state)
        verifyBlocking(watchOnlyAccountRepo) {
            cancelAuthorization(prepared.account.id, preserveAuthorizingState = true)
        }
        verifyBlocking(pubkyRepo, never()) { approveAuthWithCompanionClaim(authUrl, prepared.payload) }
    }

    @Test
    fun `activation persistence failure does not report success or unload the authorized account`() = test {
        val authUrl = "pubkyauth://signin?secret=request&caps=${PubkyAuthClaim.WATCH_ONLY_ACCOUNT_CAPABILITIES}"
        val prepared = PreparedWatchOnlyAccountClaim(
            account = watchOnlyAccount(),
            payload = ByteArray(84),
        )
        whenever(context.getString(R.string.profile__auth_approval_service_unknown)).thenReturn("Unknown service")
        whenever(context.getString(R.string.profile__auth_error_title)).thenReturn("Authorization failed")
        whenever(
            context.getString(R.string.profile__auth_approval_watch_only_account_default_name, "paykit")
        ).thenReturn("paykit server")
        whenever(pubkyRepo.profile).thenReturn(MutableStateFlow(null))
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
        whenever { watchOnlyAccountRepo.beginAuthorization(prepared.account.id) }.thenReturn(false)
        whenever { pubkyRepo.approveAuthWithCompanionClaim(authUrl, prepared.payload) }.thenReturn(Result.success(Unit))
        whenever { watchOnlyAccountRepo.markActive(prepared.account.id) }
            .thenThrow(IllegalStateException("Persistence failed"))
        val sut = createSut()

        sut.load(authUrl)
        advanceUntilIdle()
        sut.confirmAuthorize(authUrl)
        advanceUntilIdle()

        assertEquals(ApprovalState.Authorize, sut.uiState.value.state)
        verifyBlocking(watchOnlyAccountRepo) { beginAuthorization(prepared.account.id) }
        verifyBlocking(watchOnlyAccountRepo, never()) { cancelAuthorization(prepared.account.id) }
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
    ) = PubkyAuthRequest(
        rawUrl = authUrl,
        relay = "https://httprelay.pubky.app/inbox/",
        capabilities = capabilities,
        permissions = listOf(PubkyAuthPermission(path = "/pub/paykit/v0/bitkit/server/", accessLevel = "rw")),
        serviceNames = listOf("paykit"),
        bitkitClaim = bitkitClaim,
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

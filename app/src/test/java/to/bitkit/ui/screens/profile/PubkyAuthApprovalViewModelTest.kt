package to.bitkit.ui.screens.profile

import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.mockito.kotlin.mock
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
import to.bitkit.repositories.WatchOnlyAccountRepo
import to.bitkit.test.BaseUnitTest
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
    fun `confirmAuthorize reparses capabilities when load has not completed`() = test {
        val authUrl = "pubkyauth://signin?caps=/pub/bitkit.to/:rw"
        val capabilities = "/pub/bitkit.to/:rw"
        whenever { pubkyRepo.parseAuthUrl(authUrl) }.thenReturn(
            Result.success(authRequest(authUrl, capabilities)),
        )
        whenever { pubkyRepo.approveAuth(authUrl, capabilities) }.thenReturn(Result.success(Unit))
        val sut = createSut()

        sut.confirmAuthorize(authUrl)
        advanceUntilIdle()

        assertEquals(ApprovalState.Success, sut.uiState.value.state)
        verifyBlocking(pubkyRepo) { parseAuthUrl(authUrl) }
        verifyBlocking(pubkyRepo) { approveAuth(authUrl, capabilities) }
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
    fun `watch-only authorization delivers signed claim before approving session`() = test {
        val authUrl = "pubkyauth://signin?secret=request&caps=${PubkyAuthClaim.WATCH_ONLY_ACCOUNT_CAPABILITIES}"
        val capabilities = PubkyAuthClaim.WATCH_ONLY_ACCOUNT_CAPABILITIES
        val prepared = PreparedWatchOnlyAccountClaim(
            account = watchOnlyAccount(),
            payload = ByteArray(148),
        )
        whenever(context.getString(R.string.profile__auth_approval_service_unknown)).thenReturn("Unknown service")
        whenever(
            context.getString(R.string.profile__auth_approval_watch_only_account_default_name, "paykit")
        ).thenReturn("paykit server")
        whenever(pubkyRepo.profile).thenReturn(MutableStateFlow(null))
        whenever { pubkyRepo.parseAuthUrl(authUrl) }.thenReturn(
            Result.success(authRequest(authUrl, capabilities, PubkyAuthClaim.WATCH_ONLY_ACCOUNT_V1)),
        )
        whenever { watchOnlyAccountRepo.prepareSignedClaim(authUrl, "paykit server") }.thenReturn(prepared)
        whenever { watchOnlyAccountRepo.deliver(prepared, authUrl) }.thenReturn(Unit)
        whenever { pubkyRepo.approveAuth(authUrl, capabilities) }.thenReturn(Result.success(Unit))
        whenever { watchOnlyAccountRepo.markActive(prepared.account.id) }.thenReturn(Unit)
        val sut = createSut()

        sut.load(authUrl)
        advanceUntilIdle()
        sut.confirmAuthorize(authUrl)
        advanceUntilIdle()

        assertEquals(ApprovalState.Success, sut.uiState.value.state)
        verifyBlocking(watchOnlyAccountRepo) { prepareSignedClaim(authUrl, "paykit server") }
        verifyBlocking(watchOnlyAccountRepo) { deliver(prepared, authUrl) }
        verifyBlocking(pubkyRepo) { approveAuth(authUrl, capabilities) }
        verifyBlocking(watchOnlyAccountRepo) { markActive(prepared.account.id) }
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
        isTrackingEnabled = true,
        setupState = WatchOnlyAccountSetupState.PendingDelivery,
    )
}

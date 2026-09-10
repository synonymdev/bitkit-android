package to.bitkit.ui.screens.profile

import android.content.Context
import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.models.PubkyAuthRequest
import to.bitkit.models.PubkyProfile
import to.bitkit.repositories.PubkyRepo
import to.bitkit.services.PubkyRingAuthTimeoutError
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class PubkyAuthApprovalRetryTest : BaseUnitTest() {
    private val context: Context = mock()
    private val pubkyRepo: PubkyRepo = mock {
        on { profile } doReturn MutableStateFlow<PubkyProfile?>(null)
        on { publicKey } doReturn MutableStateFlow<String?>(null)
        on { displayName } doReturn MutableStateFlow<String?>(null)
        on { displayImageUri } doReturn MutableStateFlow<String?>(null)
    }

    @Test
    fun `relay timeout restores consent and requires local auth for retry`() = test {
        whenever(context.getString(R.string.profile__auth_approval_service_unknown)).thenReturn("Unknown service")
        whenever(context.getString(R.string.profile__auth_error_title)).thenReturn("Authorization failed")
        val authUrl = "pubkyring://signup?hs=homeserver" +
            "&relay=https://relay.example/inbox/&secret=secret&caps=/pub/example/:rw"
        val request = PubkyAuthRequest.parseSignup(authUrl).getOrThrow()
        whenever(context.getString(R.string.profile__auth_error_timeout)).thenReturn("Relay timed out")
        whenever(pubkyRepo.parseAuthUrl(authUrl)).thenReturn(Result.success(request))
        whenever(pubkyRepo.approveSignupAuth(request)).thenReturn(
            Result.failure(AppError(PubkyRingAuthTimeoutError())),
            Result.success(Unit),
        )
        val sut = PubkyAuthApprovalViewModel(context, pubkyRepo, mock())

        sut.effects.test {
            sut.load(authUrl)
            advanceUntilIdle()
            sut.requestAuthorize(authUrl)
            assertEquals(PubkyAuthApprovalEffect.RequestLocalAuth(authUrl), awaitItem())
            sut.confirmAuthorize(authUrl)
            advanceUntilIdle()
            assertEquals(ApprovalState.Authorize, sut.uiState.value.state)

            sut.requestAuthorize(authUrl)
            assertEquals(PubkyAuthApprovalEffect.RequestLocalAuth(authUrl), awaitItem())
            assertEquals(ApprovalState.Authenticating, sut.uiState.value.state)
            verifyBlocking(pubkyRepo) { approveSignupAuth(request) }
            sut.confirmAuthorize(authUrl)
            advanceUntilIdle()
            assertEquals(PubkyAuthApprovalEffect.Dismiss, awaitItem())
            verifyBlocking(pubkyRepo, times(2)) { approveSignupAuth(request) }
        }
    }
}

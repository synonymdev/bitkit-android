package to.bitkit.ui.screens.profile

import android.content.Context
import com.synonym.paykit.PubkyAuthDetails
import com.synonym.paykit.PubkyAuthRequestKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import to.bitkit.repositories.PubkyRepo
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class PubkyAuthApprovalViewModelTest : BaseUnitTest() {
    private val context: Context = mock()
    private val pubkyRepo: PubkyRepo = mock()

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
            Result.success(
                PubkyAuthDetails(
                    kind = PubkyAuthRequestKind.SIGN_IN,
                    capabilities = capabilities,
                    relayUrl = "https://httprelay.pubky.app/inbox/",
                    homeserverPublicKey = null,
                ),
            ),
        )
        whenever { pubkyRepo.approveAuth(authUrl, capabilities) }.thenReturn(Result.success(Unit))
        val sut = createSut()

        sut.confirmAuthorize(authUrl)
        advanceUntilIdle()

        assertEquals(ApprovalState.Success, sut.uiState.value.state)
        verifyBlocking(pubkyRepo) { parseAuthUrl(authUrl) }
        verifyBlocking(pubkyRepo) { approveAuth(authUrl, capabilities) }
    }

    private fun createSut() = PubkyAuthApprovalViewModel(
        context = context,
        pubkyRepo = pubkyRepo,
    )
}

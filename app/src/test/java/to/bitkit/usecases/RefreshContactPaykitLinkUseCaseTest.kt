package to.bitkit.usecases

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.models.PubkyProfile
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RefreshContactPaykitLinkUseCaseTest : BaseUnitTest() {
    private val pubkyRepo = mock<PubkyRepo>()
    private val privatePaykitRepo = mock<PrivatePaykitRepo>()
    private val contactKeys = listOf("pubky-alice", "pubky-bob")
    private val contacts = MutableStateFlow(
        contactKeys.map { publicKey ->
            PubkyProfile(
                publicKey = publicKey,
                name = publicKey,
                bio = "",
                imageUrl = null,
                links = emptyList(),
                status = null,
            )
        },
    )

    private val sut = RefreshContactPaykitLinkUseCase(
        ioDispatcher = testDispatcher,
        pubkyRepo = pubkyRepo,
        privatePaykitRepo = privatePaykitRepo,
    )

    @Before
    fun setUp() {
        whenever(pubkyRepo.contacts).thenReturn(contacts)
    }

    @Test
    fun `refreshes contact endpoints with saved contacts`() = test {
        whenever(privatePaykitRepo.refreshSavedContactEndpoints(contactKeys.last(), contactKeys))
            .thenReturn(Result.success(Unit))

        val result = sut(contactKeys.last())

        assertTrue(result.isSuccess)
        verify(privatePaykitRepo).refreshSavedContactEndpoints(contactKeys.last(), contactKeys)
    }

    @Test
    fun `returns endpoint refresh failure`() = test {
        val error = IllegalStateException("Endpoint refresh failed")
        whenever(privatePaykitRepo.refreshSavedContactEndpoints(contactKeys.last(), contactKeys))
            .thenReturn(Result.failure(error))

        val result = sut(contactKeys.last())

        assertEquals(error, result.exceptionOrNull())
    }
}

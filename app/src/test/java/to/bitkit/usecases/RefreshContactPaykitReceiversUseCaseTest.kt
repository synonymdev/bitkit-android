package to.bitkit.usecases

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.models.PubkyProfile
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RefreshContactPaykitReceiversUseCaseTest : BaseUnitTest() {
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

    private val sut = RefreshContactPaykitReceiversUseCase(
        ioDispatcher = testDispatcher,
        pubkyRepo = pubkyRepo,
        privatePaykitRepo = privatePaykitRepo,
    )

    @Before
    fun setUp() {
        whenever(pubkyRepo.contacts).thenReturn(contacts)
    }

    @Test
    fun `refreshes receiver paths before publishing the contact`() = test {
        whenever { pubkyRepo.refreshContactReceiverPaths(contactKeys.last()) }.thenReturn(Result.success(Unit))
        whenever {
            privatePaykitRepo.refreshSavedContactEndpoints(contactKeys.last(), contactKeys)
        }.thenReturn(Result.success(Unit))

        val result = sut(contactKeys.last())

        assertTrue(result.isSuccess)
        inOrder(pubkyRepo, privatePaykitRepo).apply {
            verify(pubkyRepo).refreshContactReceiverPaths(contactKeys.last())
            verify(privatePaykitRepo).refreshSavedContactEndpoints(contactKeys.last(), contactKeys)
            verify(privatePaykitRepo).startInitialLinkBurst(contactKeys, "contact receiver refresh")
        }
    }

    @Test
    fun `stops when receiver discovery fails`() = test {
        val error = IllegalStateException("Discovery failed")
        whenever { pubkyRepo.refreshContactReceiverPaths(contactKeys.last()) }.thenReturn(Result.failure(error))

        val result = sut(contactKeys.last())

        assertEquals(error, result.exceptionOrNull())
        verify(privatePaykitRepo, never()).refreshSavedContactEndpoints(contactKeys.last(), contactKeys)
        verify(privatePaykitRepo, never()).startInitialLinkBurst(contactKeys, "contact receiver refresh")
    }
}

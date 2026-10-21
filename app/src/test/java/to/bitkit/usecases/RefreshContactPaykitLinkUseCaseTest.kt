package to.bitkit.usecases

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import to.bitkit.models.PubkyProfile
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.PubkySignIn
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RefreshContactPaykitLinkUseCaseTest : BaseUnitTest() {
    private val pubkyRepo = mock<PubkyRepo>()
    private val privatePaykitRepo = mock<PrivatePaykitRepo>()
    private val signIn = PubkySignIn("pubky-owner", 1L)
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
        whenever(pubkyRepo.currentSignIn()).thenReturn(signIn)
        whenever(pubkyRepo.isCurrent(signIn)).thenReturn(true)
    }

    @Test
    fun `refreshes contact endpoints with saved contacts`() = test {
        var isStillCurrent: (() -> Boolean)? = null
        whenever(
            privatePaykitRepo.refreshSavedContactEndpoints(
                eq(contactKeys.last()),
                eq(contactKeys),
                eq(signIn.publicKey),
                any(),
            ),
        ).thenAnswer {
            isStillCurrent = it.getArgument(3)
            Result.success(Unit)
        }

        val result = sut(contactKeys.last())

        assertTrue(result.isSuccess)
        verify(privatePaykitRepo).refreshSavedContactEndpoints(
            eq(contactKeys.last()),
            eq(contactKeys),
            eq(signIn.publicKey),
            any(),
        )
        assertTrue(checkNotNull(isStillCurrent).invoke())
        whenever(pubkyRepo.isCurrent(signIn)).thenReturn(false)
        assertFalse(checkNotNull(isStillCurrent).invoke())
    }

    @Test
    fun `returns endpoint refresh failure`() = test {
        val error = IllegalStateException("Endpoint refresh failed")
        whenever(
            privatePaykitRepo.refreshSavedContactEndpoints(
                eq(contactKeys.last()),
                eq(contactKeys),
                eq(signIn.publicKey),
                any(),
            ),
        ).thenReturn(Result.failure(error))

        val result = sut(contactKeys.last())

        assertEquals(error, result.exceptionOrNull())
    }

    @Test
    fun `signed out refresh does not schedule linking`() = test {
        whenever(pubkyRepo.currentSignIn()).thenReturn(null)

        assertTrue(sut(contactKeys.last()).isSuccess)
        verifyNoInteractions(privatePaykitRepo)
    }
}

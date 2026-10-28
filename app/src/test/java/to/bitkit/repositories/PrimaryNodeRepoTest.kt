package to.bitkit.repositories

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.models.PrimaryNode
import to.bitkit.models.PrimaryNodeError
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class PrimaryNodeRepoTest : BaseUnitTest() {
    companion object {
        private const val KEY = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
    }

    private val settingsStore = mock<SettingsStore>()
    private val lightningRepo = mock<LightningRepo>()
    private val settings = MutableStateFlow(SettingsData())

    private fun repository(): PrimaryNodeRepo {
        whenever(settingsStore.data).thenReturn(settings)
        return PrimaryNodeRepo(settingsStore, lightningRepo, testDispatcher)
    }

    @Test
    fun `invalid input never changes the runtime or persisted primary`() = test {
        val result = repository().configure("not a node")

        assertIs<PrimaryNodeError.InvalidInput>(result.exceptionOrNull())
        verify(lightningRepo, never()).configurePrimaryNode(any())
        verify(settingsStore, never()).update(any())
    }

    @Test
    fun `bare key returns the endpoint resolved by runtime configuration`() = test {
        val resolved = PrimaryNode(KEY, "127.0.0.1:9735")
        whenever(lightningRepo.configurePrimaryNode(PrimaryNode(KEY))).thenReturn(Result.success(resolved))

        assertEquals(resolved, repository().configure(KEY).getOrThrow())
        verify(settingsStore, never()).update(any())
    }

    @Test
    fun `failed setup preserves the previous configured primary`() = test {
        val previous = "$KEY@127.0.0.1:9735"
        settings.value = settings.value.copy(primaryNodeUri = previous)
        val failure = AppError("Primary did not answer the setup request")
        whenever(lightningRepo.configurePrimaryNode(any())).thenReturn(Result.failure(failure))
        val repo = repository()

        assertSame(failure, repo.configure("$KEY@127.0.0.1:19735").exceptionOrNull())
        assertEquals(previous, repo.primaryNodeUri.first())
        verify(settingsStore, never()).update(any())
    }

    @Test
    fun `cancelling setup propagates cancellation`() = test {
        whenever(lightningRepo.configurePrimaryNode(any())).thenThrow(CancellationException())

        assertFailsWith<CancellationException> { repository().configure(KEY) }
    }
}

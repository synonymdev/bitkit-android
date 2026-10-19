package to.bitkit.services

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Test
import org.mockito.kotlin.mock
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class JadeServiceTest : BaseUnitTest() {

    private val transport = mock<JadeTransport>()

    @Test
    fun `finalizePsbt reaches the core function instead of recursing`() = test {
        val sut = JadeService(transport)

        // Without the native library the core call fails to link; a recursive call would overflow
        // the stack instead, which is the regression this guards against.
        val error = assertNotNull(runCatching { sut.finalizePsbt("not a psbt", "not a psbt") }.exceptionOrNull())

        assertFalse(generateSequence(error) { it.cause }.any { it is StackOverflowError }, "error=$error")
    }
}

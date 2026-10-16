package to.bitkit.ui.shared.toast

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Test
import to.bitkit.models.Toast
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ToastQueueManagerTest : BaseUnitTest() {
    private val waitToast = Toast(type = Toast.ToastType.INFO, title = "Loading", autoHide = false, isLoading = true)
    private val errorToast = Toast(type = Toast.ToastType.ERROR, title = "Failed", autoHide = false)

    @Test
    fun `dismiss hides the given toast when it is shown`() = test {
        val sut = ToastQueueManager(backgroundScope)
        sut.enqueue(waitToast)

        sut.dismiss(waitToast)

        assertNull(sut.currentToast.value)
    }

    @Test
    fun `dismiss keeps a toast that replaced the given one`() = test {
        val sut = ToastQueueManager(backgroundScope)
        sut.enqueue(waitToast)
        sut.enqueue(errorToast)

        sut.dismiss(waitToast)

        assertEquals(errorToast, sut.currentToast.value)
        sut.dismissCurrentToast()
        assertNull(sut.currentToast.value)
    }

    @Test
    fun `loading toast returns after a toast that interrupts it`() = test {
        val sut = ToastQueueManager(backgroundScope)
        val infoToast = Toast(type = Toast.ToastType.SUCCESS, title = "Back online", autoHide = true)
        sut.enqueue(waitToast)
        sut.enqueue(infoToast)
        assertEquals(infoToast, sut.currentToast.value)

        advanceTimeBy(Toast.VISIBILITY_TIME_DEFAULT + 1)

        assertEquals(waitToast, sut.currentToast.value)
    }
}

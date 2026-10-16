package to.bitkit.ui.shared.toast

import org.junit.Test
import to.bitkit.models.Toast
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
    }
}

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
    fun `a toast waits behind a shown toast that is not interruptible`() = test {
        val sut = ToastQueueManager(backgroundScope)
        val updateToast = Toast(type = Toast.ToastType.ERROR, title = "Update", autoHide = true, interruptible = false)
        val infoToast = Toast(type = Toast.ToastType.SUCCESS, title = "Back online", autoHide = true)
        sut.enqueue(updateToast)
        sut.enqueue(infoToast)
        assertEquals(updateToast, sut.currentToast.value)

        advanceTimeBy(Toast.VISIBILITY_TIME_DEFAULT + 1)

        assertEquals(infoToast, sut.currentToast.value)
    }

    @Test
    fun `a result replaces its queued progress toast behind a toast that is not interruptible`() = test {
        val sut = ToastQueueManager(backgroundScope)
        val updateToast = Toast(type = Toast.ToastType.ERROR, title = "Update", autoHide = true, interruptible = false)
        val progressToast = Toast(
            type = Toast.ToastType.INFO,
            title = "Applying changes",
            autoHide = false,
            replacedByNext = true,
        )
        val resultToast = Toast(type = Toast.ToastType.SUCCESS, title = "Settings updated", autoHide = true)
        sut.enqueue(updateToast)
        sut.enqueue(progressToast)
        sut.enqueue(resultToast)

        advanceTimeBy(Toast.VISIBILITY_TIME_DEFAULT + 1)

        assertEquals(resultToast, sut.currentToast.value)
        advanceTimeBy(Toast.VISIBILITY_TIME_DEFAULT + 1)
        assertNull(sut.currentToast.value)
    }

    @Test
    fun `a result replaces its queued progress toast after the warning hides`() = test {
        val sut = ToastQueueManager(backgroundScope)
        val updateToast = Toast(type = Toast.ToastType.ERROR, title = "Update", autoHide = true, interruptible = false)
        val onlineToast = Toast(type = Toast.ToastType.SUCCESS, title = "Back online", autoHide = true)
        val progressToast = Toast(
            type = Toast.ToastType.INFO,
            title = "Applying changes",
            autoHide = false,
            replacedByNext = true,
        )
        val resultToast = Toast(type = Toast.ToastType.SUCCESS, title = "Settings updated", autoHide = true)
        sut.enqueue(updateToast)
        sut.enqueue(onlineToast)
        sut.enqueue(progressToast)
        advanceTimeBy(Toast.VISIBILITY_TIME_DEFAULT + 1)
        assertEquals(onlineToast, sut.currentToast.value)

        sut.enqueue(resultToast)

        assertEquals(resultToast, sut.currentToast.value)
        advanceTimeBy(Toast.VISIBILITY_TIME_DEFAULT + 1)
        assertNull(sut.currentToast.value)
    }

    @Test
    fun `a toast that stays until dismissed keeps its place behind a toast that is not interruptible`() = test {
        val sut = ToastQueueManager(backgroundScope)
        val updateToast = Toast(type = Toast.ToastType.ERROR, title = "Update", autoHide = true, interruptible = false)
        val returnedToast = Toast(type = Toast.ToastType.INFO, title = "Payment returned", autoHide = false)
        val onlineToast = Toast(type = Toast.ToastType.SUCCESS, title = "Back online", autoHide = true)
        sut.enqueue(updateToast)
        sut.enqueue(returnedToast)
        sut.enqueue(onlineToast)

        advanceTimeBy(Toast.VISIBILITY_TIME_DEFAULT + 1)

        assertEquals(returnedToast, sut.currentToast.value)
        advanceTimeBy(Toast.VISIBILITY_TIME_DEFAULT + 1)
        assertEquals(returnedToast, sut.currentToast.value)
        sut.dismissCurrentToast()
        assertEquals(onlineToast, sut.currentToast.value)
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

    @Test
    fun `loading toast stays behind a second toast that interrupts it`() = test {
        val sut = ToastQueueManager(backgroundScope)
        val firstToast = Toast(type = Toast.ToastType.SUCCESS, title = "Back online", autoHide = true)
        val secondToast = Toast(type = Toast.ToastType.INFO, title = "Payment received", autoHide = true)
        sut.enqueue(waitToast)
        sut.enqueue(firstToast)
        sut.enqueue(secondToast)
        assertEquals(secondToast, sut.currentToast.value)

        advanceTimeBy(Toast.VISIBILITY_TIME_DEFAULT + 1)

        assertEquals(waitToast, sut.currentToast.value)
    }
}

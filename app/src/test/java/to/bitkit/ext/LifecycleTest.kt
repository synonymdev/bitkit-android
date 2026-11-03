package to.bitkit.ext

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class LifecycleTest : BaseUnitTest() {
    @Test
    fun `payment review releases on background and dismissal and resumes when started`() = test {
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this)
        }
        owner.lifecycle.currentState = Lifecycle.State.CREATED
        var activeReviews = 0
        var startedReviews = 0
        val review = launch {
            owner.lifecycle.withPaymentReview {
                activeReviews++
                startedReviews++
                val finishReview: () -> Unit = { activeReviews-- }
                finishReview
            }
        }
        runCurrent()
        assertEquals(0, activeReviews)

        owner.lifecycle.currentState = Lifecycle.State.STARTED
        runCurrent()
        assertEquals(1, activeReviews)
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        runCurrent()
        assertEquals(1, startedReviews)

        owner.lifecycle.currentState = Lifecycle.State.CREATED
        runCurrent()
        assertEquals(0, activeReviews)
        owner.lifecycle.currentState = Lifecycle.State.STARTED
        runCurrent()
        assertEquals(1, activeReviews)
        assertEquals(2, startedReviews)

        review.cancelAndJoin()
        assertEquals(0, activeReviews)
        owner.lifecycle.currentState = Lifecycle.State.DESTROYED
    }
}

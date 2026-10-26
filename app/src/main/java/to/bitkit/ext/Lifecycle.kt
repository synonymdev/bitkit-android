package to.bitkit.ext

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation

internal suspend fun Lifecycle.withPaymentReview(beginReview: () -> (() -> Unit)) {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        val finishReview = beginReview()
        try {
            awaitCancellation()
        } finally {
            finishReview()
        }
    }
}

package to.bitkit.models

import androidx.compose.runtime.Stable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Stable
data class Toast(
    val type: ToastType,
    val title: String,
    val body: String? = null,
    val autoHide: Boolean,
    val duration: Duration = DURATION_DEFAULT,
    val testTag: String? = null,
) {
    companion object {
        val DURATION_DEFAULT: Duration = 3.seconds
    }
}

@Stable
enum class ToastType { SUCCESS, INFO, LIGHTNING, WARNING, ERROR }

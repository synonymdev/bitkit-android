package to.bitkit.models

import androidx.compose.runtime.Stable

@Stable
data class Toast(
    val type: ToastType,
    val title: String,
    val body: String? = null,
    val autoHide: Boolean,
    val visibilityTime: Long = VISIBILITY_TIME_DEFAULT,
    val testTag: String? = null,
) {
    companion object {
        const val VISIBILITY_TIME_DEFAULT = 3000L
    }
}

@Stable
enum class ToastType { SUCCESS, INFO, LIGHTNING, WARNING, ERROR }

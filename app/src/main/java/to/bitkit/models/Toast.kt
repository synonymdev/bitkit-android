package to.bitkit.models

data class Toast(
    val type: ToastType,
    val title: String,
    val description: String? = null,
    val autoHide: Boolean,
    val visibilityTime: Long = VISIBILITY_TIME_DEFAULT,
    val testTag: String? = null,
    val isLoading: Boolean = false,
) {
    enum class ToastType { SUCCESS, INFO, LIGHTNING, WARNING, ERROR }

    companion object {
        const val VISIBILITY_TIME_SHORT = 1500L
        const val VISIBILITY_TIME_DEFAULT = 3000L

        /** Visibility for a toast with an action the user must read before it hides. */
        const val VISIBILITY_TIME_LONG = 6000L
    }
}

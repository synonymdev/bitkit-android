package to.bitkit.models

data class Toast(
    val type: ToastType,
    val title: String,
    val description: String? = null,
    val autoHide: Boolean,
    val visibilityTime: Long = VISIBILITY_TIME_DEFAULT,
) {
    enum class ToastType { SUCCESS, INFO, LIGHTNING, WARNING, ERROR }

    companion object {
        const val VISIBILITY_TIME_DEFAULT = 3000L
    }
}

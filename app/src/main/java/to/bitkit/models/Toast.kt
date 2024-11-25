package to.bitkit.models

data class Toast(
    val type: ToastType,
    val title: String,
    val description: String,
    val autoHide: Boolean,
    val visibilityTime: Long,
) {
    enum class ToastType { SUCCESS, INFO, LIGHTNING, WARNING, ERROR }

    companion object {
        const val VISIBILITY_TIME_DEFAULT = 3000L
    }
}

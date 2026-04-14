package to.bitkit.models

import androidx.annotation.DrawableRes

data class Toast(
    val type: ToastType,
    val title: String,
    val description: String? = null,
    val autoHide: Boolean,
    val visibilityTime: Long = VISIBILITY_TIME_DEFAULT,
    @DrawableRes val iconRes: Int? = null,
    val accentCategory: MilestoneCategory? = null,
    val useNeutralBackground: Boolean = false,
    val testTag: String? = null,
) {
    enum class ToastType { SUCCESS, INFO, LIGHTNING, WARNING, ERROR }

    companion object {
        const val VISIBILITY_TIME_DEFAULT = 3000L
    }
}

package to.bitkit.ui.shared.toast

import androidx.annotation.DrawableRes
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import to.bitkit.models.MilestoneCategory
import to.bitkit.models.Toast

object ToastEventBus {
    private val _events = MutableSharedFlow<Toast>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    @Suppress("LongParameterList")
    suspend fun send(
        type: Toast.ToastType,
        title: String,
        description: String? = null,
        autoHide: Boolean = true,
        visibilityTime: Long = Toast.VISIBILITY_TIME_DEFAULT,
        @DrawableRes iconRes: Int? = null,
        accentCategory: MilestoneCategory? = null,
        useNeutralBackground: Boolean = false,
        testTag: String? = null,
    ) {
        _events.emit(
            Toast(
                type = type,
                title = title,
                description = description,
                autoHide = autoHide,
                visibilityTime = visibilityTime,
                iconRes = iconRes,
                accentCategory = accentCategory,
                useNeutralBackground = useNeutralBackground,
                testTag = testTag,
            )
        )
    }

    suspend fun send(error: Throwable) {
        _events.emit(
            Toast(
                type = Toast.ToastType.ERROR,
                title = "Error",
                description = error.message ?: "Unknown error",
                autoHide = true,
                visibilityTime = Toast.VISIBILITY_TIME_DEFAULT,
                iconRes = null,
                accentCategory = null,
                useNeutralBackground = false,
            )
        )
    }
}

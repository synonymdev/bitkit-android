package to.bitkit.ui.shared.toast

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import to.bitkit.models.Toast

object ToastEventBus {
    private val _events = MutableSharedFlow<Toast>(replay = 0, extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    suspend fun send(
        type: Toast.ToastType,
        title: String,
        description: String,
        autoHide: Boolean = true,
        visibilityTime: Long = Toast.VISIBILITY_TIME_DEFAULT,
    ) {
        _events.emit(
            Toast(type, title, description, autoHide, visibilityTime)
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
            )
        )
    }
}

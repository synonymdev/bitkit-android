package to.bitkit.ui.shared.toast

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import to.bitkit.models.Toast
import to.bitkit.models.ToastType
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration

@Singleton
class Toaster @Inject constructor() {
    private val _events = MutableSharedFlow<Toast>(extraBufferCapacity = 1)
    val events: SharedFlow<Toast> = _events.asSharedFlow()

    @Suppress("LongParameterList")
    private suspend fun emit(
        type: ToastType,
        title: String,
        body: String? = null,
        autoHide: Boolean = true,
        duration: Duration = Toast.DURATION_DEFAULT,
        testTag: String? = null,
    ) {
        _events.emit(
            Toast(
                type = type,
                title = title,
                body = body,
                autoHide = autoHide,
                duration = duration,
                testTag = testTag,
            )
        )
    }

    suspend fun success(
        title: String,
        body: String? = null,
        testTag: String? = null,
    ) = emit(ToastType.SUCCESS, title, body, testTag = testTag)

    suspend fun info(
        title: String,
        body: String? = null,
        testTag: String? = null,
    ) = emit(ToastType.INFO, title, body, testTag = testTag)

    suspend fun lightning(
        title: String,
        body: String? = null,
        testTag: String? = null,
    ) = emit(ToastType.LIGHTNING, title, body, testTag = testTag)

    suspend fun warning(
        title: String,
        body: String? = null,
        testTag: String? = null,
    ) = emit(ToastType.WARNING, title, body, testTag = testTag)

    suspend fun error(
        title: String,
        body: String? = null,
        testTag: String? = null,
    ) = emit(ToastType.ERROR, title, body, testTag = testTag)

    suspend fun error(throwable: Throwable) = emit(
        type = ToastType.ERROR,
        title = "Error",
        body = throwable.message ?: "An unknown error occurred",
    )
}

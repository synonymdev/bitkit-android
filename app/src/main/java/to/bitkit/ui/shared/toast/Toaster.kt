package to.bitkit.ui.shared.toast

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import to.bitkit.R
import to.bitkit.models.Toast
import to.bitkit.models.ToastText
import to.bitkit.models.ToastType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Toaster @Inject constructor() {
    private val _events = MutableSharedFlow<Toast>(extraBufferCapacity = 1)
    val events: SharedFlow<Toast> = _events.asSharedFlow()

    private fun emit(toast: Toast) = _events.tryEmit(toast)

    fun success(title: ToastText, body: ToastText? = null, testTag: String? = null) =
        emit(Toast(ToastType.SUCCESS, title, body, testTag = testTag))

    fun info(title: ToastText, body: ToastText? = null, testTag: String? = null) =
        emit(Toast(ToastType.INFO, title, body, testTag = testTag))

    fun lightning(title: ToastText, body: ToastText? = null, testTag: String? = null) =
        emit(Toast(ToastType.LIGHTNING, title, body, testTag = testTag))

    fun warn(title: ToastText, body: ToastText? = null, testTag: String? = null) =
        emit(Toast(ToastType.WARNING, title, body, testTag = testTag))

    fun error(title: ToastText, body: ToastText? = null, testTag: String? = null) =
        emit(Toast(ToastType.ERROR, title, body, testTag = testTag))

    fun error(throwable: Throwable) = emit(
        Toast(
            type = ToastType.ERROR,
            title = ToastText(R.string.common__error),
            body = throwable.message?.let { ToastText(it) } ?: ToastText(R.string.common__error_body),
        )
    )
}

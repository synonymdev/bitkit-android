package to.bitkit.ui.shared.toast

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import to.bitkit.R
import to.bitkit.models.Toast
import to.bitkit.models.ToastText
import to.bitkit.models.ToastType
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration

@Suppress("TooManyFunctions")
@Singleton
class Toaster @Inject constructor() {
    private val _events = MutableSharedFlow<Toast>(extraBufferCapacity = 1)
    val events: SharedFlow<Toast> = _events.asSharedFlow()

    @Suppress("LongParameterList")
    private fun emit(
        type: ToastType,
        title: ToastText,
        body: ToastText? = null,
        autoHide: Boolean = true,
        duration: Duration = Toast.DURATION_DEFAULT,
        testTag: String? = null,
    ) {
        _events.tryEmit(
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

    // region @StringRes overloads
    fun success(
        @StringRes title: Int,
        @StringRes body: Int? = null,
        testTag: String? = null,
    ) = emit(
        ToastType.SUCCESS,
        ToastText(title),
        body?.let { ToastText(it) },
        testTag = testTag
    )

    fun info(
        @StringRes title: Int,
        @StringRes body: Int? = null,
        testTag: String? = null,
    ) = emit(
        ToastType.INFO,
        ToastText(title),
        body?.let { ToastText(it) },
        testTag = testTag,
    )

    fun lightning(
        @StringRes title: Int,
        @StringRes body: Int? = null,
        testTag: String? = null,
    ) = emit(
        ToastType.LIGHTNING,
        ToastText(title),
        body?.let { ToastText(it) },
        testTag = testTag
    )

    fun warn(
        @StringRes title: Int,
        @StringRes body: Int? = null,
        testTag: String? = null,
    ) = emit(
        ToastType.WARNING,
        ToastText(title),
        body?.let { ToastText(it) },
        testTag = testTag
    )

    fun error(
        @StringRes title: Int,
        @StringRes body: Int? = null,
        testTag: String? = null,
    ) = emit(
        ToastType.ERROR,
        ToastText(title),
        body?.let { ToastText(it) },
        testTag = testTag,
    )
    // endregion

    // region ToastText overloads
    fun success(
        title: ToastText,
        body: ToastText? = null,
        testTag: String? = null,
    ) = emit(ToastType.SUCCESS, title, body, testTag = testTag)

    fun info(
        title: ToastText,
        body: ToastText? = null,
        testTag: String? = null,
    ) = emit(ToastType.INFO, title, body, testTag = testTag)

    fun lightning(
        title: ToastText,
        body: ToastText? = null,
        testTag: String? = null,
    ) = emit(ToastType.LIGHTNING, title, body, testTag = testTag)

    fun warn(
        title: ToastText,
        body: ToastText? = null,
        testTag: String? = null,
    ) = emit(ToastType.WARNING, title, body, testTag = testTag)

    fun error(
        title: ToastText,
        body: ToastText? = null,
        testTag: String? = null,
    ) = emit(ToastType.ERROR, title, body, testTag = testTag)
    // endregion

    // region String literal overloads
    fun success(
        title: String,
        body: String? = null,
        testTag: String? = null,
    ) = emit(
        ToastType.SUCCESS,
        ToastText(title),
        body?.let { ToastText(it) },
        testTag = testTag,
    )

    fun info(
        title: String,
        body: String? = null,
        testTag: String? = null,
    ) = emit(
        ToastType.INFO,
        ToastText(title),
        body?.let { ToastText(it) },
        testTag = testTag,
    )

    fun lightning(
        title: String,
        body: String? = null,
        testTag: String? = null,
    ) = emit(
        ToastType.LIGHTNING,
        ToastText(title),
        body?.let { ToastText(it) },
        testTag = testTag,
    )

    fun warn(
        title: String,
        body: String? = null,
        testTag: String? = null,
    ) = emit(
        ToastType.WARNING,
        ToastText(title),
        body?.let { ToastText(it) },
        testTag = testTag,
    )

    fun error(
        title: String,
        body: String? = null,
        testTag: String? = null,
    ) = emit(
        ToastType.ERROR,
        ToastText(title),
        body?.let { ToastText(it) },
        testTag = testTag,
    )

    fun error(throwable: Throwable) = emit(
        type = ToastType.ERROR,
        title = ToastText(R.string.common__error),
        body = throwable.message?.let { ToastText(it) }
            ?: ToastText(R.string.common__error_body),
    )
    // endregion
}

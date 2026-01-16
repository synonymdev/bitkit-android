package to.bitkit.ui.shared.toast

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private suspend fun emit(
        type: ToastType,
        title: ToastText,
        body: ToastText? = null,
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

    // region @StringRes overloads
    suspend fun success(
        @StringRes titleRes: Int,
        @StringRes bodyRes: Int? = null,
        testTag: String? = null,
    ) = emit(
        ToastType.SUCCESS,
        ToastText.Resource(titleRes),
        bodyRes?.let { ToastText.Resource(it) },
        testTag = testTag
    )

    suspend fun info(
        @StringRes titleRes: Int,
        @StringRes bodyRes: Int? = null,
        testTag: String? = null,
    ) = emit(
        ToastType.INFO,
        ToastText.Resource(titleRes),
        bodyRes?.let { ToastText.Resource(it) },
        testTag = testTag,
    )

    suspend fun lightning(
        @StringRes titleRes: Int,
        @StringRes bodyRes: Int? = null,
        testTag: String? = null,
    ) = emit(
        ToastType.LIGHTNING,
        ToastText.Resource(titleRes),
        bodyRes?.let { ToastText.Resource(it) },
        testTag = testTag
    )

    suspend fun warning(
        @StringRes titleRes: Int,
        @StringRes bodyRes: Int? = null,
        testTag: String? = null,
    ) = emit(
        ToastType.WARNING,
        ToastText.Resource(titleRes),
        bodyRes?.let { ToastText.Resource(it) },
        testTag = testTag
    )

    suspend fun error(
        @StringRes titleRes: Int,
        @StringRes bodyRes: Int? = null,
        testTag: String? = null,
    ) = emit(
        ToastType.ERROR,
        ToastText.Resource(titleRes),
        bodyRes?.let { ToastText.Resource(it) },
        testTag = testTag,
    )
    // endregion

    // region ToastText overloads
    suspend fun success(
        title: ToastText,
        body: ToastText? = null,
        testTag: String? = null,
    ) = emit(ToastType.SUCCESS, title, body, testTag = testTag)

    suspend fun info(
        title: ToastText,
        body: ToastText? = null,
        testTag: String? = null,
    ) = emit(ToastType.INFO, title, body, testTag = testTag)

    suspend fun lightning(
        title: ToastText,
        body: ToastText? = null,
        testTag: String? = null,
    ) = emit(ToastType.LIGHTNING, title, body, testTag = testTag)

    suspend fun warning(
        title: ToastText,
        body: ToastText? = null,
        testTag: String? = null,
    ) = emit(ToastType.WARNING, title, body, testTag = testTag)

    suspend fun error(
        title: ToastText,
        body: ToastText? = null,
        testTag: String? = null,
    ) = emit(ToastType.ERROR, title, body, testTag = testTag)
    // endregion

    // region String literal overloads
    suspend fun success(
        title: String,
        body: String? = null,
        testTag: String? = null,
    ) = emit(
        ToastType.SUCCESS,
        ToastText.Literal(title),
        body?.let { ToastText.Literal(it) },
        testTag = testTag,
    )

    suspend fun info(
        title: String,
        body: String? = null,
        testTag: String? = null,
    ) = emit(
        ToastType.INFO,
        ToastText.Literal(title),
        body?.let { ToastText.Literal(it) },
        testTag = testTag,
    )

    suspend fun lightning(
        title: String,
        body: String? = null,
        testTag: String? = null,
    ) = emit(
        ToastType.LIGHTNING,
        ToastText.Literal(title),
        body?.let { ToastText.Literal(it) },
        testTag = testTag,
    )

    suspend fun warning(
        title: String,
        body: String? = null,
        testTag: String? = null,
    ) = emit(
        ToastType.WARNING,
        ToastText.Literal(title),
        body?.let { ToastText.Literal(it) },
        testTag = testTag,
    )

    suspend fun error(
        title: String,
        body: String? = null,
        testTag: String? = null,
    ) = emit(
        ToastType.ERROR,
        ToastText.Literal(title),
        body?.let { ToastText.Literal(it) },
        testTag = testTag,
    )

    suspend fun error(throwable: Throwable) = emit(
        type = ToastType.ERROR,
        title = ToastText.Literal("Error"),
        body = ToastText.Literal(throwable.message ?: "An unknown error occurred"),
    )
    // endregion
}

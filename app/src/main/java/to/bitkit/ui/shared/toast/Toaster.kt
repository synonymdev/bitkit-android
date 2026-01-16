package to.bitkit.ui.shared.toast

import android.content.Context
import androidx.annotation.StringRes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import to.bitkit.R
import to.bitkit.models.Toast
import to.bitkit.models.ToastType
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration

@Suppress("TooManyFunctions")
@Singleton
class Toaster @Inject constructor(
    @ApplicationContext private val context: Context,
) {
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

    // region Success
    suspend fun success(
        title: String,
        body: String? = null,
        testTag: String? = null,
    ) = emit(ToastType.SUCCESS, title, body, testTag = testTag)

    suspend fun success(
        @StringRes titleRes: Int,
        @StringRes bodyRes: Int? = null,
        testTag: String? = null,
    ) = emit(
        type = ToastType.SUCCESS,
        title = context.getString(titleRes),
        body = bodyRes?.let { context.getString(it) },
        testTag = testTag,
    )
    // endregion

    // region Info
    suspend fun info(
        title: String,
        body: String? = null,
        testTag: String? = null,
    ) = emit(ToastType.INFO, title, body, testTag = testTag)

    suspend fun info(
        @StringRes titleRes: Int,
        @StringRes bodyRes: Int? = null,
        testTag: String? = null,
    ) = emit(
        type = ToastType.INFO,
        title = context.getString(titleRes),
        body = bodyRes?.let { context.getString(it) },
        testTag = testTag,
    )
    // endregion

    // region Lightning
    suspend fun lightning(
        title: String,
        body: String? = null,
        testTag: String? = null,
    ) = emit(ToastType.LIGHTNING, title, body, testTag = testTag)

    suspend fun lightning(
        @StringRes titleRes: Int,
        @StringRes bodyRes: Int? = null,
        testTag: String? = null,
    ) = emit(
        type = ToastType.LIGHTNING,
        title = context.getString(titleRes),
        body = bodyRes?.let { context.getString(it) },
        testTag = testTag,
    )
    // endregion

    // region Warning
    suspend fun warning(
        title: String,
        body: String? = null,
        testTag: String? = null,
    ) = emit(ToastType.WARNING, title, body, testTag = testTag)

    suspend fun warning(
        @StringRes titleRes: Int,
        @StringRes bodyRes: Int? = null,
        testTag: String? = null,
    ) = emit(
        type = ToastType.WARNING,
        title = context.getString(titleRes),
        body = bodyRes?.let { context.getString(it) },
        testTag = testTag,
    )
    // endregion

    // region Error
    suspend fun error(
        title: String,
        body: String? = null,
        testTag: String? = null,
    ) = emit(ToastType.ERROR, title, body, testTag = testTag)

    suspend fun error(
        @StringRes titleRes: Int,
        @StringRes bodyRes: Int? = null,
        testTag: String? = null,
    ) = emit(
        type = ToastType.ERROR,
        title = context.getString(titleRes),
        body = bodyRes?.let { context.getString(it) },
        testTag = testTag,
    )

    suspend fun error(throwable: Throwable) = emit(
        type = ToastType.ERROR,
        title = context.getString(R.string.common__error),
        body = throwable.message ?: context.getString(R.string.common__error_body),
    )
    // endregion
}

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
        description: String? = null,
        autoHide: Boolean = true,
        visibilityTime: Long = Toast.VISIBILITY_TIME_DEFAULT,
        testTag: String? = null,
    ) {
        _events.emit(
            Toast(
                type = type,
                title = title,
                description = description,
                autoHide = autoHide,
                visibilityTime = visibilityTime,
                testTag = testTag,
            )
        )
    }

    // region Success
    suspend fun success(
        title: String,
        description: String? = null,
        testTag: String? = null,
    ) = emit(ToastType.SUCCESS, title, description, testTag = testTag)

    suspend fun success(
        @StringRes titleRes: Int,
        @StringRes descriptionRes: Int? = null,
        testTag: String? = null,
    ) = emit(
        type = ToastType.SUCCESS,
        title = context.getString(titleRes),
        description = descriptionRes?.let { context.getString(it) },
        testTag = testTag,
    )
    // endregion

    // region Info
    suspend fun info(
        title: String,
        description: String? = null,
        testTag: String? = null,
    ) = emit(ToastType.INFO, title, description, testTag = testTag)

    suspend fun info(
        @StringRes titleRes: Int,
        @StringRes descriptionRes: Int? = null,
        testTag: String? = null,
    ) = emit(
        type = ToastType.INFO,
        title = context.getString(titleRes),
        description = descriptionRes?.let { context.getString(it) },
        testTag = testTag,
    )
    // endregion

    // region Lightning
    suspend fun lightning(
        title: String,
        description: String? = null,
        testTag: String? = null,
    ) = emit(ToastType.LIGHTNING, title, description, testTag = testTag)

    suspend fun lightning(
        @StringRes titleRes: Int,
        @StringRes descriptionRes: Int? = null,
        testTag: String? = null,
    ) = emit(
        type = ToastType.LIGHTNING,
        title = context.getString(titleRes),
        description = descriptionRes?.let { context.getString(it) },
        testTag = testTag,
    )
    // endregion

    // region Warning
    suspend fun warning(
        title: String,
        description: String? = null,
        testTag: String? = null,
    ) = emit(ToastType.WARNING, title, description, testTag = testTag)

    suspend fun warning(
        @StringRes titleRes: Int,
        @StringRes descriptionRes: Int? = null,
        testTag: String? = null,
    ) = emit(
        type = ToastType.WARNING,
        title = context.getString(titleRes),
        description = descriptionRes?.let { context.getString(it) },
        testTag = testTag,
    )
    // endregion

    // region Error
    suspend fun error(
        title: String,
        description: String? = null,
        testTag: String? = null,
    ) = emit(ToastType.ERROR, title, description, testTag = testTag)

    suspend fun error(
        @StringRes titleRes: Int,
        @StringRes descriptionRes: Int? = null,
        testTag: String? = null,
    ) = emit(
        type = ToastType.ERROR,
        title = context.getString(titleRes),
        description = descriptionRes?.let { context.getString(it) },
        testTag = testTag,
    )

    suspend fun error(throwable: Throwable) = emit(
        type = ToastType.ERROR,
        title = context.getString(R.string.common__error),
        description = throwable.message ?: context.getString(R.string.common__error_body),
    )
    // endregion
}

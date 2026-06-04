package to.bitkit.ui.screens.widgets

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import to.bitkit.data.WidgetsData
import to.bitkit.models.WidgetSize
import to.bitkit.models.WidgetType

/**
 * Tracks the widget size chosen in a preview sheet's size carousel. Before the user picks a size it
 * reflects the persisted size (or the type default for a not-yet-saved widget); once the user swipes
 * the carousel the draft takes over. [current] is read by the widget's save action.
 */
class WidgetSizeDraft(
    scope: CoroutineScope,
    private val type: WidgetType,
    widgetsDataFlow: StateFlow<WidgetsData>,
    subscriptionTimeoutMs: Long = SUBSCRIPTION_TIMEOUT,
) {
    private val default = WidgetSize.default(type)

    private val savedSize: StateFlow<WidgetSize> = widgetsDataFlow
        .map { data -> data.widgets.firstOrNull { it.type == type }?.size ?: default }
        .stateIn(scope, SharingStarted.WhileSubscribed(subscriptionTimeoutMs), default)

    private val _draft = MutableStateFlow<WidgetSize?>(null)

    val size: StateFlow<WidgetSize> = combine(_draft, savedSize) { draft, saved -> draft ?: saved }
        .stateIn(scope, SharingStarted.WhileSubscribed(subscriptionTimeoutMs), default)

    val current: WidgetSize get() = size.value

    fun set(value: WidgetSize) = _draft.update { value }

    companion object {
        private const val SUBSCRIPTION_TIMEOUT = 5000L
    }
}

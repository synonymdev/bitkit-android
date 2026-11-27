package to.bitkit.wear.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec

/**
 * A screen whose items scroll in a [TransformingLazyColumn], morphing at the round screen's edges, with an optional
 * [edgeButton] that collapses as the list scrolls. The rotary input scrolls the list. A screen that also holds a text
 * field passes [rotaryFocusRequester] and requests focus through it to hand the rotary back to the list.
 */
@Composable
fun ListScreen(
    modifier: Modifier = Modifier,
    rotaryFocusRequester: FocusRequester? = null,
    edgeButton: (@Composable BoxScope.() -> Unit)? = null,
    items: TransformingLazyColumnScope.(TransformationSpec) -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    if (edgeButton != null) {
        ScreenScaffold(scrollState = listState, edgeButton = edgeButton, modifier = modifier) { contentPadding ->
            List(listState, contentPadding, rotaryFocusRequester) { items(transformationSpec) }
        }
    } else {
        ScreenScaffold(scrollState = listState, modifier = modifier) { contentPadding ->
            List(listState, contentPadding, rotaryFocusRequester) { items(transformationSpec) }
        }
    }
}

@Composable
private fun List(
    state: TransformingLazyColumnState,
    contentPadding: PaddingValues,
    rotaryFocusRequester: FocusRequester?,
    items: TransformingLazyColumnScope.() -> Unit,
) {
    if (rotaryFocusRequester == null) {
        TransformingLazyColumn(state = state, contentPadding = contentPadding, content = items)
        return
    }
    TransformingLazyColumn(
        state = state,
        contentPadding = contentPadding,
        rotaryScrollableBehavior = null,
        content = items,
        modifier = Modifier.rotaryScrollable(
            behavior = RotaryScrollableDefaults.behavior(state),
            focusRequester = rotaryFocusRequester,
        )
    )
}

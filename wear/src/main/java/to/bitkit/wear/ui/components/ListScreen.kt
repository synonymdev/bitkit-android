package to.bitkit.wear.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScreenScaffoldDefaults
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec

/** Bottom space a page of a `HorizontalPagerScaffold` keeps clear for the page indicator drawn over it. */
val PageIndicatorPadding = 20.dp

/**
 * Extra bottom padding for a [ListScreen]: [PageIndicatorPadding] on a page of a `HorizontalPagerScaffold`, whose
 * page indicator overlays the bottom edge, so the last item can scroll clear of it. None elsewhere. A screen with an
 * edge button keeps the padding its scaffold gives it.
 */
val LocalListBottomInset = staticCompositionLocalOf { 0.dp }

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
        val padding = ScreenScaffoldDefaults.contentPadding.plusBottom(LocalListBottomInset.current)
        ScreenScaffold(scrollState = listState, contentPadding = padding, modifier = modifier) { contentPadding ->
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

@Composable
private fun PaddingValues.plusBottom(extra: Dp): PaddingValues {
    if (extra == 0.dp) return this
    val layoutDirection = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(layoutDirection),
        top = calculateTopPadding(),
        end = calculateEndPadding(layoutDirection),
        bottom = calculateBottomPadding() + extra,
    )
}

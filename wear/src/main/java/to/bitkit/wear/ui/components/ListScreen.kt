package to.bitkit.wear.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec

/**
 * A screen whose items scroll in a [TransformingLazyColumn], morphing at the round screen's edges, with an optional
 * [edgeButton] that collapses as the list scrolls.
 */
@Composable
fun ListScreen(
    modifier: Modifier = Modifier,
    edgeButton: (@Composable BoxScope.() -> Unit)? = null,
    items: TransformingLazyColumnScope.(TransformationSpec) -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    if (edgeButton != null) {
        ScreenScaffold(scrollState = listState, edgeButton = edgeButton, modifier = modifier) { contentPadding ->
            TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
                items(transformationSpec)
            }
        }
    } else {
        ScreenScaffold(scrollState = listState, modifier = modifier) { contentPadding ->
            TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
                items(transformationSpec)
            }
        }
    }
}

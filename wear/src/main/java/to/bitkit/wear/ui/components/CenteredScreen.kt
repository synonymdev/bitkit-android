package to.bitkit.wear.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.ScreenScaffold

/** The share of the screen width kept clear on each side, so centered content fits inside a round screen. */
private const val SIDE_INSET_FRACTION = 0.12f

/** The share of the screen height kept clear at the top for the time, and at the bottom when no edge button shows. */
private const val VERTICAL_INSET_FRACTION = 0.18f

/** The share of the screen height an edge button takes at the bottom. */
private const val EDGE_BUTTON_FRACTION = 0.3f

/**
 * A screen with one focal group of content centered inside the round screen, clear of the time at the top, and an
 * optional [edgeButton] hugging the bottom edge as the screen's primary action. [overlay] draws over the whole
 * screen, such as [BezelLoading].
 */
@Composable
fun CenteredScreen(
    modifier: Modifier = Modifier,
    sideInsetFraction: Float = SIDE_INSET_FRACTION,
    edgeButton: (@Composable () -> Unit)? = null,
    overlay: (@Composable BoxWithConstraintsScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ScreenScaffold(modifier = modifier) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val bottomInset = if (edgeButton != null) EDGE_BUTTON_FRACTION else VERTICAL_INSET_FRACTION
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                content = content,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = maxWidth * sideInsetFraction,
                        end = maxWidth * sideInsetFraction,
                        top = maxHeight * VERTICAL_INSET_FRACTION,
                        bottom = maxHeight * bottomInset,
                    )
            )
            edgeButton?.let {
                Box(modifier = Modifier.align(Alignment.BottomCenter)) { it() }
            }
            overlay?.invoke(this)
        }
    }
}

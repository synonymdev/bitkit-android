package to.bitkit.ui.scaffold

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import to.bitkit.ui.shared.util.screen

@Composable
fun ScreenColumn(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    noBackground: Boolean = false,
    clipToBounds: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment,
        content = content,
        modifier = Modifier
            .screen(
                noBackground = noBackground,
                clipToBounds = clipToBounds,
            )
            .then(modifier)
    )
}

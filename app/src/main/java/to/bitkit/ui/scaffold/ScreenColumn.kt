package to.bitkit.ui.scaffold

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import to.bitkit.ui.theme.Colors

@Composable
fun ScreenColumn(
    modifier: Modifier = Modifier,
    noBackground: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        content = content,
        modifier = Modifier
            .systemBarsPadding()
            .fillMaxSize()
            .then(if (noBackground) Modifier else Modifier.background(Colors.Black))
            .then(modifier)
    )
}

package to.bitkit.ui.scaffold

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun ScreenColumn(
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        content = content,
        modifier = Modifier
            .systemBarsPadding()
            .fillMaxSize()
    )
}

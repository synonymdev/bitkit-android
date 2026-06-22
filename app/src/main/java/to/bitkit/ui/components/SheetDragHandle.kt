package to.bitkit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun SheetDragHandle(
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Colors.White32,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier
            .padding(top = 12.dp)
    ) {
        Box(Modifier.size(width = 32.dp, height = 4.dp))
    }
}

@Composable
@Preview(showBackground = true)
private fun Preview() {
    AppThemeSurface {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .background(color = Colors.Gray6)
        ) {
            SheetDragHandle()
        }
    }
}

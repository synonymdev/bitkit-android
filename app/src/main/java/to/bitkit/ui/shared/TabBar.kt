package to.bitkit.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.ui.Routes
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun TabBar(
    onSendClicked: () -> Unit,
    onReceiveClicked: () -> Unit,
    onScanClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val buttonColors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    )
    val contentPadding = PaddingValues(0.dp, 12.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
    ) {
        Row {
            Button(
                onClick = onSendClicked,
                shape = RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50),
                colors = buttonColors,
                contentPadding = contentPadding,
                elevation = null,
                modifier = Modifier.weight(1f)
            ) {
                Text("Send")
            }
            Button(
                onClick = onReceiveClicked,
                shape = RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50),
                colors = buttonColors,
                contentPadding = contentPadding,
                elevation = null,
                modifier = Modifier.weight(1f)
            ) {
                Text("Receive")
            }
        }
        IconButton(
            onClick = onScanClicked,
            modifier = Modifier
                .size(60.dp)
                .border(1.dp, buttonColors.containerColor, MaterialTheme.shapes.large)
        ) {
            Icon(
                imageVector = Icons.Default.CenterFocusWeak,
                contentDescription = "Scan",
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large)
                    .padding(16.dp)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TabBarPreview() {
    AppThemeSurface {
        TabBar(
            onSendClicked = {},
            onReceiveClicked = {},
            onScanClicked = {},
        )
    }
}

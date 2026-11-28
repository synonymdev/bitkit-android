package to.bitkit.wear.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

/** A title and a message, for screens with nothing to act on. */
@Composable
fun MessageScreen(
    title: String,
    message: String,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    CenteredScreen(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("${testTag}Title")
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("${testTag}Text")
        )
    }
}

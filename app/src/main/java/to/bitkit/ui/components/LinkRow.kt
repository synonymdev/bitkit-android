package to.bitkit.ui.components

import android.content.Intent
import android.net.Uri
import android.util.Patterns
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.isValidEmail
import to.bitkit.utils.Logger

private const val TAG = "LinkRow"

@Composable
fun LinkRow(
    label: String,
    value: String,
    linkIndex: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val uri = remember(value) { value.toLinkUri() }

    Column(modifier = modifier.fillMaxWidth()) {
        VerticalSpacer(16.dp)
        Text13Up(
            text = label,
            color = Colors.White64,
            modifier = Modifier.testTag("ProfileLinkLabel_$linkIndex"),
        )
        VerticalSpacer(8.dp)
        BodySSB(
            text = value,
            modifier = Modifier
                .clickableAlpha(
                    onClick = uri?.let { linkUri ->
                        {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, linkUri)) }
                                .onFailure { Logger.warn("Failed to open link '$linkUri'", it, context = TAG) }
                        }
                    }
                )
                .testTag("ProfileLinkValue_$linkIndex"),
        )
        VerticalSpacer(16.dp)
        HorizontalDivider()
    }
}

private fun String.toLinkUri(): Uri? {
    val trimmed = trim()
    if (trimmed.isValidEmail()) return "mailto:$trimmed".toUri()
    if (!Patterns.WEB_URL.matcher(trimmed).matches()) return null
    val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    return withScheme.toUri().takeIf { it.scheme == "http" || it.scheme == "https" }
}

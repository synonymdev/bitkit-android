package to.bitkit.ui.components.settings

import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import to.bitkit.R
import to.bitkit.env.Env

private data class SocialLink(@DrawableRes val iconRes: Int, val url: String)

private val socialLinks = listOf(
    SocialLink(R.drawable.ic_globe, Env.BITKIT_WEBSITE),
    SocialLink(R.drawable.ic_medium, Env.SYNONYM_MEDIUM),
    SocialLink(R.drawable.ic_x_twitter, Env.SYNONYM_X),
    SocialLink(R.drawable.ic_discord, Env.BITKIT_DISCORD),
    SocialLink(R.drawable.ic_telegram, Env.BITKIT_TELEGRAM),
    SocialLink(R.drawable.ic_github, Env.BITKIT_GITHUB),
)

@Composable
fun Links(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
    ) {
        socialLinks.forEach { link ->
            IconButton(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, link.url.toUri()))
                },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    painter = painterResource(link.iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

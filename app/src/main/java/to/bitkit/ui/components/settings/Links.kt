package to.bitkit.ui.components.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.ui.theme.Colors

@Composable
fun Links(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    FlowRow(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier.fillMaxWidth()
    ) {
        FloatingActionButton(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Env.BITKIT_WEBSITE.toUri())
                context.startActivity(intent)
            },
            containerColor = Colors.White16,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_globe),
                contentDescription = null,
                tint = Colors.White
            )
        }
        FloatingActionButton(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Env.SYNONYM_MEDIUM.toUri())
                context.startActivity(intent)
            },
            containerColor = Colors.White16,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_medium),
                contentDescription = null,
                tint = Colors.White
            )
        }
        FloatingActionButton(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Env.SYNONYM_X.toUri())
                context.startActivity(intent)
            },
            containerColor = Colors.White16,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_x_twitter),
                contentDescription = null,
                tint = Colors.White
            )
        }
        FloatingActionButton(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Env.BITKIT_DISCORD.toUri())
                context.startActivity(intent)
            },
            containerColor = Colors.White16,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_discord),
                contentDescription = null,
                tint = Colors.White
            )
        }
        FloatingActionButton(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Env.BITKIT_TELEGRAM.toUri())
                context.startActivity(intent)
            },
            containerColor = Colors.White16,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_telegram),
                contentDescription = null,
                tint = Colors.White
            )
        }
        FloatingActionButton(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Env.BITKIT_GITHUB.toUri())
                context.startActivity(intent)
            },
            containerColor = Colors.White16,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_github),
                contentDescription = null,
                tint = Colors.White
            )
        }
    }
}

package to.bitkit.appwidget.ui.facts

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.Text
import to.bitkit.R
import to.bitkit.appwidget.ui.components.CaptionB
import to.bitkit.appwidget.ui.components.GlanceLayoutDimens
import to.bitkit.appwidget.ui.components.GlanceWidgetScaffold
import to.bitkit.appwidget.ui.theme.GlanceTextStyles
import to.bitkit.ui.MainActivity

private val BADGE_SIZE = 32.dp
private val BADGE_RESERVED_END = 40.dp

@Suppress("RestrictedApi")
@Composable
fun FactsGlanceContent(
    fact: String?,
) {
    val context = LocalContext.current
    val openAppIntent = Intent(context, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    GlanceWidgetScaffold(onClick = actionStartActivity(openAppIntent)) {
        if (fact == null) {
            CaptionB(text = context.getString(R.string.appwidget__loading))
            return@GlanceWidgetScaffold
        }

        val size = LocalSize.current
        val isWide = size.width >= GlanceLayoutDimens.WIDE_LAYOUT_MIN_WIDTH
        val isTall = size.height >= GlanceLayoutDimens.TALL_LAYOUT_MIN_HEIGHT

        Box(
            contentAlignment = if (isWide) Alignment.TopEnd else Alignment.BottomEnd,
            modifier = GlanceModifier.fillMaxSize()
        ) {
            Text(
                text = fact,
                style = if (isWide) GlanceTextStyles.title22 else GlanceTextStyles.bodyMSB,
                maxLines = when {
                    isWide && !isTall -> 2
                    isWide -> 5
                    else -> 5
                },
                modifier = if (isWide) {
                    GlanceModifier.fillMaxSize().padding(end = BADGE_RESERVED_END)
                } else {
                    GlanceModifier.fillMaxSize()
                }
            )
            BitcoinBadge()
        }
    }
}

@Composable
private fun BitcoinBadge() {
    Image(
        provider = ImageProvider(R.drawable.ic_bitcoin_badge),
        contentDescription = null,
        modifier = GlanceModifier.size(BADGE_SIZE)
    )
}

package to.bitkit.appwidget.ui.components

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import to.bitkit.appwidget.ui.theme.GlanceColors

@Composable
fun GlanceWidgetScaffold(
    onClick: Intent? = null,
    content: @Composable () -> Unit,
) {
    val modifier = GlanceModifier
        .fillMaxSize()
        .cornerRadius(16.dp)
        .background(GlanceColors.cardBackgroundProvider)
        .padding(16.dp)
        .let { mod ->
            if (onClick != null) mod.clickable(actionStartActivity(onClick)) else mod
        }

    Column(modifier = modifier) {
        content()
    }
}

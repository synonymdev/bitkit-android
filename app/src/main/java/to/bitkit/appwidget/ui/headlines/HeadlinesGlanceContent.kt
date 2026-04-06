package to.bitkit.appwidget.ui.headlines

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.Text
import to.bitkit.R
import to.bitkit.appwidget.model.AppWidgetEntry
import to.bitkit.appwidget.ui.components.GlanceWidgetScaffold
import to.bitkit.appwidget.ui.theme.GlanceColors
import to.bitkit.appwidget.ui.theme.GlanceTextStyles
import to.bitkit.data.dto.ArticleDTO

@Composable
fun HeadlinesGlanceContent(
    context: Context,
    articles: List<ArticleDTO>,
    entry: AppWidgetEntry,
) {
    val prefs = entry.headlinesPreferences
    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)

    GlanceWidgetScaffold(onClick = launchIntent) {
        Text(
            text = context.getString(R.string.widgets__news__name),
            style = GlanceTextStyles.bodyMSB,
        )

        Spacer(modifier = GlanceModifier.height(8.dp))

        if (articles.isEmpty()) {
            Text(
                text = context.getString(R.string.appwidget__loading),
                style = GlanceTextStyles.captionB,
            )
            return@GlanceWidgetScaffold
        }

        val displayArticles = articles.take(3)
        for ((index, article) in displayArticles.withIndex()) {
            Column(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(
                    text = article.title,
                    style = GlanceTextStyles.captionB.copy(color = GlanceColors.textPrimary),
                    maxLines = 2,
                )
                if (prefs.showTime || prefs.showSource) {
                    val meta = buildString {
                        if (prefs.showTime) append(article.publishedDate)
                        if (prefs.showTime && prefs.showSource) append(" · ")
                        if (prefs.showSource) append(article.publisher.title)
                    }
                    Text(
                        text = meta,
                        style = GlanceTextStyles.source,
                        maxLines = 1,
                    )
                }
            }
            if (index < displayArticles.lastIndex) {
                Spacer(modifier = GlanceModifier.height(4.dp))
            }
        }
    }
}

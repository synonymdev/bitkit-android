package to.bitkit.appwidget.ui.headlines

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import to.bitkit.R
import to.bitkit.appwidget.model.AppWidgetEntry
import to.bitkit.appwidget.ui.components.GlanceWidgetScaffold
import to.bitkit.appwidget.ui.theme.GlanceColors
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
            style = TextStyle(
                color = GlanceColors.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            ),
        )

        Spacer(modifier = GlanceModifier.height(8.dp))

        if (articles.isEmpty()) {
            Text(
                text = context.getString(R.string.appwidget__loading),
                style = TextStyle(
                    color = GlanceColors.textSecondary,
                    fontSize = 13.sp,
                ),
            )
            return@GlanceWidgetScaffold
        }

        val displayArticles = articles.take(3)
        for ((index, article) in displayArticles.withIndex()) {
            Column(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(
                    text = article.title,
                    style = TextStyle(
                        color = GlanceColors.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    ),
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
                        style = TextStyle(
                            color = GlanceColors.textTertiary,
                            fontSize = 11.sp,
                        ),
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

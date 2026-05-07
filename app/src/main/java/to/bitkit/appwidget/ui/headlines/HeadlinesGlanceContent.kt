package to.bitkit.appwidget.ui.headlines

import android.appwidget.AppWidgetManager
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.HeightModifier
import androidx.glance.layout.Row
import androidx.glance.layout.WidthModifier
import androidx.glance.layout.fillMaxWidth
import androidx.glance.text.Text
import androidx.glance.unit.Dimension
import to.bitkit.R
import to.bitkit.appwidget.config.AppWidgetConfigActivity
import to.bitkit.appwidget.model.AppWidgetEntry
import to.bitkit.appwidget.model.AppWidgetType
import to.bitkit.appwidget.model.HomeHeadlinePreferences
import to.bitkit.appwidget.ui.components.BodySSB
import to.bitkit.appwidget.ui.components.CaptionB
import to.bitkit.appwidget.ui.components.GlanceLayoutDimens
import to.bitkit.appwidget.ui.components.GlanceWidgetScaffold
import to.bitkit.appwidget.ui.components.VerticalSpacer
import to.bitkit.appwidget.ui.theme.GlanceColors
import to.bitkit.appwidget.ui.theme.GlanceTextStyles
import to.bitkit.models.widget.ArticleModel
import to.bitkit.models.widget.safeBrowserUri
import to.bitkit.ui.theme.Colors

@Suppress("RestrictedApi")
@Composable
fun HeadlinesGlanceContent(
    entry: AppWidgetEntry,
    article: ArticleModel?,
) {
    val context = LocalContext.current
    val articleUri = article?.safeBrowserUri()
    val tapIntent = if (articleUri != null) {
        Intent(Intent.ACTION_VIEW, articleUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    } else {
        Intent(context, AppWidgetConfigActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, entry.appWidgetId)
            putExtra(AppWidgetConfigActivity.EXTRA_WIDGET_TYPE, AppWidgetType.HEADLINES.name)
        }
    }

    GlanceWidgetScaffold(onClick = tapIntent) {
        if (article == null) {
            CaptionB(text = context.getString(R.string.appwidget__loading))
            return@GlanceWidgetScaffold
        }

        if (LocalSize.current.width >= GlanceLayoutDimens.WIDE_LAYOUT_MIN_WIDTH) {
            WideContent(article = article, preferences = entry.headlinePreferences)
        } else {
            CompactContent(article = article, preferences = entry.headlinePreferences)
        }
    }
}

@Suppress("RestrictedApi")
@Composable
private fun WideContent(article: ArticleModel, preferences: HomeHeadlinePreferences) {
    Text(
        text = article.title,
        style = GlanceTextStyles.title22,
        maxLines = 4,
        modifier = GlanceModifier
            .fillMaxWidth()
            .then(HeightModifier(Dimension.Expand))
    )

    val timeVisible = preferences.showTime && article.timeAgo.isNotEmpty()
    if (!preferences.showSource && !timeVisible) return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = GlanceModifier.fillMaxWidth()
    ) {
        if (preferences.showSource) {
            BodySSB(
                text = article.publisher,
                color = ColorProvider(day = Colors.Brand, night = Colors.Brand),
                modifier = if (timeVisible) GlanceModifier.then(WidthModifier(Dimension.Expand)) else GlanceModifier
            )
        }
        if (timeVisible) {
            BodySSB(
                text = article.timeAgo,
                color = GlanceColors.textSecondary
            )
        }
    }
}

@Suppress("RestrictedApi")
@Composable
private fun CompactContent(article: ArticleModel, preferences: HomeHeadlinePreferences) {
    Text(
        text = article.title,
        style = GlanceTextStyles.title22,
        maxLines = 4,
        modifier = GlanceModifier
            .fillMaxWidth()
            .then(HeightModifier(Dimension.Expand))
    )

    val timeVisible = preferences.showTime && article.timeAgo.isNotEmpty()
    if (!timeVisible) return

    VerticalSpacer(16.dp)
    Row(
        horizontalAlignment = Alignment.End,
        modifier = GlanceModifier.fillMaxWidth()
    ) {
        BodySSB(
            text = article.timeAgo,
            color = GlanceColors.textSecondary
        )
    }
}

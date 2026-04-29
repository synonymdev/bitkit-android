package to.bitkit.ui.screens.widgets.headlines

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.models.widget.ArticleModel
import to.bitkit.models.widget.HeadlinePreferences
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsButtonValue
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.widgets.components.WidgetSizeCarousel
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun HeadlinesPreviewScreen(
    headlinesViewModel: HeadlinesViewModel,
    onClose: () -> Unit,
    onBack: () -> Unit,
    navigateEditWidget: () -> Unit,
) {
    val customHeadlinePreferences by headlinesViewModel.customPreferences.collectAsStateWithLifecycle()
    val article by headlinesViewModel.currentArticle.collectAsStateWithLifecycle()
    val isHeadlinesImplemented by headlinesViewModel.isNewsWidgetEnabled.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        headlinesViewModel.refreshOnDisplay()
    }

    HeadlinesPreviewContent(
        onBack = onBack,
        isHeadlinesImplemented = isHeadlinesImplemented,
        headlinePreferences = customHeadlinePreferences,
        article = article,
        onClickEdit = navigateEditWidget,
        onClickDelete = {
            headlinesViewModel.removeWidget()
            onClose()
        },
        onClickSave = {
            headlinesViewModel.savePreferences()
            onClose()
        },
    )
}

@Composable
fun HeadlinesPreviewContent(
    onBack: () -> Unit,
    onClickEdit: () -> Unit,
    onClickDelete: () -> Unit,
    onClickSave: () -> Unit,
    isHeadlinesImplemented: Boolean,
    headlinePreferences: HeadlinePreferences,
    article: ArticleModel,
) {
    ScreenColumn(
        noBackground = true,
        modifier = Modifier
            .background(Colors.Gray7)
            .testTag("headlines_preview_screen")
    ) {
        AppTopBar(
            titleText = stringResource(R.string.widgets__news__name),
            onBackClick = onBack,
        )

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .weight(1f)
        ) {
            VerticalSpacer(16.dp)

            BodyM(
                text = stringResource(R.string.widgets__news__description),
                color = Colors.White64,
                modifier = Modifier.testTag("widget_description")
            )

            VerticalSpacer(16.dp)

            HorizontalDivider(
                modifier = Modifier.testTag("divider")
            )

            SettingsButtonRow(
                title = stringResource(R.string.widgets__widget__settings),
                value = SettingsButtonValue.StringValue(
                    if (headlinePreferences == HeadlinePreferences()) {
                        stringResource(R.string.widgets__widget__edit_default)
                    } else {
                        stringResource(R.string.widgets__widget__edit_custom)
                    },
                ),
                onClick = onClickEdit,
                modifier = Modifier.testTag("WidgetEdit")
            )

            WidgetSizeCarousel(
                smallContent = {
                    HeadlineCardSmall(
                        showTime = headlinePreferences.showTime,
                        time = article.timeAgo,
                        headline = article.title,
                        link = article.link,
                        modifier = Modifier.testTag("headline_card_small")
                    )
                },
                wideContent = {
                    HeadlineCard(
                        showTime = headlinePreferences.showTime,
                        showSource = headlinePreferences.showSource,
                        time = article.timeAgo,
                        headline = article.title,
                        source = article.publisher,
                        link = article.link,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("headline_card_wide")
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("headlines_preview_carousel")
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 16.dp,
                    top = 22.dp,
                )
                .fillMaxWidth()
                .testTag("buttons_row")
        ) {
            if (isHeadlinesImplemented) {
                SecondaryButton(
                    text = stringResource(R.string.common__delete),
                    fullWidth = false,
                    onClick = onClickDelete,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("WidgetDelete")
                )
            }

            PrimaryButton(
                text = stringResource(R.string.widgets__widget__save),
                fullWidth = false,
                onClick = onClickSave,
                modifier = Modifier
                    .weight(1f)
                    .testTag("WidgetSave")
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        HeadlinesPreviewContent(
            onBack = {},
            onClickEdit = {},
            onClickDelete = {},
            onClickSave = {},
            headlinePreferences = HeadlinePreferences(),
            article = ArticleModel(
                timeAgo = "21 minutes ago",
                title = "How Bitcoin changed El Salvador in more ways",
                publisher = "bitcoinmagazine.com",
                link = "bitcoinmagazine.com",
            ),
            isHeadlinesImplemented = false
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview2() {
    AppThemeSurface {
        HeadlinesPreviewContent(
            onBack = {},
            onClickEdit = {},
            onClickDelete = {},
            onClickSave = {},
            headlinePreferences = HeadlinePreferences(showTime = false, showSource = false),
            article = ArticleModel(
                timeAgo = "21 minutes ago",
                title = "How Bitcoin changed El Salvador in more ways",
                publisher = "bitcoinmagazine.com",
                link = "bitcoinmagazine.com",
            ),
            isHeadlinesImplemented = true
        )
    }
}

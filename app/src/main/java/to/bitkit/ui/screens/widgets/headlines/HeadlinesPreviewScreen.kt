package to.bitkit.ui.screens.widgets.headlines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.models.widget.ArticleModel
import to.bitkit.models.widget.HeadlinePreferences
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Headline
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsButtonValue
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors


@Composable
fun HeadlinesPreviewScreen(
    headlinesViewModel: HeadlinesViewModel,
    onClose: () -> Unit,
    onBack: () -> Unit,
    navigateEditWidget: () -> Unit,
) {
    val showWidgetTitles by headlinesViewModel.showWidgetTitles.collectAsStateWithLifecycle()
    val customHeadlinePreferences by headlinesViewModel.customPreferences.collectAsStateWithLifecycle()
    val article by headlinesViewModel.currentArticle.collectAsStateWithLifecycle()
    val isHeadlinesImplemented by headlinesViewModel.isHeadlinesImplemented.collectAsStateWithLifecycle()

    HeadlinesPreviewContent(
        onClose = onClose,
        onBack = onBack,
        isHeadlinesImplemented = isHeadlinesImplemented,
        headlinePreferences = customHeadlinePreferences,
        showWidgetTitles = showWidgetTitles,
        article = article,
        onClickEdit = navigateEditWidget,
        onClickDelete = {
            headlinesViewModel.deleteWidget()
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
    onClose: () -> Unit,
    onBack: () -> Unit,
    onClickEdit: () -> Unit,
    onClickDelete: () -> Unit,
    onClickSave: () -> Unit,
    showWidgetTitles: Boolean,
    isHeadlinesImplemented: Boolean,
    headlinePreferences: HeadlinePreferences,
    article: ArticleModel
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.widgets__widget__nav_title),
            onBackClick = onBack,
            actions = { CloseNavIcon(onClick = onClose) },
        )

        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(26.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Headline(
                    text = AnnotatedString(stringResource(R.string.widgets__news__name)),
                    modifier = Modifier.width(263.dp)
                )
                Icon(
                    painter = painterResource(R.drawable.widget_newspaper),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(64.dp)
                )
            }

            BodyM(
                text = stringResource(R.string.widgets__news__description),
                color = Colors.White64,
                modifier = Modifier.padding(vertical = 16.dp)
            )

            HorizontalDivider()

            SettingsButtonRow(
                title = stringResource(R.string.widgets__widget__edit),
                value = SettingsButtonValue.StringValue(
                    if (headlinePreferences.showTime && headlinePreferences.showSource) {
                        stringResource(R.string.widgets__widget__edit_default)
                    } else {
                        stringResource(R.string.widgets__widget__edit_custom)
                    }
                ),
                onClick = onClickEdit
            )

            Spacer(modifier = Modifier.weight(1f))

            Text13Up(
                stringResource(R.string.common__preview),
                color = Colors.White64,
                modifier = Modifier.padding(vertical = 16.dp)
            )

            HeadlineCard(
                modifier = Modifier.fillMaxWidth(),
                showWidgetTitle = showWidgetTitles,
                showTime = headlinePreferences.showTime,
                showSource = headlinePreferences.showSource,
                time = article.timeAgo,
                headline = article.title,
                source = article.publisher,
                link = article.link
            )

            Row(
                modifier = Modifier
                    .padding(vertical = 21.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (isHeadlinesImplemented) {
                    SecondaryButton(
                        text = stringResource(R.string.common__delete),
                        modifier = Modifier.weight(1f),
                        fullWidth = false,
                        onClick = onClickDelete
                    )
                }

                PrimaryButton(
                    text = stringResource(R.string.common__save),
                    modifier = Modifier.weight(1f),
                    fullWidth = false,
                    onClick = onClickSave
                )
            }
        }
    }
}


@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        HeadlinesPreviewContent(
            onClose = {},
            onBack = {},
            showWidgetTitles = true,
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
            onClose = {},
            onBack = {},
            showWidgetTitles = false,
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

package to.bitkit.ui.screens.widgets.headlines

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.models.widget.ArticleModel
import to.bitkit.models.widget.HeadlinePreferences
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.Title
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun HeadlinesEditScreen(
    headlinesViewModel: HeadlinesViewModel,
    onBack: () -> Unit,
    navigatePreview: () -> Unit
) {
    val customHeadlinePreferences by headlinesViewModel.customPreferences.collectAsStateWithLifecycle()
    val article by headlinesViewModel.currentArticle.collectAsStateWithLifecycle()

    HeadlinesEditContent(
        onBack = onBack,
        headlinePreferences = customHeadlinePreferences,
        article = article,
        onClickTime = {
            headlinesViewModel.toggleShowTime()
        },
        onClickShowSource = {
            headlinesViewModel.toggleShowSource()
        },
        onClickReset = {
            headlinesViewModel.resetCustomPreferences()
        },
        onClickPreview = {
            navigatePreview()
        },
    )
}

@Composable
fun HeadlinesEditContent(
    onBack: () -> Unit,
    onClickTime: () -> Unit,
    onClickReset: () -> Unit,
    onClickPreview: () -> Unit,
    onClickShowSource: () -> Unit,
    headlinePreferences: HeadlinePreferences,
    article: ArticleModel
) {
    ScreenColumn(
        noBackground = true,
        modifier = Modifier
            .background(Colors.Gray7)
            .testTag("headlines_edit_screen")
    ) {
        AppTopBar(
            titleText = stringResource(R.string.widgets__news__name),
            onBackClick = onBack,
            actions = { DrawerNavIcon() },
        )

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .testTag("WidgetEditScrollView")
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Caption13Up(
                text = stringResource(R.string.widgets__widget__content),
                color = Colors.White64,
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .testTag("content_section_header")
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .fillMaxWidth()
                    .testTag("title_setting_row")
            ) {
                Title(
                    text = article.title,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("title_text")
                )

                IconButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.testTag("title_toggle_button")
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_checkmark),
                        contentDescription = null,
                        tint = Colors.Brand,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("title_toggle_icon"),
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.testTag("title_divider")
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .fillMaxWidth()
                    .testTag("source_setting_row")
            ) {
                BodySSB(
                    text = article.publisher,
                    color = Colors.Brand,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("source_text")
                )

                IconButton(
                    onClick = onClickShowSource,
                    modifier = Modifier.testTag("source_toggle_button")
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_checkmark),
                        contentDescription = null,
                        tint = if (headlinePreferences.showSource) Colors.Brand else Colors.White50,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("source_toggle_icon"),
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.testTag("source_divider")
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .fillMaxWidth()
                    .testTag("time_setting_row")
            ) {
                BodySSB(
                    text = article.timeAgo,
                    color = Colors.White64,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("time_text")
                )

                IconButton(
                    onClick = onClickTime,
                    modifier = Modifier.testTag("time_toggle_button")
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_checkmark),
                        contentDescription = null,
                        tint = if (headlinePreferences.showTime) Colors.Brand else Colors.White50,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("time_toggle_icon"),
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.testTag("time_divider")
            )

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier
                    .padding(vertical = 21.dp)
                    .fillMaxWidth()
                    .testTag("buttons_row"),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SecondaryButton(
                    text = stringResource(R.string.common__reset),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("WidgetEditReset"),
                    enabled = !headlinePreferences.showSource || !headlinePreferences.showTime,
                    fullWidth = false,
                    onClick = onClickReset
                )

                PrimaryButton(
                    text = stringResource(R.string.common__preview),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("WidgetEditPreview"),
                    fullWidth = false,
                    onClick = onClickPreview
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        HeadlinesEditContent(
            onBack = {},
            onClickShowSource = {},
            onClickTime = {},
            onClickReset = {},
            onClickPreview = {},
            headlinePreferences = HeadlinePreferences(),
            article = ArticleModel(
                timeAgo = "21 minutes ago",
                title = "How Bitcoin changed El Salvador in more ways",
                publisher = "bitcoinmagazine.com",
                link = "bitcoinmagazine.com",
            )
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview2() {
    AppThemeSurface {
        HeadlinesEditContent(
            onBack = {},
            onClickShowSource = {},
            onClickTime = {},
            onClickReset = {},
            onClickPreview = {},
            headlinePreferences = HeadlinePreferences(showTime = false, showSource = false),
            article = ArticleModel(
                timeAgo = "21 minutes ago",
                title = "How Bitcoin changed El Salvador in more ways",
                publisher = "bitcoinmagazine.com",
                link = "bitcoinmagazine.com",
            )
        )
    }
}

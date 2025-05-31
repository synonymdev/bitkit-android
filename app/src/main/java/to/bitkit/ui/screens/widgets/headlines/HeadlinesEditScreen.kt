package to.bitkit.ui.screens.widgets.headlines

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.models.widget.ArticleModel
import to.bitkit.models.widget.HeadlinePreferences
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsButtonValue
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors


@Composable
fun HeadlinesSettingsScreen(
    headlinesViewModel: HeadlinesViewModel = hiltViewModel(),
    onClose: () -> Unit,
    onBack: () -> Unit,
) {
    val headlinePreferences by headlinesViewModel.headlinePreferences.collectAsStateWithLifecycle()
    val article by headlinesViewModel.currentArticle.collectAsStateWithLifecycle()
    HeadlinesSettingsContent(
        onClose = onClose,
        onBack = onBack,
        headlinePreferences = headlinePreferences,
        article = article
    )
}

@Composable
fun HeadlinesSettingsContent(
    onClose: () -> Unit,
    onBack: () -> Unit,
    headlinePreferences: HeadlinePreferences,
    article: ArticleModel
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.widgets__widget__edit),
            onBackClick = onBack,
            actions = { CloseNavIcon(onClick = onClose) },
        )

        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            BodyM(text = stringResource(R.string.widgets__widget__edit_description), color = Colors.White64)

            Spacer(modifier = Modifier.height(32.dp))

            SettingsButtonRow(
                title = article.timeAgo,
                value = SettingsButtonValue.BooleanValue(headlinePreferences.showTime),
                onClick = {}
            )

            SettingsButtonRow(
                title = article.title,
                value = SettingsButtonValue.BooleanValue(true),
                enabled = false,
                onClick = {}
            )


            SettingsButtonRow(
                title = article.title,
                value = SettingsButtonValue.BooleanValue(true),
                enabled = false,
                onClick = {}
            )


        }
    }
}


@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        HeadlinesSettingsContent(
            onClose = {},
            onBack = {},
            headlinePreferences = HeadlinePreferences(),
            article = ArticleModel(
                title = "21 minutes ago",
                timeAgo = "How Bitcoin changed El Salvador in more ways",
                publisher = "bitcoinmagazine.com",
                link = "bitcoinmagazine.com",
            )
        )
    }
}

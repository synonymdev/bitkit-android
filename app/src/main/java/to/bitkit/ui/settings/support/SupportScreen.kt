package to.bitkit.ui.settings.support

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import to.bitkit.BuildConfig
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.models.Toast
import to.bitkit.ui.Routes
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.settings.Links
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsButtonValue
import to.bitkit.ui.navigateTo
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.settingsViewModel
import to.bitkit.ui.shared.util.shareText
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

private const val DEV_MODE_TAP_THRESHOLD = 5

@Composable
fun SupportScreen(
    navController: NavController,
) {
    val context = LocalContext.current
    val app = appViewModel ?: return
    val settings = settingsViewModel ?: return
    val isDevModeEnabled by settings.isDevModeEnabled.collectAsStateWithLifecycle()
    var devModeTapCount by remember { mutableIntStateOf(0) }
    val haptic = LocalHapticFeedback.current

    Content(
        onBack = { navController.popBackStack() },
        onClickReportIssue = { navController.navigateTo(Routes.ReportIssue) },
        onClickHelpCenter = {
            val intent = Intent(Intent.ACTION_VIEW, Env.BITKIT_HELP_CENTER.toUri())
            context.startActivity(intent)
        },
        onClickAppStatus = { navController.navigateTo(Routes.AppStatus) },
        onClickLegal = {
            val intent = Intent(Intent.ACTION_VIEW, Env.TERMS_OF_USE_URL.toUri())
            context.startActivity(intent)
        },
        onClickShare = {
            shareText(
                context,
                context.getString(R.string.settings__about__shareText)
                    .replace("{appStoreUrl}", Env.APP_STORE_URL)
                    .replace("{playStoreUrl}", Env.PLAY_STORE_URL)
            )
        },
        onClickVersion = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            devModeTapCount += 1

            if (devModeTapCount >= DEV_MODE_TAP_THRESHOLD) {
                val newValue = !isDevModeEnabled
                settings.setIsDevModeEnabled(newValue)
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                app.toast(
                    type = Toast.ToastType.SUCCESS,
                    title = context.getString(
                        if (newValue) {
                            R.string.settings__dev_enabled_title
                        } else {
                            R.string.settings__dev_disabled_title
                        }
                    ),
                    description = context.getString(
                        if (newValue) {
                            R.string.settings__dev_enabled_message
                        } else {
                            R.string.settings__dev_disabled_message
                        }
                    ),
                )
                devModeTapCount = 0
            }
        },
    )
}

@Composable
private fun Content(
    onBack: () -> Unit = {},
    onClickReportIssue: () -> Unit = {},
    onClickHelpCenter: () -> Unit = {},
    onClickAppStatus: () -> Unit = {},
    onClickLegal: () -> Unit = {},
    onClickShare: () -> Unit = {},
    onClickVersion: () -> Unit = {},
) {
    val appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.settings__support_title),
            onBackClick = onBack,
            actions = { DrawerNavIcon() },
        )

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            VerticalSpacer(16.dp)

            BodyM(text = stringResource(R.string.settings__support__text), color = Colors.White64)

            VerticalSpacer(16.dp)

            SettingsButtonRow(
                title = stringResource(R.string.settings__support__report),
                iconRes = R.drawable.ic_warning,
                onClick = onClickReportIssue,
            )
            SettingsButtonRow(
                title = stringResource(R.string.settings__support__help),
                iconRes = R.drawable.ic_warning,
                onClick = onClickHelpCenter,
            )
            SettingsButtonRow(
                title = stringResource(R.string.settings__support__status),
                iconRes = R.drawable.ic_settings_support,
                onClick = onClickAppStatus,
                modifier = Modifier.testTag("AppStatus"),
            )
            SettingsButtonRow(
                title = stringResource(R.string.settings__about__legal),
                iconRes = R.drawable.ic_warning,
                onClick = onClickLegal,
            )
            SettingsButtonRow(
                title = stringResource(R.string.settings__about__share),
                iconRes = R.drawable.ic_share,
                onClick = onClickShare,
            )
            SettingsButtonRow(
                title = stringResource(R.string.settings__about__version),
                iconRes = R.drawable.ic_stack,
                value = SettingsButtonValue.StringValue(appVersion),
                onClick = onClickVersion,
                modifier = Modifier.testTag("DevOptions"),
            )

            Spacer(modifier = Modifier.weight(1f))

            Image(
                painter = painterResource(R.drawable.bitkit_logo),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 16.dp)
                    .height(82.dp)
                    .testTag("AboutLogo"),
            )

            Links(modifier = Modifier.fillMaxWidth())

            VerticalSpacer(16.dp)

            BodyS(
                text = stringResource(R.string.settings__support__copyright),
                color = Colors.White64,
            )

            VerticalSpacer(32.dp)
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content()
    }
}

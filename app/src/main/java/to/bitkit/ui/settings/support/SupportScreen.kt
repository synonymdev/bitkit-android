package to.bitkit.ui.settings.support

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Devices.PIXEL_TABLET
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
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.settings.Links
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsIcon
import to.bitkit.ui.navigateTo
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.settingsViewModel
import to.bitkit.ui.shared.modifiers.clickableAlpha
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
                    testTag = if (newValue) "DevModeEnabledToast" else "DevModeDisabledToast",
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        AppTopBar(
            titleText = stringResource(R.string.settings__support_title),
            onBackClick = onBack,
            actions = { DrawerNavIcon() },
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            // Padded content — setting rows
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                VerticalSpacer(16.dp)

                BodyM(text = stringResource(R.string.settings__support__text), color = Colors.White64)

                VerticalSpacer(16.dp)

                SettingsButtonRow(
                    title = stringResource(R.string.settings__support__report),
                    icon = { SettingsIcon(R.drawable.ic_warning) },
                    onClick = onClickReportIssue,
                )
                SettingsButtonRow(
                    title = stringResource(R.string.settings__support__help),
                    icon = { SettingsIcon(R.drawable.ic_question) },
                    onClick = onClickHelpCenter,
                )
                SettingsButtonRow(
                    title = stringResource(R.string.settings__support__status),
                    icon = { SettingsIcon(R.drawable.ic_power) },
                    onClick = onClickAppStatus,
                    modifier = Modifier.testTag("AppStatus")
                )
                SettingsButtonRow(
                    title = stringResource(R.string.settings__about__legal),
                    icon = { SettingsIcon(R.drawable.ic_file_text) },
                    onClick = onClickLegal
                )
                SettingsButtonRow(
                    title = stringResource(R.string.settings__about__share),
                    icon = { SettingsIcon(R.drawable.ic_share) },
                    onClick = onClickShare,
                )

                // Version row — no chevron, value on right
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clickableAlpha { onClickVersion() }
                        .testTag("DevOptions")
                ) {
                    SettingsIcon(R.drawable.ic_stack)
                    HorizontalSpacer(8.dp)
                    BodyM(
                        text = stringResource(R.string.settings__about__version),
                        modifier = Modifier.weight(1f)
                    )
                    BodyM(text = appVersion, color = Colors.White64)
                }
                HorizontalDivider()
            }

            VerticalSpacer(32.dp)

            FillHeight()

            SupportFooter()
        }
    }
}

@Composable
private fun SupportFooter() {
    // Bitkit logo with diagonal orange crossing through it
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clipToBounds()
            .drawBehind {
                val leftCutY = size.height
                val rightCutY = 0f
                val path = Path().apply {
                    moveTo(0f, leftCutY)
                    lineTo(size.width, rightCutY)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(path, color = Colors.Brand)
            },
    ) {
        Image(
            painter = painterResource(R.drawable.bitkit_logo),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .padding(horizontal = 16.dp)
                .testTag("AboutLogo")
        )
    }

    // Solid orange background for bottom content (extends behind nav bar)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Colors.Brand)
            .padding(horizontal = 16.dp)
    ) {
        VerticalSpacer(16.dp)

        Links(modifier = Modifier.fillMaxWidth())

        VerticalSpacer(16.dp)

        BodyM(
            text = stringResource(R.string.settings__support__copyright),
            color = Colors.White64,
        )

        VerticalSpacer(16.dp)

        BrandEndorsement()

        VerticalSpacer(32.dp)
    }

    // Brand-colored spacer that fills the nav bar inset area
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .background(Colors.Brand)
            .windowInsetsBottomHeight(WindowInsets.navigationBars)
    )
}

@Composable
private fun BrandEndorsement() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Image(
            painter = painterResource(R.drawable.ic_synonym_logo),
            contentDescription = null,
            modifier = Modifier.height(24.dp)
        )
        Image(
            painter = painterResource(R.drawable.ic_tether_company),
            contentDescription = null,
            modifier = Modifier.height(16.dp)
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content()
    }
}

@Preview(showSystemUi = true, device = PIXEL_TABLET)
@Composable
private fun PreviewTablet() {
    AppThemeSurface {
        Content()
    }
}

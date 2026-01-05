package to.bitkit.ui.scaffold

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ui.LocalDrawerState
import to.bitkit.ui.components.Title
import to.bitkit.ui.theme.AppThemeSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    titleText: String?,
    onBackClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    actions: @Composable (RowScope.() -> Unit) = {},
) {
    CenterAlignedTopAppBar(
        navigationIcon = {
            onBackClick?.let { BackNavIcon(it) }
        },
        title = {
            titleText?.let { text ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    icon?.let {
                        Icon(
                            painter = painterResource(icon),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .size(32.dp)
                        )
                    }
                    Title(text = text, maxLines = 1)
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
        ),
        modifier = modifier,
    )
}

@Composable
fun BackNavIcon(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.testTag("NavigationBack")
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Default.ArrowBack,
            contentDescription = stringResource(R.string.common__back),
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
fun DrawerNavIcon(
    modifier: Modifier = Modifier,
) {
    val isPreview = LocalInspectionMode.current
    val drawerState = LocalDrawerState.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    if (drawerState != null || isPreview) {
        IconButton(
            onClick = { scope.launch { drawerState?.open() } },
            modifier = modifier.testTag("HeaderMenu")
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_list),
                contentDescription = stringResource(R.string.settings__settings),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
fun ScanNavIcon(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.testTag("NavigationAction")
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_scan),
            contentDescription = stringResource(R.string.other__qr_scan),
            modifier = Modifier.size(24.dp)
        )
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        AppTopBar(
            titleText = "Title And Back",
            onBackClick = {},
        )
    }
}

@Preview
@Composable
private fun Preview2() {
    AppThemeSurface {
        AppTopBar(
            titleText = "Title And Icon",
            onBackClick = {},
            icon = R.drawable.ic_ln_circle,
        )
    }
}

@Preview
@Composable
private fun Preview3() {
    AppThemeSurface {
        AppTopBar(
            titleText = "Title and Action",
            onBackClick = {},
            actions = {
                DrawerNavIcon()
            }
        )
    }
}

@Preview
@Composable
private fun Preview4() {
    AppThemeSurface {
        AppTopBar(
            titleText = "Title Only",
            onBackClick = null,
        )
    }
}

@Preview
@Composable
private fun PreviewNoTitle() {
    AppThemeSurface {
        AppTopBar(
            titleText = null,
            onBackClick = {},
        )
    }
}

package to.bitkit.ui.scaffold

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.Title
import to.bitkit.ui.theme.AppThemeSurface

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AppTopBar(
    titleText: String?,
    onBackClick: (() -> Unit)?,
    icon: Painter? = null,
    actions: @Composable (RowScope.() -> Unit) = {},
) {
    CenterAlignedTopAppBar(
        navigationIcon = {
            if (onBackClick != null) {
                BackNavIcon(onBackClick)
            }
        },
        title = {
            if (titleText != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    icon?.let { painter ->
                        Icon(
                            painter = painter,
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .size(32.dp)
                        )
                    }
                    Title(text = titleText)
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
        ),
    )
}

// TODO use everywhere
@Composable
fun BackNavIcon(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = Icons.AutoMirrored.Default.ArrowBack,
            contentDescription = stringResource(R.string.common__back),
        )
    }
}

@Composable
fun CloseNavIcon(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = stringResource(R.string.common__close),
        )
    }
}

@Composable
fun ScanNavIcon(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
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
            icon = painterResource(R.drawable.ic_ln_circle),
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
                CloseNavIcon(onClick = {})
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

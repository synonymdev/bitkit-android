package to.bitkit.ui.scaffold

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.ui.components.Subtitle
import to.bitkit.ui.theme.AppThemeSurface

/** For pixel perfection from FIGMA, use the back arrow + 14 padding as starting point for added space under this. */
@Composable
fun SheetTopBar(
    titleText: String?,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
    ) {
        titleText?.let {
            Subtitle(
                text = titleText,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = LocalMinimumInteractiveComponentSize.current)
                    .align(Alignment.Center)
            )
        }
        onBack?.let { callback ->
            BackNavIcon(
                onClick = callback,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Horizontal))
            )
        }
        action?.let {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Horizontal))
            ) {
                it()
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        SheetTopBar(
            titleText = "Sheet Top Bar",
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewWithBack() {
    AppThemeSurface {
        SheetTopBar(
            titleText = "Sheet Top Bar With Back",
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewNoText() {
    AppThemeSurface {
        SheetTopBar(
            titleText = null,
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewOverflow() {
    AppThemeSurface {
        SheetTopBar(
            titleText = "Overflowing Title Text In This Preview",
            onBack = {},
        )
    }
}

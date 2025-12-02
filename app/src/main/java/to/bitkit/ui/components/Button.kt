package to.bitkit.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

enum class ButtonSize {
    Small, Large;

    val height: Dp
        get() = when (this) {
            Small -> 40.dp
            Large -> 56.dp
        }
    val horizontalPadding: Dp
        get() = when (this) {
            Small -> 16.dp
            Large -> 24.dp
        }
}

@Composable
fun PrimaryButton(
    text: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    isLoading: Boolean = false,
    size: ButtonSize = ButtonSize.Large,
    enabled: Boolean = true,
    fullWidth: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val contentPadding = PaddingValues(horizontal = size.horizontalPadding.takeIf { text != null } ?: 0.dp)
    val shape = MaterialTheme.shapes.extraLarge

    Surface(
        shape = shape,
        color = Color.Transparent,
        modifier = Modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .requiredHeight(size.height)
            .then(modifier)
            .shadow(
                elevation = 4.dp,
                shape = shape,
                ambientColor = Colors.White.copy(alpha = if (isPressed) 0.4f else 0.25f),
                spotColor = Colors.White.copy(alpha = if (isPressed) 0.4f else 0.25f)
            )
            .then(
                if (isPressed) {
                    Modifier.gradientBackground(startColor = Colors.Gray4, endColor = Colors.Gray5)
                } else {
                    Modifier.gradientBackground(startColor = Colors.Gray5, endColor = Colors.Black)
                }
            )
            .then(if (enabled) Modifier else Modifier.alpha(0.32f))
            .clickable(
                onClick = onClick,
                enabled = enabled && !isLoading,
                interactionSource = interactionSource,
                indication = null
            )
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
                .padding(contentPadding)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = Colors.White32,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(size.height / 2)
                )
            } else {
                CompositionLocalProvider(LocalContentColor provides Colors.White) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (icon != null) {
                            Box(modifier = if (enabled) Modifier else Modifier.alpha(0.5f)) {
                                icon()
                            }
                        }
                        text?.let {
                            Text(
                                text = text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SecondaryButton(
    text: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    isLoading: Boolean = false,
    size: ButtonSize = ButtonSize.Large,
    enabled: Boolean = true,
    fullWidth: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val backgroundColor by animateColorAsState(
        targetValue = if (isPressed) Colors.White10 else Colors.White01,
        label = "secondaryButtonBackground"
    )
    val borderColor by animateColorAsState(
        targetValue = if (enabled) Colors.Gray4 else Color.Transparent,
        label = "secondaryButtonBorder"
    )

    val contentPadding = PaddingValues(horizontal = size.horizontalPadding.takeIf { text != null } ?: 0.dp)
    val shape = MaterialTheme.shapes.extraLarge

    Surface(
        shape = shape,
        color = backgroundColor,
        border = BorderStroke(2.dp, borderColor),
        modifier = Modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .requiredHeight(size.height)
            .then(modifier)
            .clickable(
                onClick = onClick,
                enabled = enabled && !isLoading,
                interactionSource = interactionSource,
                indication = null
            )
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
                .padding(contentPadding)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = Colors.White32,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(size.height / 2)
                )
            } else {
                CompositionLocalProvider(
                    LocalContentColor provides if (enabled) Colors.White80 else Colors.White32
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (icon != null) {
                            Box(modifier = if (enabled) Modifier else Modifier.alpha(0.5f)) {
                                icon()
                            }
                        }
                        text?.let {
                            Text(
                                text = text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TertiaryButton(
    text: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    isLoading: Boolean = false,
    size: ButtonSize = ButtonSize.Large,
    enabled: Boolean = true,
    fullWidth: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val textColor by animateColorAsState(
        targetValue = when {
            !enabled -> Colors.White32
            isPressed -> Colors.White
            else -> Colors.White80
        },
        label = "tertiaryButtonText"
    )

    val contentPadding = PaddingValues(horizontal = size.horizontalPadding.takeIf { text != null } ?: 0.dp)
    val shape = MaterialTheme.shapes.extraLarge

    Surface(
        shape = shape,
        color = Color.Transparent,
        modifier = Modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .requiredHeight(size.height)
            .then(modifier)
            .clickable(
                onClick = onClick,
                enabled = enabled && !isLoading,
                interactionSource = interactionSource,
                indication = null
            )
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
                .padding(contentPadding)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = Colors.White32,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(size.height / 2)
                )
            } else {
                CompositionLocalProvider(LocalContentColor provides textColor) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (icon != null) {
                            Box(modifier = if (enabled) Modifier else Modifier.alpha(0.5f)) {
                                icon()
                            }
                        }
                        text?.let {
                            Text(
                                text = text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PrimaryButtonPreview() {
    AppThemeSurface {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            PrimaryButton(
                text = "Primary",
                onClick = {},
            )
            PrimaryButton(
                text = "Primary With Icon",
                onClick = {},
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Favorite,
                        contentDescription = "",
                        modifier = Modifier.size(16.dp)
                    )
                },
            )
            PrimaryButton(
                text = "Primary Loading",
                onClick = {},
                isLoading = true,
            )
            PrimaryButton(
                text = "Primary Disabled",
                onClick = {},
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Favorite,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                enabled = false,
            )
            PrimaryButton(
                text = "Primary Small",
                size = ButtonSize.Small,
                onClick = {},
            )
            PrimaryButton(
                text = "Primary Small Not Full",
                size = ButtonSize.Small,
                onClick = {},
                fullWidth = false,
            )
            PrimaryButton(
                text = "Primary Small Loading",
                size = ButtonSize.Small,
                isLoading = true,
                onClick = {},
            )
            PrimaryButton(
                text = "Primary Small Disabled",
                size = ButtonSize.Small,
                onClick = {},
                enabled = false,
            )
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                PrimaryButton(
                    text = null,
                    onClick = {},
                    fullWidth = false,
                    size = ButtonSize.Large,
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = "",
                            modifier = Modifier.size(16.dp)
                        )
                    },
                )
                PrimaryButton(
                    text = null,
                    onClick = {},
                    fullWidth = false,
                    size = ButtonSize.Small,
                    enabled = false,
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Home,
                            contentDescription = "",
                            modifier = Modifier.size(16.dp)
                        )
                    },
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SecondaryButtonPreview() {
    AppThemeSurface {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            SecondaryButton(
                text = "Secondary",
                onClick = {},
            )
            SecondaryButton(
                text = "Secondary With Icon",
                onClick = {},
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Favorite,
                        contentDescription = "",
                        modifier = Modifier.size(16.dp)
                    )
                },
            )
            SecondaryButton(
                text = "Secondary Loading",
                isLoading = true,
                onClick = {},
            )
            SecondaryButton(
                text = "Secondary Disabled",
                onClick = {},
                enabled = false,
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Favorite,
                        contentDescription = "",
                        modifier = Modifier.size(16.dp)
                    )
                },
            )
            SecondaryButton(
                text = "Secondary Small",
                size = ButtonSize.Small,
                onClick = {},
            )
            SecondaryButton(
                text = "Secondary Small Loading",
                size = ButtonSize.Small,
                isLoading = true,
                onClick = {},
            )
            SecondaryButton(
                text = "Secondary Small Disabled",
                size = ButtonSize.Small,
                onClick = {},
                enabled = false,
            )
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                SecondaryButton(
                    text = null,
                    onClick = {},
                    fullWidth = false,
                    size = ButtonSize.Large,
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = "",
                            modifier = Modifier.size(16.dp)
                        )
                    },
                )
                SecondaryButton(
                    text = null,
                    onClick = {},
                    fullWidth = false,
                    size = ButtonSize.Small,
                    enabled = false,
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Home,
                            contentDescription = "",
                            modifier = Modifier.size(16.dp)
                        )
                    },
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TertiaryButtonPreview() {
    AppThemeSurface {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            TertiaryButton(
                text = "Tertiary",
                onClick = {}
            )
            TertiaryButton(
                text = "Tertiary With Icon",
                onClick = {},
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Favorite,
                        contentDescription = "",
                        modifier = Modifier.size(16.dp)
                    )
                },
            )
            TertiaryButton(
                text = "Tertiary Loading",
                isLoading = true,
                onClick = {}
            )
            TertiaryButton(
                text = "Tertiary Disabled",
                enabled = false,
                onClick = {}
            )
            TertiaryButton(
                text = "Tertiary Small",
                size = ButtonSize.Small,
                onClick = {}
            )
            TertiaryButton(
                text = "Tertiary Small Loading",
                size = ButtonSize.Small,
                isLoading = true,
                onClick = {}
            )
            TertiaryButton(
                text = "Tertiary Small Disabled",
                size = ButtonSize.Small,
                enabled = false,
                onClick = {}
            )
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                TertiaryButton(
                    text = null,
                    onClick = {},
                    fullWidth = false,
                    size = ButtonSize.Large,
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = "",
                            modifier = Modifier.size(16.dp)
                        )
                    },
                )
                TertiaryButton(
                    text = null,
                    onClick = {},
                    fullWidth = false,
                    size = ButtonSize.Small,
                    enabled = false,
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Home,
                            contentDescription = "",
                            modifier = Modifier.size(16.dp)
                        )
                    },
                )
            }
        }
    }
}

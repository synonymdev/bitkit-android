package to.bitkit.ui.screens.profile

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.GradientCircularProgressIndicator
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppTextFieldDefaults
import to.bitkit.ui.theme.AppTextStyles
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun CreateProfileScreen(
    viewModel: CreateProfileViewModel,
    onNavigateToPayContacts: () -> Unit,
    onBackClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.effects.collect {
            when (it) {
                CreateProfileEffect.CreateSuccess -> onNavigateToPayContacts()
            }
        }
    }

    Content(
        uiState = uiState,
        onBackClick = onBackClick,
        onNameChange = viewModel::onNameChange,
        onAvatarSelected = viewModel::onAvatarSelected,
        onSave = viewModel::save,
    )
}

@Composable
private fun Content(
    uiState: CreateProfileUiState,
    onBackClick: () -> Unit,
    onNameChange: (String) -> Unit,
    onAvatarSelected: (Uri) -> Unit,
    onSave: () -> Unit,
) {
    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { onAvatarSelected(it) } }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri -> uri?.let { onAvatarSelected(it) } },
    )

    val launchPhotoPicker = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } else {
            galleryLauncher.launch("image/*")
        }
    }

    ScreenColumn {
        val navTitleRes = if (uiState.isRestoring) {
            R.string.profile__restore_nav_title
        } else {
            R.string.profile__create_nav_title
        }
        AppTopBar(
            titleText = stringResource(navTitleRes),
            onBackClick = onBackClick,
        )

        if (uiState.isLoading) {
            LoadingState(text = stringResource(R.string.profile__deriving_keys))
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp)
            ) {
                VerticalSpacer(32.dp)

                AvatarPickerButton(
                    avatarUri = uiState.avatarUri,
                    onClick = launchPhotoPicker,
                )

                VerticalSpacer(24.dp)

                TextInput(
                    value = uiState.name,
                    onValueChange = onNameChange,
                    placeholder = stringResource(R.string.profile__edit_name_placeholder),
                    singleLine = true,
                    textStyle = AppTextStyles.Display.copy(textAlign = TextAlign.Center),
                    colors = AppTextFieldDefaults.transparent,
                    modifier = Modifier.fillMaxWidth(),
                )

                VerticalSpacer(16.dp)
                HorizontalDivider()
                VerticalSpacer(16.dp)

                Text13Up(
                    text = stringResource(R.string.profile__your_pubky),
                    color = Colors.White64,
                )
                VerticalSpacer(8.dp)
                BodyS(
                    text = uiState.derivedPublicKey ?: "...",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                FillHeight()
                VerticalSpacer(16.dp)

                PrimaryButton(
                    text = stringResource(R.string.common__continue),
                    onClick = onSave,
                    enabled = uiState.name.isNotBlank() && !uiState.isSaving,
                    isLoading = uiState.isSaving,
                )
                VerticalSpacer(16.dp)
            }
        }
    }
}

@Composable
private fun AvatarPickerButton(
    avatarUri: Uri?,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(100.dp)
            .clip(CircleShape)
            .background(Colors.Gray5)
            .clickable(onClick = onClick),
    ) {
        if (avatarUri != null) {
            AsyncImage(
                model = avatarUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_user_square),
                contentDescription = null,
                tint = Colors.White32,
                modifier = Modifier.size(40.dp),
            )
        }
    }
}

@Composable
private fun LoadingState(text: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            GradientCircularProgressIndicator(modifier = Modifier.size(24.dp))
            VerticalSpacer(12.dp)
            BodyM(text = text, color = Colors.White64)
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            uiState = CreateProfileUiState(
                derivedPublicKey = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg",
                name = "",
            ),
            onBackClick = {},
            onNameChange = {},
            onAvatarSelected = {},
            onSave = {},
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun RestoringPreview() {
    AppThemeSurface {
        Content(
            uiState = CreateProfileUiState(
                derivedPublicKey = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg",
                name = "Satoshi",
                isRestoring = true,
            ),
            onBackClick = {},
            onNameChange = {},
            onAvatarSelected = {},
            onSave = {},
        )
    }
}

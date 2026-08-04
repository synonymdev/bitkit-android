package to.bitkit.ui.components

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface

@HiltAndroidTest
@ComposeUi
class TagButtonTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun removableTagExposesItsAction() {
        composeTestRule.setContent {
            AppThemeSurface {
                TagButton(
                    text = "Founder",
                    onClick = {},
                    accessibilityLabel = "Remove Founder tag",
                    displayIconClose = true,
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Remove Founder tag")
            .assertHasClickAction()
        composeTestRule.onNodeWithTag("Tag-Founder")
            .assertHasClickAction()
    }
}

package to.bitkit.ui.shared.modifiers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.components.SheetSize
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Insets
import to.bitkit.ui.theme.TopBarHeight

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@ComposeUi
class SheetHeightTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun largeNonModalHeightIgnoresBottomInset() {
        var expectedHeight = 0
        var actualHeight = 0

        composeTestRule.setContent {
            AppThemeSurface {
                val density = LocalDensity.current
                val windowHeight = LocalWindowInfo.current.containerSize.height
                val topInset = Insets.Top
                val expected = with(density) {
                    (windowHeight.toDp() - topInset - TopBarHeight + 6.dp).roundToPx()
                }

                SideEffect {
                    expectedHeight = expected
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .sheetHeight(size = SheetSize.LARGE)
                            .fillMaxWidth()
                            .onSizeChanged { actualHeight = it.height }
                    )
                }
            }
        }

        composeTestRule.waitUntil {
            expectedHeight > 0 && actualHeight > 0
        }

        assertEquals(expectedHeight, actualHeight)
    }
}

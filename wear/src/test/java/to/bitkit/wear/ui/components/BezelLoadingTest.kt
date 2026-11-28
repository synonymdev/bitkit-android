package to.bitkit.wear.ui.components

import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.wear.compose.material3.Text
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

// Native graphics, so that the Region math that leaves covered nodes out of the accessibility tree runs.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w227dp-h227dp-round")
@RunWith(RobolectricTestRunner::class)
class BezelLoadingTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the ring is one progress node and leaves the content beneath it to accessibility services`() {
        val accessibility = composeRule.activity.getSystemService(AccessibilityManager::class.java)
        shadowOf(accessibility).apply {
            setEnabled(true)
            setTouchExplorationEnabled(true)
        }
        composeRule.setContent {
            WearPreview {
                Box(modifier = Modifier.fillMaxSize()) {
                    Text(text = "₿ 21 000", modifier = Modifier.align(Alignment.Center).testTag("Content"))
                    Text(text = "Hint", modifier = Modifier.align(Alignment.BottomCenter).testTag("Hint"))
                    BezelLoading()
                }
            }
        }

        composeRule.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(1)
        val provider = assertNotNull(composeView().accessibilityNodeProvider)
        val ring = assertNotNull(provider.createAccessibilityNodeInfo(idOf("BezelLoading")))
        assertEquals("android.widget.ProgressBar", ring.className)
        // A node that a node above it covers gets no accessibility node, so TalkBack and `android layout` miss it.
        assertNotNull(provider.createAccessibilityNodeInfo(idOf("Content")))
        assertNotNull(provider.createAccessibilityNodeInfo(idOf("Hint")))
    }

    private fun idOf(tag: String): Int = composeRule.onNodeWithTag(tag).fetchSemanticsNode().id

    private fun composeView(): View {
        fun find(view: View): View? = when {
            view.javaClass.simpleName == "AndroidComposeView" -> view
            view is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
            else -> null
        }
        return requireNotNull(find(composeRule.activity.window.decorView)) { "No compose view" }
    }
}

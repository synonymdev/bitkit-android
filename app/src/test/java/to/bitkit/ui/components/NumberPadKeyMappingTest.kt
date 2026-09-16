package to.bitkit.ui.components

import androidx.compose.ui.input.key.Key
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression pins for #719 — the custom number pad accepts hardware/host keystrokes.
 *
 * The mapping is what [NumberPad]'s `onPreviewKeyEvent` feeds into `onPress`, so a key that stops
 * mapping here goes dead on a device with a physical keyboard attached.
 */
class NumberPadKeyMappingTest {

    @Test
    fun `top row digits map to their characters`() {
        val digitKeys = listOf(
            Key.Zero to "0",
            Key.One to "1",
            Key.Two to "2",
            Key.Three to "3",
            Key.Four to "4",
            Key.Five to "5",
            Key.Six to "6",
            Key.Seven to "7",
            Key.Eight to "8",
            Key.Nine to "9",
        )

        digitKeys.forEach { (key, expected) ->
            assertEquals(expected, mapHardwareKey(key, NumberPadType.SIMPLE))
        }
    }

    @Test
    fun `numpad digits map to their characters`() {
        val numPadKeys = listOf(
            Key.NumPad0 to "0",
            Key.NumPad1 to "1",
            Key.NumPad2 to "2",
            Key.NumPad3 to "3",
            Key.NumPad4 to "4",
            Key.NumPad5 to "5",
            Key.NumPad6 to "6",
            Key.NumPad7 to "7",
            Key.NumPad8 to "8",
            Key.NumPad9 to "9",
        )

        numPadKeys.forEach { (key, expected) ->
            assertEquals(expected, mapHardwareKey(key, NumberPadType.SIMPLE))
        }
    }

    @Test
    fun `backspace and delete map to the delete key`() {
        assertEquals(KEY_DELETE, mapHardwareKey(Key.Backspace, NumberPadType.SIMPLE))
        assertEquals(KEY_DELETE, mapHardwareKey(Key.Delete, NumberPadType.SIMPLE))
    }

    @Test
    fun `decimal separators are accepted only on a decimal pad`() {
        listOf(Key.Period, Key.NumPadDot, Key.Comma).forEach { key ->
            assertEquals(KEY_DECIMAL, mapHardwareKey(key, NumberPadType.DECIMAL))
            assertNull(mapHardwareKey(key, NumberPadType.SIMPLE))
            assertNull(mapHardwareKey(key, NumberPadType.INTEGER))
        }
    }

    @Test
    fun `unmapped keys are ignored so the event falls through`() {
        listOf(Key.A, Key.Spacebar, Key.Enter, Key.DirectionUp).forEach { key ->
            assertNull(mapHardwareKey(key, NumberPadType.DECIMAL))
        }
    }
}

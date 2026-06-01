package to.bitkit.models.widget

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlocksWidgetFieldTest {

    @Test
    fun `default preferences enable exactly the first four fields`() {
        val enabled = BlocksPreferences().enabledFields()

        assertEquals(
            listOf(
                BlocksWidgetField.BLOCK,
                BlocksWidgetField.TIME,
                BlocksWidgetField.DATE,
                BlocksWidgetField.TRANSACTIONS,
            ),
            enabled,
        )
    }

    @Test
    fun `toggleField turns off an enabled field even at the limit`() {
        val result = BlocksPreferences().toggleField(BlocksWidgetField.BLOCK)

        assertFalse(result.showBlock)
        assertEquals(3, result.enabledFields().size)
    }

    @Test
    fun `toggleField is a no-op when enabling a fifth field`() {
        // Defaults already have 4 fields enabled (block, time, date, transactions)
        val result = BlocksPreferences().toggleField(BlocksWidgetField.SIZE)

        assertFalse(result.showSize)
        assertEquals(MAX_BLOCKS_FIELDS, result.enabledFields().size)
    }

    @Test
    fun `toggleField enables a disabled field when below the limit`() {
        val prefs = BlocksPreferences(
            showBlock = true,
            showTime = true,
            showDate = false,
            showTransactions = false,
            showSize = false,
            showFees = false,
        )

        val result = prefs.toggleField(BlocksWidgetField.FEES)

        assertTrue(result.showFees)
        assertEquals(3, result.enabledFields().size)
    }

    @Test
    fun `limitedToMax keeps only the first four enabled fields in declared order`() {
        val prefs = BlocksPreferences(
            showBlock = true,
            showTime = true,
            showDate = true,
            showTransactions = true,
            showSize = true,
            showFees = true,
        )

        val result = prefs.limitedToMax()

        assertEquals(
            listOf(
                BlocksWidgetField.BLOCK,
                BlocksWidgetField.TIME,
                BlocksWidgetField.DATE,
                BlocksWidgetField.TRANSACTIONS,
            ),
            result.enabledFields(),
        )
    }

    @Test
    fun `limitedToMax leaves compliant preferences unchanged`() {
        val prefs = BlocksPreferences(
            showBlock = true,
            showTime = false,
            showDate = true,
            showTransactions = false,
            showSize = true,
            showFees = false,
        )

        assertEquals(prefs, prefs.limitedToMax())
    }
}

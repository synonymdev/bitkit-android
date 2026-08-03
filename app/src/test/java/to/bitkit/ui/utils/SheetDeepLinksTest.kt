package to.bitkit.ui.utils

import android.net.Uri
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.components.Sheet
import to.bitkit.ui.screens.wallets.receive.ReceiveRoute
import to.bitkit.ui.sheets.BackupRoute
import to.bitkit.ui.sheets.SendRoute
import to.bitkit.ui.sheets.WidgetsRoute
import to.bitkit.ui.sheets.hardware.HardwareRoute
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class SheetDeepLinksTest : BaseUnitTest() {
    private companion object {
        const val KEBAB_SEGMENT = "[a-z0-9]+(-[a-z0-9]+)*"
        val KEBAB_PATH = Regex("$KEBAB_SEGMENT(/$KEBAB_SEGMENT)?")

        val SENSITIVE_PATHS: List<String> = listOf(
            "backup/show-mnemonic",
            "backup/show-passphrase",
            "backup/confirm-mnemonic",
            "backup/confirm-passphrase",
            "pin",
            "change-pin",
            "disable-pin",
            "force-transfer",
            "backup/success",
            "backup/warning",
        )

        val UNSUPPORTED_START_PATHS: List<String> = listOf(
            "send/fee-nav",
            "send/fee-rate",
            "send/fee-custom",
            "send/quick-pay",
            "send/confirm",
            "receive/confirm",
            "receive/confirm-increase-inbound",
            "receive/liquidity",
            "receive/liquidity-additional",
            "hardware/searching",
            "hardware/paired",
        )
    }

    @Test
    fun `a bare sheet id opens the sheet at its first registered route`() {
        val send = SheetDeepLinks.sheetFor(Uri.parse("bitkit://screen/send"))
        val receive = SheetDeepLinks.sheetFor(Uri.parse("bitkit://screen/receive"))
        val widgets = SheetDeepLinks.sheetFor(Uri.parse("bitkit://screen/widgets"))

        assertEquals(Sheet.Send(SendRoute.Recipient), send)
        assertEquals(Sheet.Receive(ReceiveRoute.QR), receive)
        assertEquals(Sheet.Widgets(WidgetsRoute.Gallery), widgets)
    }

    @Test
    fun `backup opens at the intro rather than the sheet's own default route`() {
        val backup = SheetDeepLinks.sheetFor(Uri.parse("bitkit://screen/backup"))

        assertEquals(Sheet.Backup(BackupRoute.Intro), backup)
    }

    @Test
    fun `a sub-route segment selects the nested start destination`() {
        val amount = SheetDeepLinks.sheetFor(Uri.parse("bitkit://screen/send/amount"))
        val priceEdit = SheetDeepLinks.sheetFor(Uri.parse("bitkit://screen/widgets/price-edit"))
        val editInvoice = SheetDeepLinks.sheetFor(Uri.parse("bitkit://screen/receive/edit-invoice"))

        assertEquals(Sheet.Send(SendRoute.Amount), amount)
        assertEquals(Sheet.Widgets(WidgetsRoute.PriceEdit), priceEdit)
        assertEquals(Sheet.Receive(ReceiveRoute.EditInvoice), editInvoice)
    }

    @Test
    fun `sheets without a nested graph are reachable by id alone`() {
        val tagSelector = SheetDeepLinks.sheetFor(Uri.parse("bitkit://screen/activity-tag-selector"))
        val qrScanner = SheetDeepLinks.sheetFor(Uri.parse("bitkit://screen/qr-scanner"))

        assertEquals(Sheet.ActivityTagSelector, tagSelector)
        assertEquals(Sheet.QrScanner(), qrScanner)
    }

    @Test
    fun `sensitive sheets are unreachable`() {
        SENSITIVE_PATHS.forEach { path ->
            val sheet = SheetDeepLinks.sheetFor(Uri.parse("bitkit://screen/$path"))

            assertNull(sheet, "expected '$path' to be unreachable")
        }
    }

    @Test
    fun `routes that cannot be a nested start destination are unreachable`() {
        UNSUPPORTED_START_PATHS.forEach { path ->
            val sheet = SheetDeepLinks.sheetFor(Uri.parse("bitkit://screen/$path"))

            assertNull(sheet, "expected '$path' to be unreachable")
        }
    }

    @Test
    fun `an unknown sub-route does not fall back to the sheet default`() {
        val sheet = SheetDeepLinks.sheetFor(Uri.parse("bitkit://screen/send/not-a-route"))

        assertNull(sheet)
    }

    @Test
    fun `links outside the screen scheme are ignored`() {
        val payment = SheetDeepLinks.sheetFor(Uri.parse("bitcoin://send"))
        val otherHost = SheetDeepLinks.sheetFor(Uri.parse("bitkit://recovery-mode/send"))

        assertNull(payment)
        assertNull(otherHost)
    }

    @Test
    fun `every route marked as a start is registered in its family lookup`() {
        val unreachable = mutableListOf<String>()

        fun check(starts: List<KClass<*>>, lookup: (String) -> Any?) {
            starts.forEach { route ->
                val id = ScreenDeepLinks.kebabId(route) ?: return@forEach
                if (lookup(id) == null) unreachable += id
            }
        }

        check(SendRoute.DeepLinkStart::class.sealedSubclasses) { SendRoute.fromDeepLink(it) }
        check(ReceiveRoute.DeepLinkStart::class.sealedSubclasses) { ReceiveRoute.fromDeepLink(it) }
        check(BackupRoute.DeepLinkStart::class.sealedSubclasses) { BackupRoute.fromDeepLink(it) }
        check(WidgetsRoute.DeepLinkStart::class.sealedSubclasses) { WidgetsRoute.fromDeepLink(it) }
        check(HardwareRoute.DeepLinkStart::class.sealedSubclasses) { HardwareRoute.fromDeepLink(it) }

        assertTrue(unreachable.isEmpty(), "declared starts missing from their family lookup: $unreachable")
    }

    @Test
    fun `lookup ignores case`() {
        val sheet = SheetDeepLinks.sheetFor(Uri.parse("bitkit://screen/Send/Coin-Selection"))

        assertEquals(Sheet.Send(SendRoute.CoinSelection), sheet)
    }
}

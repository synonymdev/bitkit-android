package to.bitkit.ui.screens.wallets.receive

import org.junit.Test
import kotlin.test.assertEquals

class ReceiveInvoiceUtilsTest {

    @Test
    fun `getInvoiceForTab TREZOR returns only the hardware address`() {
        val result = getInvoiceForTab(
            tab = ReceiveTab.TREZOR,
            bip21 = "bitcoin:software?lightning=lnbc1software",
            bolt11 = "lnbc1software",
            cjitInvoice = null,
            isNodeRunning = true,
            onchainAddress = "bc1qsoftware",
            hardwareAddress = "bc1qhardware",
        )

        assertEquals("bitcoin:bc1qhardware", result)
    }

    @Test
    fun `getInvoiceForTab TREZOR applies hardware invoice details`() {
        val result = getInvoiceForTab(
            tab = ReceiveTab.TREZOR,
            bip21 = "bitcoin:software",
            bolt11 = "",
            cjitInvoice = null,
            isNodeRunning = true,
            onchainAddress = "bc1qsoftware",
            hardwareAddress = "bc1qhardware",
            hardwareAmountSats = 12_345uL,
            hardwareMessage = "Cold storage",
        )

        assertEquals("bitcoin:bc1qhardware?amount=0.00012345&message=Cold+storage", result)
    }

    @Test
    fun `getInvoiceForTab TREZOR omits a zero amount`() {
        val result = getInvoiceForTab(
            tab = ReceiveTab.TREZOR,
            bip21 = "bitcoin:bc1qsoftware",
            bolt11 = "",
            cjitInvoice = null,
            isNodeRunning = true,
            onchainAddress = "bc1qsoftware",
            hardwareAddress = "bc1qhardware",
            hardwareAmountSats = 0uL,
        )

        assertEquals("bitcoin:bc1qhardware", result)
    }

    private val testAddress = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
    private val testBolt11 = "lnbc1500n1pn2s39xpp5wyxw0e9fvvf..."
    private val testCjitInvoice = "lnbc2000n1pn2s39xpp5zyxw0e9fvvf..."

    @Test
    fun `getInvoiceForTab SAVINGS returns BIP21 without lightning parameter`() {
        val bip21WithAmount = "bitcoin:$testAddress?amount=0.001&message=Test&lightning=$testBolt11"

        val result = getInvoiceForTab(
            tab = ReceiveTab.SAVINGS,
            bip21 = bip21WithAmount,
            bolt11 = testBolt11,
            cjitInvoice = null,
            isNodeRunning = true,
            onchainAddress = testAddress
        )

        assertEquals("bitcoin:$testAddress?amount=0.001&message=Test", result)
    }

    @Test
    fun `getInvoiceForTab SAVINGS preserves amount when lightning is last parameter`() {
        val bip21 = "bitcoin:$testAddress?amount=0.00050000&lightning=$testBolt11"

        val result = getInvoiceForTab(
            tab = ReceiveTab.SAVINGS,
            bip21 = bip21,
            bolt11 = testBolt11,
            cjitInvoice = null,
            isNodeRunning = true,
            onchainAddress = testAddress
        )

        assertEquals("bitcoin:$testAddress?amount=0.00050000", result)
    }

    @Test
    fun `getInvoiceForTab SAVINGS handles BIP21 without lightning parameter`() {
        val bip21WithoutLightning = "bitcoin:$testAddress?amount=0.002&message=Test"

        val result = getInvoiceForTab(
            tab = ReceiveTab.SAVINGS,
            bip21 = bip21WithoutLightning,
            bolt11 = testBolt11,
            cjitInvoice = null,
            isNodeRunning = true,
            onchainAddress = testAddress
        )

        assertEquals("bitcoin:$testAddress?amount=0.002&message=Test", result)
    }

    @Test
    fun `getInvoiceForTab SAVINGS returns fallback address when BIP21 is empty`() {
        val result = getInvoiceForTab(
            tab = ReceiveTab.SAVINGS,
            bip21 = "",
            bolt11 = testBolt11,
            cjitInvoice = null,
            isNodeRunning = true,
            onchainAddress = testAddress
        )

        assertEquals(testAddress, result)
    }

    @Test
    fun `getInvoiceForTab SAVINGS returns fallback when BIP21 only has lightning`() {
        val bip21OnlyLightning = "bitcoin:$testAddress?lightning=$testBolt11"

        val result = getInvoiceForTab(
            tab = ReceiveTab.SAVINGS,
            bip21 = bip21OnlyLightning,
            bolt11 = testBolt11,
            cjitInvoice = null,
            isNodeRunning = true,
            onchainAddress = testAddress
        )

        assertEquals("bitcoin:$testAddress", result)
    }

    @Test
    fun `getInvoiceForTab AUTO returns full BIP21 when node running and has lightning`() {
        val bip21 = "bitcoin:$testAddress?amount=0.001&lightning=$testBolt11"

        val result = getInvoiceForTab(
            tab = ReceiveTab.AUTO,
            bip21 = bip21,
            bolt11 = testBolt11,
            cjitInvoice = null,
            isNodeRunning = true,
            onchainAddress = testAddress
        )

        assertEquals(bip21, result)
    }

    @Test
    fun `getInvoiceForTab AUTO returns empty when has lightning but node not running`() {
        val bip21 = "bitcoin:$testAddress?amount=0.001&lightning=$testBolt11"

        val result = getInvoiceForTab(
            tab = ReceiveTab.AUTO,
            bip21 = bip21,
            bolt11 = testBolt11,
            cjitInvoice = null,
            isNodeRunning = false,
            onchainAddress = testAddress
        )

        assertEquals("", result)
    }

    @Test
    fun `getInvoiceForTab AUTO returns empty when BIP21 has no lightning even if node running`() {
        val bip21WithoutLightning = "bitcoin:$testAddress?amount=0.001&message=Test"

        val result = getInvoiceForTab(
            tab = ReceiveTab.AUTO,
            bip21 = bip21WithoutLightning,
            bolt11 = testBolt11,
            cjitInvoice = null,
            isNodeRunning = true,
            onchainAddress = testAddress
        )

        assertEquals("", result)
    }

    @Test
    fun `getInvoiceForTab AUTO returns empty when no lightning and node not running`() {
        val bip21WithoutLightning = "bitcoin:$testAddress?amount=0.001&message=Test"

        val result = getInvoiceForTab(
            tab = ReceiveTab.AUTO,
            bip21 = bip21WithoutLightning,
            bolt11 = testBolt11,
            cjitInvoice = null,
            isNodeRunning = false,
            onchainAddress = testAddress
        )

        assertEquals("", result)
    }

    @Test
    fun `getInvoiceForTab AUTO detects lightning when it is the first parameter`() {
        val bip21LightningFirst = "bitcoin:$testAddress?lightning=$testBolt11&amount=0.001"

        val result = getInvoiceForTab(
            tab = ReceiveTab.AUTO,
            bip21 = bip21LightningFirst,
            bolt11 = testBolt11,
            cjitInvoice = null,
            isNodeRunning = true,
            onchainAddress = testAddress
        )

        assertEquals(bip21LightningFirst, result)
    }

    @Test
    fun `getInvoiceForTab SPENDING returns CJIT invoice when available and node running`() {
        val bip21 = "bitcoin:$testAddress?lightning=$testBolt11"

        val result = getInvoiceForTab(
            tab = ReceiveTab.SPENDING,
            bip21 = bip21,
            bolt11 = testBolt11,
            cjitInvoice = testCjitInvoice,
            isNodeRunning = true,
            onchainAddress = testAddress
        )

        assertEquals(testCjitInvoice, result)
    }

    @Test
    fun `getInvoiceForTab SPENDING returns bolt11 when CJIT unavailable`() {
        val bip21 = "bitcoin:$testAddress?lightning=$testBolt11"

        val result = getInvoiceForTab(
            tab = ReceiveTab.SPENDING,
            bip21 = bip21,
            bolt11 = testBolt11,
            cjitInvoice = null,
            isNodeRunning = true,
            onchainAddress = testAddress
        )

        assertEquals(testBolt11, result)
    }

    @Test
    fun `getInvoiceForTab SPENDING returns empty when node not running even with CJIT`() {
        val bip21 = "bitcoin:$testAddress?lightning=$testBolt11"

        val result = getInvoiceForTab(
            tab = ReceiveTab.SPENDING,
            bip21 = bip21,
            bolt11 = testBolt11,
            cjitInvoice = testCjitInvoice,
            isNodeRunning = false,
            onchainAddress = testAddress
        )

        assertEquals("", result)
    }

    @Test
    fun `getInvoiceForTab SPENDING returns empty when node not running and no CJIT`() {
        val bip21 = "bitcoin:$testAddress?lightning=$testBolt11"

        val result = getInvoiceForTab(
            tab = ReceiveTab.SPENDING,
            bip21 = bip21,
            bolt11 = testBolt11,
            cjitInvoice = null,
            isNodeRunning = false,
            onchainAddress = testAddress
        )

        assertEquals("", result)
    }

    @Test
    fun `getSavingsCopyText returns plain address when no extra params`() {
        val bip21 = "bitcoin:$testAddress?lightning=$testBolt11"
        assertEquals(testAddress, getSavingsCopyText(bip21, testAddress))
    }

    @Test
    fun `getSavingsCopyText returns plain address when bip21 has no params`() {
        val bip21 = "bitcoin:$testAddress"
        assertEquals(testAddress, getSavingsCopyText(bip21, testAddress))
    }

    @Test
    fun `getSavingsCopyText returns bip21 without lightning when amount present`() {
        val bip21 = "bitcoin:$testAddress?amount=0.001&lightning=$testBolt11"
        assertEquals("bitcoin:$testAddress?amount=0.001", getSavingsCopyText(bip21, testAddress))
    }

    @Test
    fun `getSavingsCopyText returns bip21 without lightning when message present`() {
        val bip21 = "bitcoin:$testAddress?message=Test&lightning=$testBolt11"
        assertEquals("bitcoin:$testAddress?message=Test", getSavingsCopyText(bip21, testAddress))
    }

    @Test
    fun `getSavingsCopyText returns fallback address when bip21 is empty`() {
        assertEquals(testAddress, getSavingsCopyText("", testAddress))
    }
}

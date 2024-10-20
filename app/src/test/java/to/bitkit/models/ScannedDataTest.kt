package to.bitkit.models

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import to.bitkit.test.TestApp

@Suppress("SpellCheckingInspection")
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class ScannedDataTest {

    @Test(expected = ScannedError.InvalidData::class)
    fun `empty uri throws InvalidData`() {
        ScannedData("")
    }

    @Test
    fun `valid bolt11 lightning invoice`() {
        val uri = "lightning:lntb1m1pw..."
        val scannedData = ScannedData(uri)

        assertEquals(1, scannedData.options.size)
        assertTrue(scannedData.options[0] is ScannedOptions.Bolt11)
        assertEquals("lntb1m1pw...", (scannedData.options[0] as ScannedOptions.Bolt11).invoice)
    }

    @Test
    fun `valid bitcoin address with bip21 parameters`() {
        val uri = "bitcoin:1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa?amount=0.01&label=Satoshi&message=Donation"
        val scannedData = ScannedData(uri)

        assertEquals(1, scannedData.options.size)
        val option = scannedData.options[0] as ScannedOptions.Onchain
        assertEquals("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa", option.address)
        assertEquals(0.01, option.amount!!, 0.0)
        assertEquals("Satoshi", option.label)
        assertEquals("Donation", option.message)
    }

    @Test
    fun `bitcoin uri with lightning invoice`() {
        val uri = "bitcoin:1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa?lightning=lntb1m1pw..."
        val scannedData = ScannedData(uri)

        assertEquals(1, scannedData.options.size)
        assertTrue(scannedData.options[0] is ScannedOptions.Bolt11)
        assertEquals("lntb1m1pw...", (scannedData.options[0] as ScannedOptions.Bolt11).invoice)
    }

    @Test(expected = ScannedError.NoOptions::class)
    fun `no options in uri throws NoOptions`() {
        val uri = "bitcoin:?somethingInvalid=true"
        ScannedData(uri)
    }
}

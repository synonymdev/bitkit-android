package to.bitkit.models

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.BitcoinNetworkEnum
import com.synonym.bitkitcore.BtOpenChannelState
import com.synonym.bitkitcore.BtOrderState
import com.synonym.bitkitcore.BtOrderState2
import com.synonym.bitkitcore.CJitStateEnum
import com.synonym.bitkitcore.PaymentState
import com.synonym.bitkitcore.PaymentType
import org.junit.Test
import to.bitkit.data.SettingsData
import to.bitkit.di.json
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Fixtures in `resources/ios-backup` are bitkit-ios `BackupService` output, encoded by Swift's `JSONEncoder`. */
class BackupJsonTest {

    @Test
    fun `decodes iOS metadata with omitted optionals`() {
        val backup = decodeBackup<MetadataBackupV1>(iosFixture("metadata"))

        assertEquals(2, backup.tagMetadata.size)
        assertNull(backup.tagMetadata[0].txId)
        assertNull(backup.tagMetadata[0].channelId)
        assertEquals(listOf("QA1164"), backup.tagMetadata[0].tags)
        assertEquals(PubkySessionBackupKind.LocalSeed, backup.pubkySession?.kind)
        assertEquals("Contact Wallet", backup.pubkyContactProfileOverrides?.values?.single()?.name)
    }

    @Test
    fun `decodes iOS settings written in iOS forms`() {
        val settings = decodeSettingsBackup(iosFixture("settings")).settings

        assertEquals(listOf("nativeSegwit", "taproot"), settings.addressTypesToMonitor)
        assertEquals(PrimaryDisplay.FIAT, settings.primaryDisplay)
        assertEquals(BitcoinDisplayUnit.CLASSIC, settings.displayUnit)
        assertEquals(TransactionSpeed.Fast, settings.defaultTransactionSpeed)
        assertEquals(CoinSelectionPreference.LargestFirst, settings.coinSelectPreference)
        assertEquals(false, settings.coinSelectAuto)
        assertEquals("tcp://192.168.1.10:60001", settings.electrumServer)
        assertEquals(true, settings.backupVerified)
    }

    @Test
    fun `keeps the default for a setting it cannot read`() {
        val raw = """{"version":1,"createdAt":1,"settings":{"primaryDisplay":"Fiat","quickPayAmount":"lots"}}"""

        val settings = decodeSettingsBackup(raw).settings

        assertEquals(PrimaryDisplay.FIAT, settings.primaryDisplay)
        assertEquals(SettingsData().quickPayAmount, settings.quickPayAmount)
    }

    @Test
    fun `rejects a settings envelope without settings`() {
        assertFails { decodeSettingsBackup("""{"version":1,"createdAt":1}""") }
    }

    @Test
    fun `decodes iOS blocktank enums`() {
        val backup = decodeBackup<BlocktankBackupV1>(iosFixture("blocktank"))

        assertEquals(BitcoinNetworkEnum.REGTEST, backup.info?.onchain?.network)
        assertEquals(BtOrderState.OPEN, backup.orders.single().state)
        assertEquals(BtOrderState2.EXECUTED, backup.orders.single().state2)
        assertEquals(BtOpenChannelState.OPEN, backup.orders.single().channel?.state)
        assertEquals(CJitStateEnum.CREATED, backup.cjitEntries.single().state)
    }

    @Test
    fun `decodes iOS activity enum wrappers`() {
        val backup = decodeBackup<ActivityBackupV1>(iosFixture("activity"))

        val lightning = assertIs<Activity.Lightning>(backup.activities[0]).v1
        assertEquals(PaymentType.SENT, lightning.txType)
        assertEquals(PaymentState.SUCCEEDED, lightning.status)
        assertEquals("pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw374y", lightning.contact)
        val onchain = assertIs<Activity.Onchain>(backup.activities[1]).v1
        assertEquals(PaymentType.RECEIVED, onchain.txType)
        assertNull(onchain.channelId)
        assertEquals(listOf("QA1164"), backup.activityTags.single().tags)
    }

    @Test
    fun `decodes Android envelopes unchanged`() {
        val metadata = decodeBackup<MetadataBackupV1>(iosFixture("metadata"))
        val settings = decodeSettingsBackup(iosFixture("settings"))
        val blocktank = decodeBackup<BlocktankBackupV1>(iosFixture("blocktank"))
        val activity = decodeBackup<ActivityBackupV1>(iosFixture("activity"))

        assertEquals(metadata, decodeBackup(json.encodeToString(metadata)))
        assertEquals(settings, decodeSettingsBackup(json.encodeToString(settings)))
        assertEquals(blocktank, decodeBackup(json.encodeToString(blocktank)))
        assertEquals(activity, decodeBackup(json.encodeToString(activity)))
    }

    private fun iosFixture(name: String) =
        requireNotNull(javaClass.getResourceAsStream("/ios-backup/$name.json")).bufferedReader().readText()
}

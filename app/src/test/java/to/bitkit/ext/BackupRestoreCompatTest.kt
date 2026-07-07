package to.bitkit.ext

import com.synonym.bitkitcore.ActivityTags
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentType
import com.synonym.bitkitcore.PreActivityMetadata
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import to.bitkit.data.AppCacheData
import to.bitkit.di.json
import to.bitkit.models.ActivityBackupV1
import to.bitkit.models.MetadataBackupV1
import to.bitkit.models.WalletScope
import to.bitkit.test.BaseUnitTest
import org.junit.Test
import kotlin.test.assertEquals

class BackupRestoreCompatTest : BaseUnitTest() {

    @Test
    fun `decodeActivityBackupV1Compat fills missing walletId`() {
        val payload = ActivityBackupV1(
            createdAt = 1_000,
            activities = listOf(
                Activity.Onchain(
                    OnchainActivity.create(
                        id = "tx1",
                        txType = PaymentType.RECEIVED,
                        txId = "tx1",
                        value = 100uL,
                        fee = 0uL,
                        address = "",
                        timestamp = 1_700_000_000uL,
                    )
                )
            ),
            activityTags = listOf(
                ActivityTags(
                    walletId = WalletScope.default,
                    activityId = "tx1",
                    tags = listOf("tag"),
                )
            ),
            closedChannels = emptyList(),
        )
        val legacyJson = json.encodeToString(payload)
            .let { json.parseToJsonElement(it).withoutWalletIds() }
            .let { json.encodeToString(it) }

        val parsed = legacyJson.decodeActivityBackupV1Compat()

        assertEquals(WalletScope.default, (parsed.activities.single() as Activity.Onchain).v1.walletId)
        assertEquals(WalletScope.default, parsed.activityTags.single().walletId)
    }

    @Test
    fun `decodeMetadataBackupV1Compat fills missing walletId`() {
        val payload = MetadataBackupV1(
            createdAt = 1_000,
            tagMetadata = listOf(
                PreActivityMetadata(
                    walletId = WalletScope.default,
                    paymentId = "payment1",
                    tags = listOf("tag"),
                    paymentHash = null,
                    txId = null,
                    address = null,
                    isReceive = true,
                    feeRate = 1uL,
                    isTransfer = false,
                    channelId = null,
                    createdAt = 1_700_000_000uL,
                )
            ),
            cache = AppCacheData(),
        )
        val legacyJson = json.encodeToString(payload)
            .let { json.parseToJsonElement(it).withoutWalletIds() }
            .let { json.encodeToString(it) }

        val parsed = legacyJson.decodeMetadataBackupV1Compat()

        assertEquals(WalletScope.default, parsed.tagMetadata.single().walletId)
    }

    private fun JsonElement.withoutWalletIds(): JsonElement = when (this) {
        is JsonObject -> JsonObject(
            filterKeys { it != "walletId" }
                .mapValues { (_, value) -> value.withoutWalletIds() }
        )
        is JsonArray -> JsonArray(map { it.withoutWalletIds() })
        else -> this
    }
}

package to.bitkit.services

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import to.bitkit.data.SettingsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.env.Env
import to.bitkit.models.WalletScope
import to.bitkit.repositories.OnchainSendAttemptStore
import to.bitkit.repositories.OnchainSendOutcome
import to.bitkit.repositories.WalletRepo
import to.bitkit.test.annotations.CoreServiceIntegration
import to.bitkit.test.annotations.DeviceIntegration
import java.io.File
import javax.inject.Inject

/** Disposable-device fixture: real signed receipt, deliberately seeded Pending, never a native fault claim. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@DeviceIntegration
@CoreServiceIntegration
class PreparedOnchainRecoveryFixtureTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var lightningService: LightningService

    @Inject
    lateinit var coreService: CoreService

    @Inject
    lateinit var keychain: Keychain

    @Inject
    lateinit var walletRepo: WalletRepo

    @Inject
    lateinit var store: OnchainSendAttemptStore

    @Inject
    lateinit var settingsStore: SettingsStore

    @Test
    fun verifySharedVssBindingVectors() {
        val mnemonic = List(11) { "abandon" }.plus("about").joinToString(" ")
        val vectors = mapOf(
            "bitcoin" to "28f258542d91310274942356e7777d8978bac6dbd3fadbfbfe5d663b19f1f6c5",
            "testnet" to "0ce1e7952991cf5ce84bfa135dbc194b3d55a7f2fc69807091c4ca1d6eaac809",
            "signet" to "966319adba79fb70afbf6eb1c7bde0cf93433e08d6f2f832c30467b812005fbe",
            "regtest" to "fe843546f607f38ba7b1e8fe479c3103139ebea5b83628cbaa465e41cf6cb6c0",
        )
        vectors.forEach { (network, expected) ->
            val storeId = com.synonym.vssclient.vssDeriveStoreId("bitkit_v1_$network", mnemonic, null)
            val binding = java.security.MessageDigest.getInstance("SHA-256")
                .digest(storeId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            org.junit.Assert.assertEquals(expected, binding)
        }
    }

    @Test
    fun seedPreparedOriginalForRecoveryUi() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Env.initAppStoragePath(context.filesDir.absolutePath)
        hiltRule.inject()
        // Refuse a pre-existing wallet; the runner must use an owned, disposable app install.
        check(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name) == null)
        walletRepo.createWallet(null).getOrThrow()
        keychain.upsertString(Keychain.Key.PIN.name, "1234") // Public disposable-fixture PIN only.
        settingsStore.update {
            it.copy(isPinEnabled = true, isPinForPaymentsEnabled = true, isBiometricEnabled = false)
        }
        lightningService.setup(walletIndex = 0)
        try {
            lightningService.start()
            lightningService.sync()
            val depositAddress = lightningService.newAddress()
            coreService.blocktank.regtestMine(1u)
            coreService.blocktank.regtestDeposit(depositAddress, 100_000uL)
            coreService.blocktank.regtestMine(6u)
            delay(15_000)
            lightningService.sync()
            val recipient = "bcrt1qs04g2ka4pr9s3mv73nu32tvfy7r3cxd27wkyu8"
            val isMax = InstrumentationRegistry.getArguments().getString("recoveryMode") == "max"
            val original = store.admit(
                walletId = WalletScope.default, requestId = null, orderId = null, address = recipient,
                amountSats = 1_000uL, isMaxAmount = isMax, feeRateSatsPerVByte = 1uL,
                isTransfer = false, channelId = null, tags = emptyList(), beforeSendAttempt = {},
            )
            val prepared = lightningService.prepareOnchainSend(recipient, 1_000uL, 1uL, isMaxAmount = isMax)
            store.retainPreparedReceipt(original.attemptId, 0, prepared.receipt, false)
            // No original broadcast: this seeds the uncertainty UI using actual native receipt provenance.
            val pending = store.recordOutcome(original.attemptId, OnchainSendOutcome.Unknown(prepared.receipt.txid), 0)
            File(context.getExternalFilesDir(null), "recovery-original-native-receipt.json")
                .writeText(Json.encodeToString(pending))
        } finally {
            lightningService.stop()
        }
        // Retain this disposable fixture for the real app UI journey; owned-device teardown removes it afterward.
    }
}

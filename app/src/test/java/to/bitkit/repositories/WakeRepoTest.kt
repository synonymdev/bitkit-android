package to.bitkit.repositories

import com.google.android.gms.tasks.Task
import com.google.firebase.messaging.FirebaseMessaging
import com.synonym.bitkitcore.WakeEnvironment
import com.synonym.bitkitcore.WakeException
import com.synonym.bitkitcore.WakeIdentityProof
import com.synonym.bitkitcore.WakePlatform
import com.synonym.bitkitcore.WakeRegistration
import com.synonym.bitkitcore.WakeRegistrationRequest
import com.synonym.bitkitcore.WakeServerInfo
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.WakeCredentialStore
import to.bitkit.data.WakeRegistrationState
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.json
import to.bitkit.services.WakeCredentials
import to.bitkit.services.WakeService
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class WakeRepoTest : BaseUnitTest() {
    companion object {
        private const val GATEWAY_URL = "http://127.0.0.1:9020"
        private const val AUDIENCE = "wake.localhost"
        private const val PUSH_TOKEN = "fcm-token"
        private const val NODE_ID = "023c72addb4fdf09af94f0c94d7fe92a386a7e70cf8a1d85916386bb2535c7b1b1"
        private const val PUBKY_KEY = "47cz1q7hnqtedgwnxt5k5pz5in1ji5oyx7r9fwr3fsc5ojppftry"
        private const val NOW_SECS = 1_790_000_000L
        private const val MESSAGE = "wake-register-v1"
        private const val LN_SIGNATURE = "ln-signature"
        private const val PK_SIGNATURE = "pk-signature"
        private const val LN_IDENTITY = "ln:$NODE_ID"
        private const val PK_IDENTITY = "pk:$PUBKY_KEY"

        private val CREDENTIALS = WakeCredentials(
            installId = "AAAAAAAAAAAAAAAAAAAAAA",
            encryptionSecretKey = "55".repeat(32),
            encryptionPublicKey = "02" + "9a".repeat(32),
            deviceSecret = "wkd_test_secret",
            deviceSecretSha256 = "a4".repeat(32),
        )
    }

    private val wakeService = mock<WakeService>()
    private val wakeConfig = mock<WakeConfig>()
    private val keychain = mock<Keychain>()
    private val lightningRepo = mock<LightningRepo>()
    private val pubkyRepo = mock<PubkyRepo>()
    private val firebaseMessaging = mock<FirebaseMessaging>()
    private val keychainValues = mutableMapOf<String, String>()
    private val preparedTimestamps = mutableListOf<Long>()
    private val preparedIdentities = mutableListOf<List<String>>()
    private val sentProofs = mutableListOf<List<WakeIdentityProof>>()
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(NOW_SECS)
    }

    private lateinit var sut: WakeRepo

    @Before
    fun setUp() {
        whenever(wakeConfig.gatewayUrl).thenReturn(GATEWAY_URL)
        whenever(wakeConfig.isRegistrationEnabled).thenReturn(true)
        whenever { wakeConfig.isOptedIn() }.thenReturn(true)
        whenever(keychain.loadString(any())).thenAnswer { keychainValues[it.getArgument(0)] }
        whenever { keychain.upsertString(any(), any()) }.thenAnswer {
            keychainValues[it.getArgument(0)] = it.getArgument(1)
            Unit
        }
        whenever { wakeService.generateCredentials() }.thenReturn(CREDENTIALS)
        whenever { wakeService.serverInfo(GATEWAY_URL) }.thenReturn(
            WakeServerInfo(audience = AUDIENCE, serverTime = NOW_SECS.toULong(), version = "1.0.0"),
        )
        whenever { wakeService.prepareRegistration(any(), any(), any(), any(), any(), any()) }.thenAnswer {
            val identities = it.getArgument<List<String>>(3)
            val timestampSecs = it.getArgument<Long>(5)
            preparedIdentities += identities
            preparedTimestamps += timestampSecs
            WakeRegistrationRequest(
                audience = it.getArgument(0),
                app = WakeService.APP,
                installId = CREDENTIALS.installId,
                platform = WakePlatform.FCM,
                environment = WakeEnvironment.PRODUCTION,
                pushToken = it.getArgument(2),
                encryptionPublicKey = CREDENTIALS.encryptionPublicKey,
                secretSha256 = CREDENTIALS.deviceSecretSha256,
                identities = identities,
                topics = it.getArgument(4),
                timestamp = timestampSecs.toULong(),
                message = MESSAGE,
            )
        }
        whenever { wakeService.register(any(), any(), any()) }.thenAnswer {
            val proofs = it.getArgument<List<WakeIdentityProof>>(2)
            sentProofs += proofs
            WakeRegistration(
                deviceId = "device-1",
                identities = proofs.map(WakeIdentityProof::identity),
                topics = WakeRepo.TOPICS,
                unknownTopics = emptyList(),
                serverTime = NOW_SECS.toULong(),
            )
        }
        whenever { wakeService.unregister(any(), any()) }.thenReturn(Unit)
        whenever(lightningRepo.getNodeId()).thenReturn(NODE_ID)
        whenever { lightningRepo.signMessage(MESSAGE) }.thenReturn(Result.success(LN_SIGNATURE))
        whenever { pubkyRepo.wakeIdentity() }.thenReturn(Result.success(null))
        whenever { pubkyRepo.signWakeRegistration(PK_IDENTITY, MESSAGE) }.thenReturn(Result.success(PK_SIGNATURE))
        val tokenTask = mock<Task<String>> {
            on { isComplete } doReturn true
            on { result } doReturn PUSH_TOKEN
        }
        whenever(firebaseMessaging.token).thenReturn(tokenTask)
        sut = createSut()
    }

    private fun createSut() = WakeRepo(
        ioDispatcher = testDispatcher,
        wakeService = wakeService,
        wakeConfig = wakeConfig,
        credentialStore = WakeCredentialStore(testDispatcher, keychain, wakeService),
        lightningRepo = lightningRepo,
        pubkyRepo = pubkyRepo,
        firebaseMessaging = firebaseMessaging,
        clock = clock,
    )

    @Test
    fun `a wallet without a Pubky identity registers ln alone`() = test {
        val result = sut.syncRegistration().getOrThrow()

        assertEquals(WakeSync.Registered(deviceId = "device-1", unknownTopics = emptyList()), result)
        assertEquals(listOf(listOf(LN_IDENTITY)), preparedIdentities)
        assertEquals(listOf(listOf(WakeIdentityProof(LN_IDENTITY, LN_SIGNATURE))), sentProofs)
        assertTrue(Keychain.Key.WAKE_CREDENTIALS.name in keychainValues, "Credentials are kept for the next launch")
    }

    @Test
    fun `a wallet with a Pubky identity registers ln and pk`() = test {
        whenever(pubkyRepo.wakeIdentity()).thenReturn(Result.success(PK_IDENTITY))

        sut.syncRegistration().getOrThrow()

        assertEquals(listOf(listOf(LN_IDENTITY, PK_IDENTITY)), preparedIdentities)
        assertEquals(
            listOf(listOf(WakeIdentityProof(LN_IDENTITY, LN_SIGNATURE), WakeIdentityProof(PK_IDENTITY, PK_SIGNATURE))),
            sentProofs,
        )
    }

    @Test
    fun `registration waits while the Pubky identity cannot be read rather than dropping pk`() = test {
        whenever(pubkyRepo.wakeIdentity()).thenReturn(Result.failure(PubkyIdentityUnavailableError))

        val result = sut.syncRegistration().getOrThrow()

        assertEquals(WakeSync.Waiting, result)
        verify(wakeService, never()).register(any(), any(), any())
    }

    @Test
    fun `registration waits for the node`() = test {
        whenever(lightningRepo.getNodeId()).thenReturn(null)

        assertEquals(WakeSync.Waiting, sut.syncRegistration().getOrThrow())
        verify(wakeService, never()).register(any(), any(), any())
    }

    @Test
    fun `nothing is sent again until something changed`() = test {
        sut.syncRegistration().getOrThrow()

        assertEquals(WakeSync.UpToDate, sut.syncRegistration().getOrThrow())
        assertEquals(WakeSync.UpToDate, createSut().syncRegistration().getOrThrow(), "Across launches")
        assertIs<WakeSync.Registered>(sut.syncRegistration(pushToken = "rotated-token").getOrThrow())
        assertEquals(2, sentProofs.size)
    }

    @Test
    fun `each registration is timestamped after the last one, even within the same second`() = test {
        sut.syncRegistration().getOrThrow()
        sut.syncRegistration(force = true).getOrThrow()

        assertEquals(listOf(NOW_SECS, NOW_SECS + 1), preparedTimestamps)
    }

    @Test
    fun `the timestamp stays past the last one across launches and clock steps`() = test {
        keychainValues[Keychain.Key.WAKE_REGISTRATION.name] =
            json.encodeToString(WakeRegistrationState(lastTimestampSecs = NOW_SECS + 5))

        createSut().syncRegistration().getOrThrow()

        assertEquals(listOf(NOW_SECS + 6), preparedTimestamps)
    }

    @Test
    fun `nextRegistrationTimestamp is max of now and last plus one`() {
        assertEquals(NOW_SECS, nextRegistrationTimestamp(nowSecs = NOW_SECS, lastSecs = null))
        assertEquals(NOW_SECS, nextRegistrationTimestamp(nowSecs = NOW_SECS, lastSecs = NOW_SECS - 10))
        assertEquals(NOW_SECS + 1, nextRegistrationTimestamp(nowSecs = NOW_SECS, lastSecs = NOW_SECS))
        assertEquals(NOW_SECS + 6, nextRegistrationTimestamp(nowSecs = NOW_SECS, lastSecs = NOW_SECS + 5))
    }

    @Test
    fun `a stale timestamp is signed again once at the gateway time`() = test {
        val serverTimeSecs = NOW_SECS - 700
        var attempts = 0
        whenever(wakeService.register(any(), any(), any())).thenAnswer {
            attempts++
            if (attempts == 1) {
                throw AppError(
                    WakeException.GatewayRejected(401u, "stale_timestamp", "stale", serverTimeSecs.toULong(), null),
                )
            }
            WakeRegistration("device-1", listOf(LN_IDENTITY), WakeRepo.TOPICS, emptyList(), NOW_SECS.toULong())
        }

        assertIs<WakeSync.Registered>(sut.syncRegistration().getOrThrow())
        assertEquals(listOf(NOW_SECS, serverTimeSecs), preparedTimestamps)
        assertEquals(2, attempts)
    }

    @Test
    fun `other rejections are not retried`() = test {
        var attempts = 0
        whenever(wakeService.register(any(), any(), any())).thenAnswer {
            attempts++
            throw AppError(WakeException.GatewayRejected(409u, "stale_registration", "stale", null, null))
        }

        assertTrue(sut.syncRegistration().isFailure)
        assertEquals(1, attempts)
        assertNull(WakeCredentialStore(testDispatcher, keychain, wakeService).registrationState().fingerprint)
    }

    @Test
    fun `nothing registers unless opted in, and opting out unregisters`() = test {
        whenever(wakeConfig.isOptedIn()).thenReturn(false)
        assertEquals(WakeSync.NotOptedIn, sut.syncRegistration().getOrThrow())
        verify(wakeService, never()).generateCredentials()

        whenever(wakeConfig.isOptedIn()).thenReturn(true)
        sut.syncRegistration().getOrThrow()
        whenever(wakeConfig.isOptedIn()).thenReturn(false)

        assertEquals(WakeSync.Unregistered, sut.syncRegistration().getOrThrow())
        verify(wakeService).unregister(GATEWAY_URL, CREDENTIALS.deviceSecret)

        whenever(wakeConfig.isOptedIn()).thenReturn(true)
        assertIs<WakeSync.Registered>(sut.syncRegistration().getOrThrow(), "Opting in again registers again")
    }

    @Test
    fun `wakes stay off without a gateway and in E2E builds`() = test {
        whenever(wakeConfig.isRegistrationEnabled).thenReturn(false)
        assertEquals(WakeSync.Off, sut.syncRegistration().getOrThrow())

        whenever(wakeConfig.isRegistrationEnabled).thenReturn(true)
        whenever(wakeConfig.gatewayUrl).thenReturn(null)
        assertEquals(WakeSync.Off, sut.syncRegistration().getOrThrow())

        verify(wakeService, never()).generateCredentials()
        verify(wakeService, never()).register(any(), any(), any())
    }

    @Test
    fun `the app lifecycle starts no wake work without a gateway`() = test {
        whenever(wakeConfig.gatewayUrl).thenReturn(null)

        sut.onAppForeground()
        sut.onAppBackground()

        verify(lightningRepo, never()).lightningState
        verify(wakeService, never()).setPresence(any(), any(), any())
        verify(wakeService, never()).clearPresence(any(), any())
    }

    @Test
    fun `unregister revokes the device secret and treats an unknown secret as done`() = test {
        sut.syncRegistration().getOrThrow()
        whenever(wakeService.unregister(any(), any())).thenAnswer {
            throw AppError(WakeException.GatewayRejected(401u, "unauthorized", "unauthorized", null, null))
        }

        assertTrue(sut.unregister().isSuccess)
        verify(wakeService).unregister(GATEWAY_URL, CREDENTIALS.deviceSecret)
    }

    @Test
    fun `wake identities use the bare keys the gateway expects`() {
        assertEquals(LN_IDENTITY, lightningWakeIdentity(NODE_ID))
        assertEquals(PK_IDENTITY, pubkyWakeIdentity("pubky$PUBKY_KEY"))
    }
}

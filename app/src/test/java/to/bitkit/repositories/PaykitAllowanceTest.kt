package to.bitkit.repositories

import com.synonym.paykit.AccountingAmount
import com.synonym.paykit.AllowanceAccountingHistory
import com.synonym.paykit.AllowanceAccountingState
import com.synonym.paykit.AllowanceAmountRange
import com.synonym.paykit.AllowanceHistoryStatus
import com.synonym.paykit.AllowanceLifecycleState
import com.synonym.paykit.AllowanceLocalRole
import com.synonym.paykit.AllowancePeriod
import com.synonym.paykit.AllowancePeriodLimit
import com.synonym.paykit.AllowanceRecord
import com.synonym.paykit.AllowanceTerms
import com.synonym.paykit.PaymentAccountingScope
import com.synonym.paykit.PaymentAttemptRecord
import com.synonym.paykit.PaymentDisposition
import com.synonym.paykit.PaymentExecutionMode
import com.synonym.paykit.PaymentExecutionStatus
import com.synonym.paykit.PaymentOccurrenceKey
import com.synonym.paykit.PaymentOccurrenceRecord
import org.junit.Test
import org.lightningdevkit.ldknode.Network
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.data.keychain.Keychain
import to.bitkit.services.PaykitReceiverPaths
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class PaykitAllowanceTest : BaseUnitTest() {
    private val fixtures = PaykitAllowanceFixtures

    // region Records

    @Test
    fun `record reads back sats, anchor, role and allowlist`() {
        val allowlist = listOf(fixtures.lightningIdentifier, fixtures.onchainIdentifier)
        val record = fixtures.record(
            state = AllowanceLifecycleState.PROPOSED,
            terms = fixtures.terms(allowedPaymentEndpointIdentifiers = allowlist),
            proposedByMe = true,
        )

        val allowance = checkNotNull(PaykitAllowance.from(record))

        assertEquals(fixtures.counterpartyKey, allowance.counterparty)
        assertEquals(PaykitReceiverPaths.WALLET, allowance.counterpartyReceiverPath)
        assertEquals(PaykitAllowanceFixtures.WALLET_ALLOWANCE_ID, allowance.allowanceId)
        assertEquals(PaykitAllowance.Role.ALLOWER, allowance.role)
        assertTrue(allowance.isAllower)
        assertTrue(allowance.isProposedByMe)
        assertEquals(AllowanceLifecycleState.PROPOSED, allowance.lifecycleState)
        assertEquals(5_000uL, allowance.perPaymentMaxSats)
        assertEquals(50_000uL, allowance.monthlyLimitSats)
        assertEquals(fixtures.septemberAnchor, allowance.monthlyAnchor)
        assertNull(allowance.activeFrom)
        assertNull(allowance.expiresAt)
        assertEquals(allowlist, allowance.allowedPaymentEndpointIdentifiers)
        assertEquals(Instant.parse("2026-09-02T10:00:00Z"), allowance.lastEventAt)

        val received = checkNotNull(
            PaykitAllowance.from(
                fixtures.record(
                    localRole = AllowanceLocalRole.ALLOWEE,
                    state = AllowanceLifecycleState.PROPOSED,
                    proposedByMe = false,
                ),
            ),
        )
        assertEquals(PaykitAllowance.Role.ALLOWEE, received.role)
        assertFalse(received.isAllower)
        assertFalse(received.isProposedByMe)
        assertTrue(received.isAnswerable)
    }

    @Test
    fun `record without usable role, terms or asset is skipped`() {
        assertNull(PaykitAllowance.from(fixtures.record(localRole = null)))
        assertNull(PaykitAllowance.from(fixtures.record(localRole = AllowanceLocalRole.UNKNOWN)))
        assertNull(PaykitAllowance.from(fixtures.record(terms = null)))
        assertNull(PaykitAllowance.from(fixtures.record(terms = fixtures.terms(asset = "usd"))))
    }

    @Test
    fun `status maps every lifecycle state`() {
        fun status(
            state: AllowanceLifecycleState,
            proposedByMe: Boolean = true,
            terms: AllowanceTerms = fixtures.terms(),
            now: Instant = fixtures.now,
        ): PaykitAllowance.Status {
            val record = fixtures.record(state = state, terms = terms, proposedByMe = proposedByMe)
            return checkNotNull(PaykitAllowance.from(record)).status(now)
        }

        assertEquals(PaykitAllowance.Status.AWAITING_ANSWER, status(AllowanceLifecycleState.PROPOSED))
        assertEquals(
            PaykitAllowance.Status.AWAITING_MY_ANSWER,
            status(AllowanceLifecycleState.PROPOSED, proposedByMe = false),
        )
        assertEquals(PaykitAllowance.Status.ACTIVE, status(AllowanceLifecycleState.ACCEPTED))
        assertEquals(PaykitAllowance.Status.DECLINED, status(AllowanceLifecycleState.REJECTED))
        assertEquals(PaykitAllowance.Status.ENDED, status(AllowanceLifecycleState.ENDED))
        assertEquals(PaykitAllowance.Status.CONFLICTED, status(AllowanceLifecycleState.CONFLICTED))
        assertEquals(PaykitAllowance.Status.CONFLICTED, status(AllowanceLifecycleState.UNKNOWN))

        val expiry = Instant.parse("2026-09-20T00:00:00Z")
        val expiring = fixtures.terms(expiresAt = "2026-09-20T00:00:00Z")
        val accepted = AllowanceLifecycleState.ACCEPTED
        assertEquals(PaykitAllowance.Status.ACTIVE, status(accepted, terms = expiring, now = expiry - 1.seconds))
        assertEquals(PaykitAllowance.Status.EXPIRED, status(accepted, terms = expiring, now = expiry))
        assertEquals(PaykitAllowance.Status.EXPIRED, status(accepted, terms = expiring, now = fixtures.now))

        val start = Instant.parse("2026-10-01T00:00:00Z")
        val scheduled = fixtures.terms(activeFrom = "2026-10-01T00:00:00Z")
        assertEquals(PaykitAllowance.Status.NOT_YET_ACTIVE, status(accepted, terms = scheduled, now = fixtures.now))
        assertEquals(PaykitAllowance.Status.ACTIVE, status(accepted, terms = scheduled, now = start))
    }

    @Test
    fun `sats from bitcoin amount reads the zero minimum`() {
        assertEquals(0uL, PaykitAllowance.satsFromBitcoinAmount("0"))
        assertEquals(0uL, PaykitAllowance.satsFromBitcoinAmount("0.00000000"))
        assertEquals(5_000uL, PaykitAllowance.satsFromBitcoinAmount("0.00005"))
        assertEquals(50_000uL, PaykitAllowance.satsFromBitcoinAmount("0.0005"))
        assertNull(PaykitAllowance.satsFromBitcoinAmount("abc"))
    }

    @Test
    fun `bitcoin decimal and trusted time round trip`() {
        assertEquals("0.00005", 5_000uL.toBitcoinDecimal())
        assertEquals("0.0005", 50_000uL.toBitcoinDecimal())
        assertEquals("2026-09-24T12:00:00Z", PaykitAllowanceTime.format(fixtures.now))
        val withMillis = Instant.parse("2026-09-24T12:00:00.123456Z")
        assertEquals("2026-09-24T12:00:00.123Z", PaykitAllowanceTime.format(withMillis))
        assertEquals(Instant.parse("2026-09-24T12:00:00.123Z"), PaykitAllowanceTime.parse("2026-09-24T12:00:00.123Z"))
        assertNull(PaykitAllowanceTime.parse("yesterday"))
    }

    // endregion

    // region Monthly window

    @Test
    fun `month start uses the UTC calendar month`() {
        assertEquals(fixtures.septemberAnchor, PaykitAllowanceTime.monthStart(fixtures.now))
        assertEquals(
            fixtures.septemberAnchor,
            PaykitAllowanceTime.monthStart(Instant.parse("2026-10-01T01:00:00+02:00")),
        )
        assertEquals(
            Instant.parse("2026-10-01T00:00:00Z"),
            PaykitAllowanceTime.monthStart(Instant.parse("2026-09-30T23:30:00-02:00")),
        )
    }

    @Test
    fun `monthly window from a first of month anchor`() {
        val cases = listOf(
            Triple("2026-09-01T00:00:00Z", "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z"),
            Triple("2026-09-24T12:00:00Z", "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z"),
            Triple("2026-09-30T23:59:59Z", "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z"),
            Triple("2026-10-01T00:00:00Z", "2026-10-01T00:00:00Z", "2026-11-01T00:00:00Z"),
            Triple("2026-12-31T23:59:59Z", "2026-12-01T00:00:00Z", "2027-01-01T00:00:00Z"),
            Triple("2027-02-10T08:00:00Z", "2027-02-01T00:00:00Z", "2027-03-01T00:00:00Z"),
            Triple("2026-08-31T23:59:59Z", "2026-08-01T00:00:00Z", "2026-09-01T00:00:00Z"),
            Triple("2026-07-15T12:00:00Z", "2026-07-01T00:00:00Z", "2026-08-01T00:00:00Z"),
        )

        for ((date, start, end) in cases) {
            val window = PaykitAllowanceTime.monthlyWindow(fixtures.septemberAnchor, Instant.parse(date))
            assertEquals(Instant.parse(start) to Instant.parse(end), window, "window for $date")
        }
    }

    @Test
    fun `monthly window clamps a January 31 anchor in February`() {
        val anchor = Instant.parse("2026-01-31T12:30:00Z")

        val midFebruary = PaykitAllowanceTime.monthlyWindow(anchor, Instant.parse("2026-02-15T00:00:00Z"))
        assertEquals(anchor, midFebruary.first)
        assertEquals(Instant.parse("2026-02-28T12:30:00Z"), midFebruary.second)

        val lateFebruary = PaykitAllowanceTime.monthlyWindow(anchor, Instant.parse("2026-02-28T12:30:00Z"))
        assertEquals(Instant.parse("2026-02-28T12:30:00Z"), lateFebruary.first)
    }

    /**
     * Vectors from paykit-lib `test_anchored_months_clamp_from_original_anchor`: every boundary is counted from the
     * original anchor, so the window after a clamped February still ends on March 31.
     */
    @Test
    fun `monthly window after a clamped February matches paykit anchored arithmetic`() {
        val anchor = Instant.parse("2026-01-31T12:30:00Z")
        val vectors = listOf(
            Triple("2026-02-28T12:30:00Z", "2026-02-28T12:30:00Z", "2026-03-31T12:30:00Z"),
            Triple("2026-03-30T12:30:00Z", "2026-02-28T12:30:00Z", "2026-03-31T12:30:00Z"),
            Triple("2025-12-01T00:00:00Z", "2025-11-30T12:30:00Z", "2025-12-31T12:30:00Z"),
        )
        for ((date, start, end) in vectors) {
            val window = PaykitAllowanceTime.monthlyWindow(anchor, Instant.parse(date))
            assertEquals(Instant.parse(start) to Instant.parse(end), window, "window for $date")
        }

        val beforeMarchAnchor = PaykitAllowanceTime.monthlyWindow(
            Instant.parse("2026-03-31T00:00:00Z"),
            Instant.parse("2026-01-15T00:00:00Z"),
        )
        assertEquals(Instant.parse("2025-12-31T00:00:00Z"), beforeMarchAnchor.first)
        assertEquals(Instant.parse("2026-01-31T00:00:00Z"), beforeMarchAnchor.second)

        val allowance = fixtures.allowance(monthlyAnchor = anchor)
        val lateMarchAttempt = fixtures.capacityAttempt(sats = 50_000uL, at = "2026-03-29T09:00:00Z")
        assertFalse(
            PaykitAllowanceCapacity.fits(
                1_000uL,
                allowance,
                listOf(lateMarchAttempt),
                Instant.parse("2026-03-30T12:30:00Z"),
            ),
            "A March 29 attempt at the cap must count on March 30",
        )
    }

    // endregion

    // region Capacity

    @Test
    fun `capacity fits under the cap`() {
        val attempts = listOf(fixtures.capacityAttempt(sats = 20_000uL, at = "2026-09-05T10:00:00Z"))

        assertEquals(20_000uL, fixtures.usedSats(attempts))
        assertTrue(PaykitAllowanceCapacity.fits(5_000uL, fixtures.allowance(), attempts, fixtures.now))
    }

    @Test
    fun `capacity fits exactly at the cap and rejects one sat over`() {
        val attempts = listOf(
            fixtures.capacityAttempt(sats = 20_000uL, at = "2026-09-05T10:00:00Z"),
            fixtures.capacityAttempt(sats = 25_000uL, at = "2026-09-12T10:00:00Z"),
        )

        assertTrue(PaykitAllowanceCapacity.fits(5_000uL, fixtures.allowance(), attempts, fixtures.now))
        val noPerPaymentMaximum = fixtures.allowance(perPaymentMaxSats = null)
        assertFalse(PaykitAllowanceCapacity.fits(5_001uL, noPerPaymentMaximum, attempts, fixtures.now))
    }

    @Test
    fun `capacity rejects over the per payment maximum`() {
        val allowance = fixtures.allowance()

        assertTrue(PaykitAllowanceCapacity.fits(5_000uL, allowance, emptyList(), fixtures.now))
        assertFalse(PaykitAllowanceCapacity.fits(5_001uL, allowance, emptyList(), fixtures.now))
    }

    @Test
    fun `capacity ignores failed attempts, previous months and other allowances`() {
        val attempts = listOf(
            fixtures.capacityAttempt(sats = 45_000uL, at = "2026-09-05T10:00:00Z", isLive = false),
            fixtures.capacityAttempt(sats = 50_000uL, at = "2026-08-31T23:59:59Z"),
            fixtures.capacityAttempt(
                allowanceId = PaykitAllowanceFixtures.SERVER_ALLOWANCE_ID,
                sats = 50_000uL,
                at = "2026-09-10T10:00:00Z",
            ),
            fixtures.capacityAttempt(sats = 1_000uL, at = "2026-09-01T00:00:00Z"),
            fixtures.capacityAttempt(sats = 10_000uL, at = "2026-09-12T10:00:00Z"),
        )

        assertEquals(11_000uL, fixtures.usedSats(attempts))
        assertTrue(PaykitAllowanceCapacity.fits(5_000uL, fixtures.allowance(), attempts, fixtures.now))
    }

    @Test
    fun `capacity without a monthly limit checks only the per payment maximum`() {
        val allowance = fixtures.allowance(monthlyLimitSats = null)
        val attempts = listOf(fixtures.capacityAttempt(sats = 1_000_000uL, at = "2026-09-05T10:00:00Z"))

        assertTrue(PaykitAllowanceCapacity.fits(5_000uL, allowance, attempts, fixtures.now))
    }

    @Test
    fun `attempts from history keep automatic allowance attempts`() {
        val history = AllowanceAccountingHistory(
            associations = emptyList(),
            occurrences = listOf(
                fixtures.occurrence(
                    requestId = "req-1",
                    attempts = listOf(
                        fixtures.attemptRecord(
                            id = "failed",
                            amount = "0.0001",
                            admittedAt = "2026-09-02T10:00:00Z",
                            status = PaymentExecutionStatus.FAILED,
                        ),
                        fixtures.attemptRecord(
                            id = "paid",
                            amount = "0.0001",
                            admittedAt = "2026-09-03T10:00:00Z",
                            status = PaymentExecutionStatus.SUCCEEDED,
                        ),
                    ),
                ),
                fixtures.occurrence(
                    requestId = "req-2",
                    attempts = listOf(
                        fixtures.attemptRecord(
                            id = "manual",
                            mode = PaymentExecutionMode.MANUAL,
                            allowanceId = null,
                            status = PaymentExecutionStatus.SUCCEEDED,
                        ),
                        fixtures.attemptRecord(
                            id = "unattributed",
                            allowanceId = null,
                            status = PaymentExecutionStatus.SUCCEEDED,
                        ),
                        fixtures.attemptRecord(
                            id = "open",
                            amount = "0.00002",
                            admittedAt = "2026-09-05T10:00:00Z",
                            status = PaymentExecutionStatus.SUBMITTED,
                        ),
                    ),
                ),
            ),
            watermarks = emptyList(),
        )

        val attempts = PaykitAllowanceCapacity.attempts(history)

        val walletId = PaykitAllowanceFixtures.WALLET_ALLOWANCE_ID
        assertEquals(
            listOf(
                PaykitAllowanceCapacity.Attempt(walletId, 10_000uL, Instant.parse("2026-09-02T10:00:00Z"), false),
                PaykitAllowanceCapacity.Attempt(walletId, 10_000uL, Instant.parse("2026-09-03T10:00:00Z"), true),
                PaykitAllowanceCapacity.Attempt(walletId, 2_000uL, Instant.parse("2026-09-05T10:00:00Z"), true),
            ),
            attempts,
        )
    }

    @Test
    fun `status and capacity use the given time`() {
        val allowance = fixtures.allowance(expiresAt = fixtures.now + 30.days)
        val attempts = listOf(fixtures.capacityAttempt(sats = 50_000uL, at = "2026-09-10T10:00:00Z"))

        assertEquals(PaykitAllowance.Status.ACTIVE, allowance.status(fixtures.now))
        assertEquals(PaykitAllowance.Status.EXPIRED, allowance.status(fixtures.now + 30.days))
        assertFalse(PaykitAllowanceCapacity.fits(1uL, allowance, attempts, fixtures.now))
        assertTrue(PaykitAllowanceCapacity.fits(1uL, allowance, attempts, Instant.parse("2026-10-01T00:00:00Z")))
    }

    // endregion

    // region Grouping and terms

    @Test
    fun `ordered receiver paths keep supported links with the wallet link first`() {
        assertEquals(
            listOf(PaykitReceiverPaths.WALLET, PaykitReceiverPaths.SERVER),
            PaykitAllowanceRepo.orderedReceiverPaths(
                listOf(PaykitReceiverPaths.SERVER, "a/other", PaykitReceiverPaths.WALLET, PaykitReceiverPaths.SERVER),
            ),
        )
        assertEquals(
            listOf(PaykitReceiverPaths.SERVER),
            PaykitAllowanceRepo.orderedReceiverPaths(listOf(PaykitReceiverPaths.SERVER)),
        )
        assertEquals(emptyList(), PaykitAllowanceRepo.orderedReceiverPaths(emptyList()))
    }

    @Test
    fun `entry primary prefers an accepted link, then the wallet link`() {
        val server = fixtures.allowance(
            allowanceId = PaykitAllowanceFixtures.SERVER_ALLOWANCE_ID,
            receiverPath = PaykitReceiverPaths.SERVER,
            perPaymentMaxSats = 1uL,
        )
        val wallet = fixtures.allowance()

        val entry = PaykitAllowanceEntry(id = "group", allowances = listOf(server, wallet), limits = fixtures.limits)
        assertEquals(PaykitAllowanceFixtures.WALLET_ALLOWANCE_ID, entry.primary.allowanceId)
        assertEquals(5_000uL, entry.perPaymentMaxSats)

        val serverOnly = PaykitAllowanceEntry(id = "server", allowances = listOf(server), limits = null)
        assertEquals(PaykitAllowanceFixtures.SERVER_ALLOWANCE_ID, serverOnly.primary.allowanceId)

        val walletDeclined = wallet.copy(lifecycleState = AllowanceLifecycleState.REJECTED)
        val acceptedOnServer = PaykitAllowanceEntry(id = "g", listOf(walletDeclined, server), limits = null)
        assertEquals(PaykitAllowanceFixtures.SERVER_ALLOWANCE_ID, acceptedOnServer.primary.allowanceId)

        val bothProposed = listOf(
            server.copy(lifecycleState = AllowanceLifecycleState.PROPOSED),
            wallet.copy(lifecycleState = AllowanceLifecycleState.PROPOSED),
        )
        val proposed = PaykitAllowanceEntry(id = "p", allowances = bothProposed, limits = null)
        assertEquals(PaykitAllowanceFixtures.WALLET_ALLOWANCE_ID, proposed.primary.allowanceId)
    }

    @Test
    fun `allowed endpoints are bolt11 and the network's on-chain methods`() {
        assertEquals(
            listOf(
                "btc-lightning-bolt11",
                "btc-regtest-p2tr",
                "btc-regtest-p2wpkh",
                "btc-regtest-p2sh",
                "btc-regtest-p2pkh",
            ),
            PaykitAllowanceRepo.allowedPaymentEndpointIdentifiers(Network.REGTEST),
        )
    }

    // endregion

    // region Store

    @Test
    fun `store keeps one state per identity and survives a corrupt value`() = test {
        val keychain = mock<Keychain>()
        var stored: String? = null
        whenever(keychain.loadString(any())).thenAnswer { stored }
        whenever(keychain.upsertString(any(), any())).doSuspendableAnswer { stored = it.getArgument(1) }
        val store = PaykitAllowanceStore(keychain)
        val entry = PaykitAllowanceLocalState.JournalEntry(
            attemptId = "attempt-1",
            isAutomatic = true,
            requestId = fixtures.paymentRequest().id,
            allowanceId = PaykitAllowanceFixtures.WALLET_ALLOWANCE_ID,
            amountSats = 1_000uL,
            paymentEndpointIdentifier = fixtures.lightningIdentifier,
            paymentHash = "ab".repeat(32),
            stage = PaykitAllowanceLocalState.Stage.SENT,
            createdAtMillis = fixtures.now.toEpochMilliseconds(),
        )
        val group = PaykitAllowanceLocalState.Group(
            id = "group-1",
            counterparty = fixtures.counterpartyKey,
            limits = fixtures.limits,
            allowanceIds = listOf(PaykitAllowanceFixtures.WALLET_ALLOWANCE_ID),
            createdAtMillis = 1L,
        )
        val state = PaykitAllowanceLocalState(
            groups = listOf(group),
            journal = listOf(entry),
            presentedProposalIds = setOf("proposal"),
            notifiedRequestIds = setOf("request"),
            lastTrustedTimeMillis = 42L,
        )

        store.save(fixtures.identityKey, state)

        assertEquals(state, store.load(fixtures.identityKey))
        assertEquals(PaykitAllowanceLocalState(), store.load(fixtures.otherCounterpartyKey))
        val loaded = store.load(fixtures.identityKey)
        assertEquals(group, loaded.group(containing = PaykitAllowanceFixtures.WALLET_ALLOWANCE_ID))

        stored = "{not json"
        assertEquals(PaykitAllowanceLocalState(), store.load(fixtures.identityKey))
    }

    // endregion
}

/** Shared builders for the Allowance suites. */
internal object PaykitAllowanceFixtures {
    const val WALLET_ALLOWANCE_ID = "allowance-wallet"
    const val SERVER_ALLOWANCE_ID = "allowance-server"
    val identityKey = "pubky" + "z".repeat(52)
    val counterpartyKey = "pubky" + "y".repeat(52)
    val otherCounterpartyKey = "pubky" + "x".repeat(52)
    val lightningIdentifier: String get() = MethodId.Bolt11.rawValue
    val onchainIdentifier: String get() = MethodId.P2wpkh.rawValue
    val now: Instant = Instant.parse("2026-09-24T12:00:00Z")
    val septemberAnchor: Instant = Instant.parse("2026-09-01T00:00:00Z")
    val limits = PaykitAllowanceLimits(
        perPaymentUsd = 5,
        monthlyUsd = 50,
        perPaymentSats = 5_000uL,
        monthlySats = 50_000uL,
    )

    @Suppress("LongParameterList")
    fun terms(
        perPaymentMaximum: String? = "0.00005",
        monthlyLimit: String? = "0.0005",
        anchor: String? = "2026-09-01T00:00:00Z",
        activeFrom: String? = null,
        expiresAt: String? = null,
        asset: String = PaykitIssuerInterop.BITCOIN_ASSET,
        allowedPaymentEndpointIdentifiers: List<String>? = listOf(lightningIdentifier),
    ): AllowanceTerms {
        val termsAsset = asset
        val termsActiveFrom = activeFrom
        val termsExpiresAt = expiresAt
        val allowlist = allowedPaymentEndpointIdentifiers
        val periodAnchor = anchor
        val range = perPaymentMaximum?.let { max ->
            mock<AllowanceAmountRange> {
                on { minimum() } doReturn "0"
                on { maximum() } doReturn max
            }
        }
        val monthlyPeriod = mock<AllowancePeriod> {
            on { kind() } doReturn "anchored"
            on { every() } doReturn 1uL
            on { unit() } doReturn "month"
            on { anchor() } doReturn periodAnchor
        }
        val periodLimit = mock<AllowancePeriodLimit> {
            on { amountLimit() } doReturn monthlyLimit
            on { paymentCountLimit() } doReturn null
            on { period() } doReturn monthlyPeriod
        }
        return mock {
            on { asset() } doReturn termsAsset
            on { perPaymentAmount() } doReturn range
            on { periodLimits() } doReturn listOf(periodLimit)
            on { lifetimeAmountLimit() } doReturn null
            on { activeFrom() } doReturn termsActiveFrom
            on { expiresAt() } doReturn termsExpiresAt
            on { allowedPaymentEndpointIdentifiers() } doReturn allowlist
        }
    }

    @Suppress("LongParameterList")
    fun record(
        allowanceId: String = WALLET_ALLOWANCE_ID,
        counterparty: String = counterpartyKey,
        receiverPath: String = PaykitReceiverPaths.WALLET,
        localRole: AllowanceLocalRole? = AllowanceLocalRole.ALLOWER,
        state: AllowanceLifecycleState = AllowanceLifecycleState.ACCEPTED,
        historyStatus: AllowanceHistoryStatus = AllowanceHistoryStatus.CONSISTENT,
        terms: AllowanceTerms? = terms(),
        proposedByMe: Boolean = true,
        lastEventAt: String? = "2026-09-02T10:00:00Z",
    ) = AllowanceRecord(
        counterparty = counterparty,
        counterpartyReceiverPath = receiverPath,
        allowanceId = allowanceId,
        localRole = localRole,
        state = state,
        historyStatus = historyStatus,
        proposalEventId = "proposal-$allowanceId",
        terms = terms,
        proposalStreamItemId = if (proposedByMe) null else 1uL,
        proposalOutboundMessageId = if (proposedByMe) 1uL else null,
        proposalOutboundStatus = null,
        acceptanceEventId = null,
        acceptanceOutboundStatus = null,
        rejectionEventId = null,
        rejectionOutboundStatus = null,
        endEventId = null,
        endOutboundStatus = null,
        pendingCausalEventIds = emptyList(),
        conflictEventIds = emptyList(),
        lastStreamItemId = null,
        lastOutboundMessageId = null,
        lastOutboundStatus = null,
        lastEventAt = lastEventAt,
        invalidReason = null,
    )

    @Suppress("LongParameterList")
    fun allowance(
        allowanceId: String = WALLET_ALLOWANCE_ID,
        counterparty: String = counterpartyKey,
        receiverPath: String = PaykitReceiverPaths.WALLET,
        role: PaykitAllowance.Role = PaykitAllowance.Role.ALLOWER,
        state: AllowanceLifecycleState = AllowanceLifecycleState.ACCEPTED,
        perPaymentMaxSats: ULong? = 5_000uL,
        monthlyLimitSats: ULong? = 50_000uL,
        monthlyAnchor: Instant? = septemberAnchor,
        expiresAt: Instant? = null,
        lastEventAt: Instant? = null,
    ) = PaykitAllowance(
        id = PaykitAllowance.Id(counterparty, receiverPath, allowanceId),
        role = role,
        lifecycleState = state,
        isProposedByMe = role == PaykitAllowance.Role.ALLOWER,
        perPaymentMaxSats = perPaymentMaxSats,
        monthlyLimitSats = monthlyLimitSats,
        monthlyAnchor = monthlyAnchor,
        expiresAt = expiresAt,
        allowedPaymentEndpointIdentifiers = listOf(lightningIdentifier),
        lastEventAt = lastEventAt,
    )

    fun capacityAttempt(
        allowanceId: String = WALLET_ALLOWANCE_ID,
        sats: ULong,
        at: String,
        isLive: Boolean = true,
    ) = PaykitAllowanceCapacity.Attempt(allowanceId, sats, Instant.parse(at), isLive)

    fun usedSats(attempts: List<PaykitAllowanceCapacity.Attempt>): ULong =
        PaykitAllowanceCapacity.usedSats(WALLET_ALLOWANCE_ID, attempts, septemberAnchor, now)

    fun accountingAmount(amount: String, currency: String = PaykitIssuerInterop.BITCOIN_ASSET): AccountingAmount =
        mock {
            on { value() } doReturn amount
            on { asset() } doReturn currency
        }

    @Suppress("LongParameterList")
    fun attemptRecord(
        id: String,
        mode: PaymentExecutionMode = PaymentExecutionMode.AUTOMATIC,
        allowanceId: String? = WALLET_ALLOWANCE_ID,
        amount: String = "0.00001",
        admittedAt: String = "2026-09-24T12:00:00Z",
        status: PaymentExecutionStatus,
        epoch: String = "epoch-1",
    ) = PaymentAttemptRecord(
        attemptId = id,
        mode = mode,
        allowanceId = allowanceId,
        associationRevision = allowanceId?.let { 1uL },
        amount = accountingAmount(amount),
        admittedAt = admittedAt,
        status = status,
        epoch = epoch,
    )

    fun accountingScope(paymentRequestId: String) = PaymentAccountingScope(
        localPublicKey = identityKey,
        localReceiverPath = PaykitReceiverPaths.WALLET,
        counterparty = counterpartyKey,
        counterpartyReceiverPath = PaykitReceiverPaths.WALLET,
        paymentRequestId = paymentRequestId,
    )

    fun occurrence(
        requestId: String,
        attempts: List<PaymentAttemptRecord>,
        allowanceId: String? = WALLET_ALLOWANCE_ID,
    ) = PaymentOccurrenceRecord(
        key = PaymentOccurrenceKey(request = accountingScope(requestId), billingPeriod = null),
        disposition = PaymentDisposition.Automatic,
        allowanceId = allowanceId,
        associationRevision = allowanceId?.let { 1uL },
        attempts = attempts,
    )

    fun accountingState(
        revision: ULong = 1uL,
        epoch: String = "epoch-1",
        requiresReconciliation: Boolean = false,
        occurrences: List<PaymentOccurrenceRecord> = emptyList(),
    ) = AllowanceAccountingState(
        revision = revision,
        epoch = epoch,
        requiresReconciliation = requiresReconciliation,
        history = AllowanceAccountingHistory(
            associations = emptyList(),
            occurrences = occurrences,
            watermarks = emptyList(),
        ),
    )

    @Suppress("LongParameterList")
    fun paymentRequest(
        id: String = "550e8400-e29b-41d4-a716-446655440001",
        counterparty: String = counterpartyKey,
        receiverPath: String = PaykitReceiverPaths.WALLET,
        amountValue: String = "0.00001",
        amountSats: ULong = 1_000uL,
        createdAt: String = "2026-09-24T11:00:00Z",
    ) = PaykitPaymentRequest(
        paymentRequestId = id,
        counterparty = counterparty,
        counterpartyReceiverPath = receiverPath,
        amountValue = amountValue,
        amountSats = amountSats,
        createdAt = Instant.parse(createdAt),
        expiresAt = null,
        acceptedPaymentEndpointIdentifiers = listOf(lightningIdentifier),
    )
}

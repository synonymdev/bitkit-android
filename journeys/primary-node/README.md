# Primary node

Use [the local build and regtest setup](../../docs/primary-node-wallet.md) before running these cases.
The required branches are `feat/primary-node-wallet` in Android, `feat/lfbw-wallet-client` in LDK Node,
and `fix/jit-skim-fee-tlv` for a Beignet provider. Keep their local working-tree changes and use the
rebuilt local Android AAR. [LDK Node Umbrel 0.1.12](https://github.com/coreyphillips/ldk-node-umbrel/releases/tag/v0.1.12)
contains the native direct-funding changes and has published, verified images for both architectures.
The Beignet provider fixture uses its local source branch.

Install an `E2E=true E2E_BACKEND=local` dev APK on a disposable emulator and create a fresh wallet.
Host Electrum defaults to port 60001 and must use the same regtest chain as the provider. Override it
with `E2E_ELECTRUM_URL=tcp://10.0.2.2:60412` for the isolated direct-funding fixture described in the
[qualification record](../../docs/qualification/direct-funding-android.json). Keep the provider online and running throughout a journey, with funds for payment cases. The pairing case
uses a provider with no spendable onchain funds. Run connected instrumentation before
manual setup because its cleanup can uninstall the app. For compact-screen checks use 720 by 1280
pixels at 320 dpi, equivalent to 360 by 640 dp; changing density to fit a button does not verify the fix.
Use only regtest coins. The app's backup and authentication endpoints still use staging.

`pair-primary-node.xml` checks manual validation, QR entry, explicit connection and saved configuration.
Use a compatible regtest provider with its liquidity provider enabled and no spendable onchain funds.
Pairing must succeed when the protocol responds, even though a receive quote would be declined for
insufficient funding. The app and provider must share
the same chain. The provider URI must contain an address the emulator can reach, such as `10.0.2.2`
for a service on the development host. An ordinary Lightning peer is not sufficient.

The screen accepts a bare compressed public key, but the runtime can only use it when it already knows
a reachable address for that node. Use a full URI when testing a new provider. Scanning a URI fills the
form; connection starts only after tapping Connect node. Failed setup must keep the previous primary
and show an inline error.

This feature currently exists only on Android. The matching iOS primary node flow is not implemented.

`unified-wallet.xml` verifies the home balance, receiving fallback and removal of paid setup prompts.
`receive-primary-quote.xml` verifies amount entry, fee review, explicit invoice approval and settlement
when new LFBW liquidity is required. Existing ready inbound liquidity permits an ordinary fixed-amount
or amountless Lightning invoice without primary skim-fee approval. Ordinary invoices default to a
24-hour expiry; invoices approved through the primary quote flow expire within ten minutes.
`force-close-cancel.xml` verifies that advanced recovery requires confirmation and cancellation leaves
the channel open. `send-single-bitcoin-source.xml` checks that the software wallet appears once in
send source selection while hardware devices remain separate. `send-primary-onchain.xml` checks
fee-aware maximum amounts, exact recipient amounts and paying an address from the primary channel.
`close-and-resume-funding.xml` covers explicit primary closure, a durable pause in automatic funding
and resumption only after a verified Connect node action. Closing must leave returned funds available
onchain across app restarts so the user can choose another primary without an automatic reopen.

Repeat receive and send cases with both provider engines. Receiving requires Bitkit to stay online;
notification permission does not provide offline primary receiving. An ordinary invoice may remain
displayed while its peer is offline, but it cannot settle without an online, reachable receiving path.
With notifications denied, a second received payment must not show the retired Get paid passively
prompt. Automatic funding must
not show Spending Balance Ready. On a compact display, Continue must be entirely above the navigation
bar and below the keypad; its physical tap must advance without changing the amount. Fee details and
errors may scroll while approval and retry remain visible.

After a confirmed deposit triggers automatic primary funding, verify that its activity row and detail
show Connection fee with only the mining fee as the debit. The detail shows the funded amount as
Within your wallet. Repeat after another funding operation and app restart. Existing legacy transfer
history keeps its original labels, and external splice payments remain ordinary sent payments.
After mining an external splice payment, refresh activity and verify that it becomes confirmed with
the recipient amount and mining fee unchanged, including after a subsequent automatic funding operation.
An unavailable backend must preserve the last known confirmation state.

The old transfer-to-spending and CJIT journey suites describe the retired Blocktank flows. They are
historical coverage and are not runnable on this branch. New-opening legacy deep links now open the
primary-node editor. Existing channel history and recovery remain accessible in Advanced settings.
The receive tab names are now Bitcoin and Lightning; Bitcoin is always available and falls back to an
onchain request while a Lightning request is unavailable. Previous Savings/Auto tab-switch journeys
are superseded by the cases here.

Parts of J1 through J7 have been exercised manually on the disposable emulator. A partial check is
not a completed journey: initial runs found receive-policy, confirmation-refresh and compact-layout
issues that were corrected and rechecked on later candidates. That earlier primary-wallet APK passed all 22 targeted device tests. A quoted 20,000 sat request with a 120 sat fee received
19,880 sats, increased the wallet balance from 48,444 to 68,324 sats and cleared the paid QR.
Receive-history mapping, the compact invoice editor, offline quote retry after provider restart and passive disconnect invalidation have also passed manual checks. The history shows one net credit of 19,880 sats and a fee of 120 sats. An unpaid request also cleared its Lightning invoice at its encoded ten-minute expiry while preserving the Bitcoin fallback. The local
[device record](../../.ai/primary-node-qa/results.md) identifies the build and exact steps executed.
Hardware, settlement through a quoted request's onchain fallback,
exact unified-request source selection and the pending duplicate-send window
remain unrun on device unless that record explicitly reports a later result. Native fixtures and unit
tests are supporting evidence, not substitutes for those device cases.

For splice growth, create a fresh primary channel by depositing 50,000 sats, then receive a quoted
100,000 sat Lightning payment through that channel. Verify that the provider grows the channel and
Bitkit credits the approved net amount once. This must exceed the channel's original opening capacity.
Older channels keep their original negotiated limits and require an explicit close and reopen before
this check. An oversized request on an older channel must fail before a primary invoice is issued.

That earlier primary-wallet APK also passed migration through explicit close, persistent funding pause, failed primary
replacement, and verified resume. A new 66,511 sat channel grew to 186,511 sats for a 100,000 sat
receive, credited 99,800 sats with a 200 sat fee, and then sent 70,000 sats with zero fee. Both payments
exceeded the original channel capacity and appear once in history. See the local
[final growth record](../../.ai/primary-node-qa/final-growth-results.md).

## Direct channel funding

The current native working tree adds Beignet v3 direct funding. Rebuild the AAR and Android APK;
version 199 artifacts described above do not include this feature. Run the cases below with an
online compatible LDK or Beignet primary and a separate sender on the same regtest chain. The
receiver needs a configured primary; it may start with no channel or an existing ready channel.
The sender needs one software-wallet coin with at least three confirmations covering the request plus the 1,000-sat fee
ceiling. Keep automatic primary funding paused on that sender, or use a sender without a primary,
so its test coin remains onchain. The provider must support relay forwarding and external payer
funding; stock public Lightning peers cannot act as this fixture. Standard staging alone does not
provide that setup.

`receive-direct-funding.xml` verifies AUTO/Bitcoin advertises bgnq without changing Lightning-only
copying, and checks amount changes, disconnect and expiry. `send-direct-funding.xml` verifies
explicit onchain funding selection, the authenticated receiver and maximum-fee review, one activity
record and persistence after restart. Use a real signed request copied from the compatible receiver;
a fabricated string is suitable only for the malformed-request rejection step. Repeat the funded
case with each receiver/provider engine and once with no initial receiving channel, then again
with an existing receiving channel to verify a splice. These are new cases; previous primary-wallet
qualification is not evidence that they passed.

The sender does not support external hardware signing or Paykit payment-proof contexts for bgnq.
Such a request is rejected before sending. Plain BIP21 requests retain their single Bitcoin-source
behavior. A bgnq request exposes separate Lightning and Direct channel funding choices when both
are available. Direct funding requires an actual confirmed onchain coin, not only channel balance.
Incoming channel credit is labeled separately from an own-wallet connection fee. A signed pending
attempt must not enable fee boosting or an alternate payment. Restart recovery retains its native
request identity and updates the same activity record once the funding transaction is confirmed.

The matching iOS direct-funding feature is not implemented; these journeys are Android-only.

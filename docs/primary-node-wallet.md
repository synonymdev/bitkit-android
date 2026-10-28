# Primary node wallet

This local feature keeps Bitkit's LDK wallet and replaces the paid Blocktank setup flow with a primary node. Pair a compatible node in Settings or from the home shortcut by pasting or scanning its public key and network address. A public key alone works when Bitkit already knows a reachable address for that node.

The backup branch is `feat/primary-wallet-backup`. Recover its native dependency from [coreyphillips/ldk-node commit `437ee29947f96c486cf01a74bf941539b49d3b79`](https://github.com/coreyphillips/ldk-node/commit/437ee29947f96c486cf01a74bf941539b49d3b79), on `feat/lfbw-wallet-client`. This frozen revision includes the primary-wallet and direct-funding APIs, current Chicory compatibility, and FFOR removal. No bitkit-core source changes are required. The existing TCP and Iroh primary transports and pinned Iroh source-build scripts are included in this Android snapshot. No Tor transport is included, and the default primary is empty.

The records below describe earlier Android builds and their original native inputs. They do not qualify this backup branch or the frozen native revision above. Published [LDK Node Umbrel 0.1.12](https://github.com/coreyphillips/ldk-node-umbrel/releases/tag/v0.1.12) also predates the current native changes; the newer Umbrel candidate qualification does not replace that release. The following table records historical initial development baselines, not source pins for a fresh build. Historical Android artifacts and results are recorded in [the direct-funding qualification](qualification/direct-funding-android.json).

| Repository | Local branch | Historical initial base commit |
| --- | --- | --- |
| bitkit-android | `feat/primary-node-wallet` | `eadda6c440390a8179a1e70da1c9e4120579c3cc` |
| ldk-node | `feat/lfbw-wallet-client` | `b63959d535fd66995b3c89213e102aa4f6d5f327` |
| beignet | `fix/jit-skim-fee-tlv` | `cc473ff08cd37cd5d0c4e60dae5032ffc34d079b` |

## Protocol compatibility

The frozen native dependency removes FFOR receive, its feature advertisements, and its offline-custody APIs. Primary-wallet funding, online receive, direct funding and splices remain available. Bitkit does not provide offline receiving. The feature-bit allocation described in the historical version 198 record belonged to that earlier native library and is not a capability of the current dependency. Rebuild the Android AAR from the frozen source rather than reusing an older qualified artifact.

## Behavior

Pairing authenticates the selected Lightning peer and checks that it responds to the LFBW protocol. A valid quote refusal still proves protocol support, so a compatible primary can be paired before it has funds. Pairing does not require approval of a sample receive or reserve liquidity. Actual Lightning receives still require the provider to accept the requested amount and the user to approve its fee.

The home setup banner disappears after a primary URI is saved and stays hidden across restarts, disconnection and insufficient provider funds. The configured primary remains accessible from Settings. Home displays one software-wallet Bitcoin balance. Hardware wallets and USDT remain separate. The Bitcoin receive tab always offers an onchain address. When an existing ready channel has sufficient inbound liquidity, Bitkit can create an ordinary fixed-amount or amountless Lightning invoice without primary skim-fee approval. Ordinary invoices default to a 24-hour expiry. An ordinary invoice may remain displayed while its peer is offline, but payment cannot settle without an online, reachable receiving path.

Requesting new liquidity through LFBW requires an amount, a primary quote and explicit approval of the maximum receive fee. Insufficient provider funds, an amount below the receive fee, a declined request and a timeout have separate localized explanations. Retrying obtains a new fee quote and still requires explicit approval before creating an invoice. That invoice is bound to the approved primary and fee ceiling, and expires within ten minutes. Changing the primary, disconnecting, expiry or matching settlement clears the approved primary invoice. Keep Bitkit online while receiving; neither invoice flow provides offline receiving.

Confirmed deposits are automatically moved into the primary channel when at least 25,000 sats are available and estimated funding fees are no more than 5 percent. The native wallet spends only confirmed inputs for this funding. Smaller deposits remain available onchain. Automatic funding and outgoing onchain operations share a persistent operation gate. Explicitly closing the primary channel pauses automatic funding before the close request, so returned funds stay onchain across restarts. The Primary node screen shows the pause. A successful explicit Connect node action resumes funding for the verified primary; a failed connection leaves the pause in place. Ambiguous close failures retain the pause. Pausing does not erase pending payment evidence or history.

Ordinary onchain sends use onchain funds when sufficient, otherwise the primary channel can fund the destination through a splice. The requested destination amount and actual transaction fee must be verified before Bitkit reports success. Confirmation updates for these external outputs use the same configured chain backend and retain prior state on lookup failure. An uncertain operation stays blocked across restarts until native evidence resolves it. Advanced connection settings retain cooperative and force-close recovery.

Automatic primary funding appears in activity as a connection fee, with the actual mining fee as the debit. The funded amount remains within the wallet. Legacy transfer history retains its original meaning. Primary wallets do not show the retired background-receiving onboarding or spending-setup notifications. Amount entry and fee review keep their actions reachable on compact screens.

## Current limits

- The primary must be online and explicitly support the LFBW protocol. Pairing an ordinary Lightning node fails the capability check. A paired provider without enough available funding cannot offer new receive liquidity until its funding requirements are met.
- Selecting a primary trusts its unconfirmed channel funding. TCP and Iroh primary transports are included in this snapshot; Tor transport is not included. Neither transport adds offline receiving.
- Changing to a different primary requires closing the existing primary channel and resolving pending operations first.
- Newly opened private primary channels negotiate an inbound HTLC limit that can accommodate later splices. Existing channels retain their original negotiated limit and must be explicitly closed and reopened to gain this behavior. Requests above an older channel's limit are rejected before issuing a primary invoice.
- The displayed total includes both onchain and channel funds, but one send cannot combine independent balances. Available send amounts reflect the usable source and fees.
- Onchain payments with contact, purchase, subscription or payment-request metadata still require an onchain balance. Their durable payment-proof flow has not been extended to splices.
- Primary invoices created after an LFBW receive quote expire within ten minutes. Ordinary channel invoices use the default 24-hour expiry. Offline receive and FFOR client support are outside this change.
- External splice confirmation uses the configured chain backend, which remains trusted for active-chain identity. Bitcoin Core requires its transaction index for confirmed external transaction lookups. Missing or inconsistent evidence leaves the last known activity status unchanged.
- The primary-operation journal is encrypted on this device and survives app restarts. It is not yet part of VSS device-restore backups, so restoring onto another device can lose pending splice payment context and tags even when native channel state is restored.
- Existing channel history and recovery remain available. Legacy transfer and Blocktank setup journeys are superseded by the primary-node journeys.
- Initial qualification exercised Beignet and native LDK provider engines. The October 26 follow-up also exercised an existing packaged LDK Node Umbrel image on Mint regtest, as recorded below. No user deployment was changed, and the matching iOS flow is not implemented.

## Build and device setup

On another machine, clone `https://github.com/coreyphillips/ldk-node.git` beside this Android checkout and check out `437ee29947f96c486cf01a74bf941539b49d3b79`. Build it with `./bindgen.sh android`, using Rust/rustup, Java 17, an Android SDK and NDK 28.2.13676358. Set `JAVA_HOME`, `ANDROID_HOME` and `ANDROID_NDK_ROOT` to those installations for the native build. The launcher installs its pinned Gobley binding generator and cargo-ndk 3.5.4, adds the Android Rust targets, generates matching Kotlin bindings, and packages armeabi-v7a, arm64-v8a and x86_64 libraries. The resulting artifact is `bindings/kotlin/ldk-node-android/lib/build/outputs/aar/lib-release.aar`. Keep the generated bindings and native libraries from the same source revision.

Build the required Iroh AAR from the Android checkout with `bash scripts/build-iroh-android.sh`, after the native build has installed cargo-ndk and the Rust targets. Set `ANDROID_NDK_HOME` to the same NDK directory. The script pins Iroh FFI revision `5e451092dba0c1a09ee83ff6e5be37b1152a5c58`, verifies source and published AAR download hashes, rebuilds the three native ABIs with 16 KB alignment, and uses `scripts/package-iroh-android.py` to verify the generated API checksums and package the unchanged published Kotlin classes. Its default outputs are `.local-native/iroh-android-1.1.0-16kb.aar` and a companion JSON build record. Override the destination with `IROH_ANDROID_AAR`, and select an alternate artifact for Gradle with `-PirohAndroidAar=/absolute/path/to/iroh-android-1.1.0-16kb.aar`. The ignored AAR is not included in this backup.

The historical direct-funding AAR has SHA-256 `f2c95398bed9ac387f548bddc8b4d27edcd908541a527734d1d05e892122690c`. That qualification's final arm64 regtest QA APK has SHA-256 `639c778fe7025beaed0c3cb6ccffbfaa7041a3624fee731a361b8469d8a960f8`, application ID `to.bitkit.dev` and version code 195. These artifact identities and their exact checks are in [the Android qualification record](qualification/direct-funding-android.json). They are not fingerprints of the new frozen native build. Rebuilding can also produce a different archive hash. Historical version 199 used AAR `881c318c9c7e3fc473419d04aed65766e22cfd754b18bd457f76593001a3f582`, saved at `.local-native/ldk-node-splice-out-199.aar` and selected through `ORG_GRADLE_PROJECT_ldkNodeAar`; that artifact predates direct funding. Record the newly built sibling artifact from the Android checkout with:

```sh
shasum -a 256 ../ldk-node/bindings/kotlin/ldk-node-android/lib/build/outputs/aar/lib-release.aar
```

The Android checkout uses that sibling artifact by default, or an explicit `-PldkNodeAar` path. Other native artifacts can be provided with `-PbitkitCoreAar`, `-PpaykitAar` and `-PvssAar` when GitHub Packages credentials are unavailable. This checkout already has matching versioned artifacts in the ignored `.local-native` directory, which the build detects automatically for local review. They are core 0.8.0-rc9, Paykit 0.1.0 and VSS 0.5.23. Run `just compile`, `just test` and `just lint` with a compatible Android JDK and SDK. Release core dependency resolution still uses the repository's package source.

On the review Mac, the following commands use Android Studio's bundled JDK. On another machine, substitute its JDK and SDK paths. Install `just`, or run its equivalent Gradle tasks from `justfile`.

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
just compile
just test
just lint
E2E=true E2E_BACKEND=local E2E_HOMEGATE_URL=https://homegate.staging.pubky.app ./gradlew assembleDevDebug
E2E=true E2E_BACKEND=local E2E_HOMEGATE_URL=https://homegate.staging.pubky.app ./gradlew installDevDebug
adb shell monkey -p to.bitkit.dev -c android.intent.category.LAUNCHER 1
```

For an artifact outside the sibling checkout, export `ORG_GRADLE_PROJECT_ldkNodeAar=/absolute/path/to/lib-release.aar` before those commands. The other three override properties accept the same `ORG_GRADLE_PROJECT_` prefix. The ignored `.local-native` files are local prerequisites, not files available from a fresh clone. Obtain matching artifacts or configure package credentials before building elsewhere.

Local endpoint overrides in `local.properties`, downloaded artifacts, device state, and `.ai/` logs are excluded from the source backup. Links to `.ai/` below describe historical local evidence and will not resolve in a fresh clone. The committed qualification JSON retains the summarized results and artifact identities; none of the wallet stores or signing material should be copied into source control.

Fresh builds have no default primary. Pair a node explicitly in Settings, or supply `DEFAULT_PRIMARY_NODE_URI` through the environment, a Gradle property, or the ignored `local.properties` file for a deliberate mainnet build override, in that precedence order. That override is ignored on non-mainnet networks. Use a supported TCP or Iroh primary URI; an onion address is not usable without a Tor transport. Do not commit a personal endpoint or local configuration with the source backup.

For disposable regtest device testing, build `assembleDevDebug` with `E2E=true` and `E2E_BACKEND=local`. The emulator defaults to host Electrum on `10.0.2.2:60001`. The current isolated direct-funding device qualification used `E2E_ELECTRUM_URL=tcp://10.0.2.2:60412` on the same disposable chain as its provider. Set `E2E_ELECTRUM_URL` to use another regtest Electrum endpoint, such as `tcp://192.168.50.211:60401` for Mint. This override applies only when the regtest flavor has both E2E and the local backend enabled. The provider must use that same regtest chain and expose a reachable Lightning port. Use its full URI with `10.0.2.2` as the host. Create a fresh test wallet and use only regtest funds. The app's existing backup and authentication services still use staging.

The initial primary-wallet qualification used Beignet's `docker/docker-compose.yml` at the historical base commit above: Bitcoin Core 31.0 on host RPC port 43782 with `txindex=1`, and Electrum on host port 60001. Start its `bitcoind`, `bitcoinsetup` and `electrs` services if that disposable chain is not already running. Set `LFBW_REGTEST_RPC_URL` to its local `rpc://user:password@host:port` endpoint. The native fixtures require a funded RPC wallet named `default`, or `--faucet-wallet` selecting another funded wallet; each run draws 0.03 regtest BTC. Do not run fixtures concurrently because they mine on the shared chain.

From the LDK checkout, the reproducible provider-engine checks are:

```sh
cargo build --example chicory-primary --features chicory --locked
python3 scripts/primary-wallet-regtest.py --provider ldk
python3 scripts/primary-wallet-regtest.py --provider beignet --beignet-root /path/to/beignet
python3 scripts/primary-wallet-regtest.py --provider beignet --beignet-root /path/to/beignet --client-chain-url tcp://127.0.0.1:60001
```

Build the patched Beignet checkout first with `npm run build`, with Node.js on `PATH`. These harnesses create fresh stores and stop their providers after execution. For Android journeys, leave a separate funded provider running against the same chain. The LDK driver can be started interactively with `target/debug/examples/chicory-primary /path/to/fresh-provider-store 39735 "$LFBW_REGTEST_RPC_URL"`. It prints its node URI and deposit address. Fund that address with regtest coins, mine confirmations, and enter `{"id":1,"method":"sync"}` on the driver's stdin. Keep the process and stdin open while testing. Pair its printed URI in Bitkit after replacing `127.0.0.1` with `10.0.2.2`. The Beignet adapter and provider instructions are in the [native client notes](../../ldk-node/docs/primary-wallet-client.md).

During the initial primary-wallet qualification, the existing Umbrel wrappers could consume those local engines without source changes. LDK Node Umbrel's `scripts/build-images.sh` accepts `LDK_SOURCE_DIR` pointing to this native checkout and copies its working-tree changes, including the vendored Lightning fixes. Its standalone daemon passed `cargo check --manifest-path tools/ldk-node-daemon/Cargo.toml --locked`. Beignet Umbrel's Dockerfile prefers `vendor/beignet-local.tgz`; build and pack this Beignet checkout, then copy that package to the override path before building a local image. Without that tarball it uses the published npm version, which lacks these changes. Its daemon environment suite passed 22 tests. Neither an image build nor deployment was performed during that initial qualification. The later direct-funding release [LDK Node Umbrel 0.1.12](https://github.com/coreyphillips/ldk-node-umbrel/releases/tag/v0.1.12) has built, published and verified images for both supported architectures. No user Umbrel deployment was changed by this qualification.

Use a 720 by 1280 emulator at its original density of 320 dpi for compact-layout checks, equivalent to 360 by 640 dp. Run instrumentation before manual wallet setup because the connected-test task can uninstall the app afterward. Keep each provider's store and identity across its restart checks; use a new app wallet and provider store for an independent run.

## Historical primary-wallet qualification

- J1: `journeys/primary-node/pair-primary-node.xml` covers validation, pairing and persistence.
- J2: `journeys/primary-node/unified-wallet.xml` covers home and onchain fallback.
- J3: `journeys/primary-node/receive-primary-quote.xml` covers fee approval and settlement.
- J4: `journeys/primary-node/force-close-cancel.xml` covers recovery confirmation.
- J5: `journeys/primary-node/send-single-bitcoin-source.xml` covers send-source selection.
- J6: `journeys/primary-node/send-primary-onchain.xml` covers channel-funded onchain sends and fee-aware limits.
- J7: `journeys/primary-node/close-and-resume-funding.xml` covers explicit closure, persistent funding pause and verified resume.

The initial Android qualification passed `just compile`, `just test` (4,492 tests with no failures, errors or skips) and `just lint` against native AAR `88537f56473dc65e619f08b388e252656b0ecd90883f7bb1d8f2fa4cbd5fb0df`. The lint gate passed after correcting one test import-order violation. The final regtest APK passed all 22 targeted device tests in 25.936 seconds. Its SHA-256 is `c6a5e60140ef7ccc6dda2b61647a49608ddb45c10eeb52e6e3657e5cdb06e9aa`. Focused checks and manual verification cover receive history, the compact invoice editor, reconnect, disconnect and invoice expiry. The native suite passed 230 tests, the focused vendored channel suite and serialization regression passed 24 tests, and Beignet passed 226 focused tests. Native Clippy remains blocked by seven existing vendored Lightning lint failures on Rust 1.99, as documented in the native checkout.

Native funded regtest validation passed all 19 checks in each configuration: LDK provider with RPC client, patched Beignet provider with RPC client, and patched Beignet provider with Electrum client. Checks include quote and fee-ceiling refusal, a 20,000 sat payment with a 120 sat fee, a 1,000 sat Lightning send, a 40,000 sat splice-in, exact 5,000 sat external splice-out, mempool and confirmed status, unknown-status rejection, a 100,000 sat receive after restarting a wallet whose original channel held 48,200 sats, a subsequent 60,000 sat Lightning send, pending-channel quote refusal, older-channel oversized receive refusal, provider policy after restart, and channel-ID replay refusal. Sanitized evidence is in [primary-wallet-client.json](../../ldk-node/docs/qualification/primary-wallet-client.json).

Manual emulator checks have covered parts of J1 through J7, including pairing, the single balance, confirmed automatic funding, force-close cancellation and outgoing Lightning/onchain payments. A quoted 20,000 sat receive with a 120 sat fee settled for 19,880 sats, increased the balance from 48,444 to 68,324 sats and cleared the paid QR. The resulting activity was verified once with a net credit of 19,880 sats and an actual fee of 120 sats. Offline quote retry succeeded after restarting the same provider, and passive disconnect removed the approved Lightning invoice within the 34-second observation interval. An unpaid 40,000 sat request also cleared its Lightning invoice at its encoded ten-minute expiry while retaining the Bitcoin address and amount. This is not a complete journey-suite pass. Initial receive and external-confirmation attempts exposed issues subsequently fixed and verified on later candidates. That earlier final artifact was installed during the historical qualification. A new 66,511 sat primary channel grew to 186,511 sats and received a 100,000 sat invoice. Android credited exactly 99,800 sats, displayed the 200 sat fee once, cleared the paid Lightning request and retained the receipt after restart. A subsequent 70,000 sat Lightning send, also above the original channel capacity, settled with zero fee. The provider received exactly 70,000 sats and Home fell from 167,455 to 97,455 sats. Both payments appear once in history. Explicit closure returned funds onchain without reopening, and the funding pause survived process restart. An unreachable replacement also retained the original primary and its pause; explicit verification of the original primary resumed funding and opened the new channel. The local [device record](../.ai/primary-node-qa/results.md) tracks exact builds, transactions, fixes and remaining checks. The [final growth record](../.ai/primary-node-qa/final-growth-results.md) covers the final APK, closure migration, larger receive and larger send. Hardware, settlement through the onchain fallback of a quoted request, exact unified-request source selection and the duplicate-send pending window remain unverified on device until that record states otherwise.

The native suite also covers underpaid invoice rejection and durable caller-selected channel IDs. IDs are written before opening and cannot be replayed after restart, failure or closure. Android terminal-event processing persists its journal before native acknowledgement and only uses queue movement as recovery evidence after the native node has reloaded.

The October 26 pairing correction removes the requirement that a primary accept a 100,000 sat quote before it can be saved. A dedicated read-only protocol probe accepts a well-formed refusal from the authenticated primary. Actual receive quotes and fee approval remain strict. The corrected native library passed 233 tests, and the Android compile, 4,493 unit tests, detekt and lint gates passed against AAR `2fe81c4d596f70b3f9e79fc6929df86faa8757409a9e629182bdab5f2848976a`. Nine focused regtest checks covered unfunded LDK and Beignet providers, unchanged real-quote refusal, reconnect after client restart, empty wallet state and rejection of a provider without protocol support. These checks did not fund wallets or mine blocks. The full funded matrix and manual Android journeys above describe the earlier qualification; they were not repeated for this pairing correction.


The October 26 receive follow-up adds typed native errors and localized Android guidance for insufficient primary funds, declined requests, timeouts and amounts below the receive fee. The primary setup banner now follows the saved URI and stays hidden when the primary is offline or unfunded. Native tests passed 236 cases. Android compilation, 4,501 unit tests across 252 suites, rerun detekt and Android lint passed against AAR `740e7a4c3de7233198d6f41fd70554737a2f2fa88d39e92c47ff15db3ab95888`.

Version 197 includes a separate Bitkit Primary Regtest APK, application ID `to.bitkit.primary.regtest`, signed with `app/debug.keystore`. It installs alongside Bitkit Primary (`to.bitkit.primary`) and the usual dev app. This specific APK uses Mint Electrum at `tcp://192.168.50.211:60401`, so the device needs access to that LAN and a compatible primary on the same regtest chain. Pair the regtest wallet's node URI. The app retains staging authentication and backup services. The custom package has placeholder Firebase configuration and no Firebase push. The artifact directory contains the application-ID init script, build information and checksums. A matching mainnet version 197 is also available locally with E2E disabled.

Focused testing of the exact regtest arm64 APK `3ec6bdddc0fea92f06d6d75bbd60e2580702a93d6a1dcc88adcc92afccb6cf1e` passed on a compact emulator with the existing `ldk-node-umbrel:0.1.7-arm64` image and the user's Mint regtest API. Image QR pairing succeeded with an unfunded provider. The setup banner disappeared and stayed hidden after restart and while offline. A 20,000 sat receive first showed the new insufficient-funds explanation; funding the primary and retrying produced a new 120 sat quote requiring explicit approval. Payment credited exactly 19,880 sats once and cleared the paid Lightning request. A subsequent 1,000 sat send settled with a 1 sat routing fee, leaving 18,879 sats. Balances and both history entries survived restart. The [version 197 device record](../.ai/primary-regtest-197/results.md) contains commands, evidence, fixture identities and remaining coverage. These focused checks do not replace the full historical journey matrix or constitute a physical-phone or mainnet payment test.


The October 26 feature-bit compatibility update rebuilds the native library with the Beignet 440-445 allocation and packages version 198 of both custom apps. The Android gates passed `just compile`, `just test` (4,501 tests across 252 suites, no failures, errors or skips) and `just lint`. Both arm64 and universal APKs passed package identity, network flags read from packaged DEX, debug signature and 16 KB alignment verification. Each APK's native library matches the frozen AAR byte for byte. The regtest arm64 APK upgraded version 197 on the emulator, reached Home and retained the existing balance and payment history without a fatal exception or native load error. No new payments or full funded journeys were run for this update. Mainnet was verified as an artifact and was not launched with funds. Exact paths, hashes, commands and source working-tree fingerprints are in [the version 198 qualification record](qualification/ffor-feature-bits-198.json).


The version 199 rebuild includes the native splice-out reserve correction. Splice-out no longer uses the HTLC outbound-capacity limit, which excludes the old channel reserve. The channel manager still validates the amount, fees, pending HTLCs and new reserve atomically, and zero amounts remain rejected. The fixed native source passed 236 unit tests, 169 integration tests including all eight splice-out regressions, 13 doctests and the payment benchmark before packaging. Source fingerprints remained unchanged during the Android native build, and independent binary inspection confirmed the old precheck was absent in all three Android ABIs.

Android compilation and lint passed with the new AAR. The initial unit-test task was up to date, so the task was explicitly rerun: all 4,501 tests across 252 suites passed with no failures, errors or skips. Mainnet and regtest arm64/universal APKs passed package, network flag, signature, exact ABI set, 16 KB alignment and native-library identity verification. The regtest arm64 APK upgraded version 198 in place and retained the existing balance and payment history without crash or native load errors. No new payment was sent on the emulator, and mainnet runtime and full funded Android journey tests were not performed. Exact build commands, source fingerprints, native evidence and artifact hashes are in [the version 199 qualification record](qualification/splice-out-199.json).


## Direct funding requests

The new working-tree implementation appends Beignet v3 `bgnq` to the Bitcoin/AUTO BIP21 request
when the configured primary is connected. It preserves the Bitcoin address, amount, message and
available Lightning fallback. The Lightning tab continues to expose only the BOLT11 invoice.
Direct requests have a ten-minute lifetime and require at least 5,000 sats when a fixed amount is
set. The receiver can use a new channel or splice an existing primary channel. Shared requests
remain usable when the receive sheet closes. Changing receive details replaces the advertised
request; primary changes, disconnects and matching fallback settlement revoke its native request.
Fallback associations are stored locally so settlement is handled after leaving the receive sheet.
Expiry and committed native payments stop advertising the request. The independent BIP21 rails
cannot settle atomically; a sender must not attempt multiple rails for the same request.

The sender authenticates the signed envelope and checks network, expiry, receiver and fixed amount
before allowing payment. Malformed bgnq is rejected instead of being discarded. Direct channel
funding uses one confirmed software-wallet coin and a reviewed maximum network fee of 1,000 sats.
The native signer validates the offered funding transaction before releasing a witness. Existing
channel balance cannot fund this sender path. Hardware-wallet signing and contact, purchase,
subscription or Paykit payment-proof contexts remain unsupported for bgnq requests.

For a bgnq request, source selection explicitly distinguishes Lightning from Direct channel funding.
A committed native attempt blocks another payment of the request, including a switch to Lightning,
and survives restart. Native signed-pending, mempool and confirmed records update one stable
activity identity. Received amounts appear as Incoming channel credit, separate from own-wallet
funding transfers. These jointly funded transactions do not offer ordinary wallet fee boosting.
The actual fee replaces the confirmation ceiling in activity when native transaction details exist.

The new QA cases are J8 (`journeys/primary-node/receive-direct-funding.xml`) and
J9 (`journeys/primary-node/send-direct-funding.xml`). Their fixture prerequisites are in that suite’s
README. Previous qualification records and APK versions above predate this functionality and do
not establish its validation. New native and Android results must be recorded separately.

The initial direct-funding Android qualification passed compilation, all 4,519 unit tests across 255 suites,
full rerun detekt, and Android lint with no findings. Twenty targeted receive/send Compose tests
passed on a fresh compact API 36 emulator (720 by 1280 pixels at 320 dpi). The two initial receive
snapshot-race fixture failures were corrected and the entire unit suite was rerun successfully.
The final AAR SHA-256 is `f2c95398bed9ac387f548bddc8b4d27edcd908541a527734d1d05e892122690c`.
That initial arm64 QA APK SHA-256 was `f3cf249c87e0e6746ee483e868d9c0639e510f6c9de4e8766b6ecabf7ac6359e`;
its native library matches that AAR exactly, and APK signature and 16 KB alignment checks passed.

This is a disposable `to.bitkit.dev` regtest build at version code 195, with E2E local mode and
Electrum `tcp://10.0.2.2:60412`. It was installed only on the new `emulator-5556`; the existing
emulator and user wallet were not modified. It is not a new mainnet or custom Bitkit Primary release.
The [Android qualification record](qualification/direct-funding-android.json) contains exact commands,
artifact identities, results and resolved initial failures. Actual funded J8/J9 Android results are summarized below with their artifact scopes; the passing
Compose checks do not claim real payment interoperability.

Actual funded Android testing then exposed a persistence bug missed by the mocked storage tests:
the direct-funding receipt used an empty address, which the Core SQLite schema rejects. The transfer
itself completed, but history showed an ordinary debit instead. Receipts now store the stable
`direct-funding:<requestId>` protocol target rather than an empty or invented Bitcoin address.
A new test passes against the actual packaged Core JNI/SQLite database, including pending receipts,
confirmation, repeated writes, database reopening and incoming credit. The complete Android gate
was rerun: compilation, all 4,520 unit tests across 255 suites, detekt and Android lint passed.

The storage-repair arm64 QA APK SHA-256 was
`7d48765018b6762fa9f6d4ed4a73ca8c8d12fa41d4ec85b3a3829b7230857843`. Its native library is unchanged
and still matches the AAR above. The disposable wallet was upgraded in place without clearing it or
sending again. Its original payment recovered as exactly one confirmed Direct channel funding
activity with 20,000 sats paid and a 155-sat fee; the ordinary duplicate disappeared and fee boosting
remained unavailable. The earlier 20 Compose tests predate this storage fix and were not rerun on
the funded wallet. The qualification record preserves both candidates and their evidence separately.

The documented LDK-provider device run funded a fresh native receiver from Android for 20,000 sats
with a 155-sat fee on the initial APK; the storage-repair APK recovered that same payment without
resending. On the storage-repair APK, a native sender then credited Android with 20,000 sats into a
new channel and another 20,000 sats by splicing that same channel from 20,000 to 40,000 sats.
Payer fees were 155 and 252 sats. Both confirmed incoming credits survived Android restart; native
sender restart and replay returned the same transaction without another payment. History contained
no own-wallet funding debit for these external credits.

Receive lifecycle checks passed for signed amount changes, disconnect fallback, reconnect, automatic
ten-minute expiry without touching the open QR, complete combined Bitcoin/Lightning/bgnq copying,
and Lightning-only copying. The 794-character combined clipboard matched the QR; Lightning-only
copying matched its 343-character BOLT invoice. Opening the system clipboard editor backgrounded
Bitkit and retired that composite under the existing disconnect policy, so fallback settlement was
tested on a separate fresh request. With Receive closed, its 20,000-sat Bitcoin fallback arrived in
the mempool and retired the direct request. A native direct attempt was declined before Receive
reopened and again afterward, with no payer witness, direct funding transaction or fee. The fallback
transaction itself was not mined. Detailed transactions, request IDs and timing are preserved in the
qualification record and [device narrative](../.ai/direct-funding-20261027/results.md).

A final review corrected the sender's failed-status handling: native outbound Failed records have
already committed a witness, so they continue to block another rail for the same request. Failed
inbound negotiations remain distinct and do not create a committed credit. The concrete unified-URI
rescan and stale Lightning-confirmation regression passes without either payer API being called.
Final compilation, all 4,523 unit tests across 255 suites, Android lint and full detekt passed. The final
arm64 APK SHA-256 is `639c778fe7025beaed0c3cb6ccffbfaa7041a3624fee731a361b8469d8a960f8`; its native
library remains identical to the qualified AAR. Funded execution evidence retains the earlier APK
hashes. The final build passed an in-place upgrade and restart of the same disposable wallet:
two incoming channel credits and the sole outgoing direct-funding receipt remained, with the
confirmed 20,000-sat payment and 155-sat fee unchanged. Native payment counts were unchanged
and no new payment was sent. This final history check is separate from the earlier funded runs.

These are individual checks, not an overall J8/J9 pass. Unrun device variants are Android paying into
an existing receiver channel, a separate Beignet-provider UI repeat, unresolved signed-pending
result reentry, an exactly zero-onchain-coin sender, and unexpired committed-result navigation.
The insufficient-onchain case did pass despite available channel balance. Reentry after restart
occurred after the original request expired; it does not establish the unresolved-pending case.
Lightning fallback settlement was not exercised. Native interoperability, unit tests and the earlier
Compose checks remain separately scoped supporting evidence.

After verification, the owned native fixture drivers, Electrum container and disposable emulator were
stopped; its temporary port reverse was removed. The existing user emulator was never operated.
The disposable Bitcoin Core container was also stopped and verified absent from the
running-container list. Local evidence and temporary fixture data were retained for recovery. The native qualification and
published provider images do not imply a mainnet payment or a user Umbrel installation.

## Custom mainnet and regtest APKs, build 200

The October 27 direct-funding update is packaged as Bitkit Primary mainnet (`to.bitkit.primary`) and Bitkit Primary Regtest (`to.bitkit.primary.regtest`), version code 200. Both retain the previous custom debug signing key and include the qualified direct-funding AAR. ARM64 and universal APKs were built and checked for package identity, packaged network flags, signature, 16 KB alignment and exact native-library bytes. Mainnet has E2E disabled; regtest retains Mint Electrum at `tcp://192.168.50.211:60401` and staging Homegate.

These builds were not installed or used for new payments. The earlier funded device tests remain scoped to their recorded APKs; the final QA APK was archived before rebuilding. Exact artifact paths, hashes and build commands are in [the build 200 qualification record](qualification/direct-funding-200.json).

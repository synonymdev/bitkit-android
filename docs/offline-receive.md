# Offline receive integration draft

This change prepares the receive flow for FFOR and stays hidden in production. `GatedOfflineReceiveService` is the production binding of `OfflineReceiveService`. It behaves as `UnavailableOfflineReceiveService` (no eligible amounts, preparation rejected) unless both hold: the app was compiled with the `ldkNodeLocalVersion` Gradle property against an ldk-node build that carries the offline receive API, and the device-local dev toggle **Offline receive (experimental)** is on. CI and release builds compile without the property, so the native adapter is absent and the toggle is not rendered. Neither an ordinary BOLT 11 invoice nor a CJIT invoice is represented as an offline invoice, and no invoice is stored or displayed unless the library reported it as `Ready`.

The receive editor offers **Receive Offline** on Auto and Spending only after the provider accepts the exact positive amount and the amount fits current inbound liquidity. Savings, hardware receive, amountless invoices, unsupported peers, and unavailable initial capability checks keep the option hidden. Selection resets when the amount changes. A same-amount capability refresh preserves selected offline intent and blocks confirmation while unresolved. If support becomes unavailable, the user must explicitly deselect offline receive to request an ordinary invoice. Preparation rechecks eligibility for a new operation, and the editor returns to the QR only after successful preparation. Errors stay in the editor. Superseded asynchronous responses cannot enable the option or publish an obsolete invoice.

A prepared invoice belongs to the current receive session. Its amount, description, and invoice take precedence over ordinary wallet refreshes, and it remains displayable after the local node stops. Reopening an unchanged, unexpired invoice reuses the prepared invoice. Expiry is checked when the screen resumes and every foreground second; it removes the Lightning QR and copy surface and asks the user to edit the invoice. An ordinary invoice is never substituted when offline preparation fails or a prepared invoice expires. Starting a new receive session resets the selection.

Before QR exposure, the app saves the selected tags as pre-activity metadata for the prepared payment hash. The existing confirmed `PaymentReceived` path consumes that metadata when recording income, invalidates the matching prepared session, and closes its receive sheet. Unrelated payments, invoice preparation, and claimable but unpaid events do not settle the session. The repository subscribes before preparation starts and checks native inbound payment history before registering a returned invoice, including retries whose payment event arrived before the reply. A concurrent confirmed event forces a fresh history check. Session memory holds only the current prepared invoice and metadata snapshot, and clears on dismissal or wallet reset.

## Required native adapter

`OfflineReceiveService` is an app boundary, not an assertion that these APIs exist in LDK. Before replacing the unavailable binding, the adapter must provide:

- Exact amount eligibility using a compatible channel and settlement peer, including voucher slot limits, fees, dust, claim safety margins, and reserved capacity.
- Online preparation that waits for signed durable activation of the voucher and stores recovery material before returning `PreparedOfflineInvoice`.
- A valid invoice for the requested amount and description, with the actual invoice expiry supplied as an absolute timestamp in milliseconds. The app parses BOLT 11 with signature validation and checks network, exact millisatoshis, payment hash, description, and expiry against the prepared metadata before showing a QR.
- Durable idempotency for the supplied request ID. Retries of unchanged invoice parameters retain that ID and must recover an existing activation or safely resume it. The app permits reconciliation retries after a reservation reduces available liquidity; fresh operations still require an eligibility check.
- Durable recovery after app termination or caller cancellation, including reservations created by a request whose UI has since been dismissed or changed.
- Settlement reconciliation and ordinary confirmed payment events for the prepared payment hash, with native inbound payment history available for retry checks and idempotent activity accounting.
- Backup and restore behavior for claim material, recovery deadlines, and invoice metadata. The in-memory UI session is not a recovery store.

The current draft does not restore a prepared invoice into a new receive session or provide a claim recovery screen. These are activation prerequisites alongside the native implementation. Production support must remain unavailable until those paths and cross-implementation offline settlement are verified.

The native and settlement work is tracked in [ldk-node #117](https://github.com/synonymdev/ldk-node/issues/117). A provider fake verifies UI and repository behavior; it is not evidence of Lightning settlement. The committed native dependency stays at the catalog version; the offline receive build is opt-in (see below).

## Native adapter

The ldk-node bindings expose `Builder.setOfflineReceiveConfig(OfflineReceiveConfig)` and `Node.offlineReceive()`, a handler with `canReceive(amountMsat)`, `prepare(requestId, amountMsat, description)`, `status(requestId)` and `cancel(requestId)`. Status is `Preparing`, `AwaitingActivation`, `AwaitingWitnesses`, `Ready(bolt11)`, `Expired`, `Settled(outcome)` or `Failed(reason)`. Calls may throw `NodeException.OfflineReceiveDisabled`, `OfflineReceiveUnavailable`, `OfflineReceiveIneligible`, `OfflineReceiveRequestNotFound`, `OfflineReceiveRequestConflict` or `NotRunning`. `prepare` is idempotent for identical arguments and refuses different arguments under a known id. After a restart the library reports a request it still tracks, but `Ready` carries the invoice only after it was released in the current process; the adapter therefore calls `prepare` again with the persisted id and arguments to resume, and never invents an invoice.

Layout:

- `app/src/main/java/to/bitkit/services/offline/`: compiled always, against the pinned release.
  - `OfflineReceiveClient` is the app's view of the handler (millisatoshis, `OfflineReceiveClientStatus`, `OfflineReceiveClientException.Kind`), so the provider is unit tested without the native library.
  - `LdkOfflineReceiveService` is the real provider. `canReceive(sats)` calls `canReceive(sats * 1000)` and answers false on `DISABLED`, `UNAVAILABLE`, `INELIGIBLE` and `NOT_RUNNING`. `prepareInvoice` resolves the durable request identity, calls `status` and, unless it is already `Ready`, `prepare`, then polls `status` every 500 ms until `Ready` within `prepareTimeoutMillis` (default 60 s). `Expired`, `Settled` and `Failed` fail with `OfflineReceiveUnavailable` and clear the identity; a timeout or caller cancellation fails but keeps the identity so a retry resumes the same request. A `Ready` invoice is parsed with `OfflineReceiveInvoiceParser` and must match the network, exact millisatoshis, description and the local node id as payee, with the absolute expiry derived from the invoice; a mismatch cancels the request and fails.
  - `OfflineReceiveRequestStore` (`PreferencesOfflineReceiveRequestStore`, DataStore file `offline_receive`) persists the active request id with its amount and description. A later request for the same amount and description reuses that id even though the UI generated a new one, which is how the same request is resumed after process death instead of preparing a second one. A different intent cancels the persisted request first. `WipeWalletUseCase` clears the store.
  - `OfflineReceiveSettingsSource` derives `OfflineReceiveSettings` from `SettingsStore.offlineReceiveDevSettings` (device-local Preferences, never backed up): enabled flag (default off, and forced off unless the native adapter was compiled in), settlement node id (default: the first trusted Blocktank LSP peer of the current network from `Env.trustedLnPeers`, override in dev settings), witness node ids (default none) and the preparation timeout.
  - `GatedOfflineReceiveService` picks the native provider only when the `@NativeOfflineReceive` set multibinding has one and the toggle is on. `NodeBuilderCustomizer` is a set multibinding that `LightningService` applies to the ldk-node `Builder` before `setEntropyBip39Mnemonic`.
- `app/src/offlineReceive/java/`: compiled only with `ldkNodeLocalVersion`.
  - `LdkOfflineReceiveClient` adapts `OfflineReceivePaymentInterface` to `OfflineReceiveClient` and maps statuses and exceptions.
  - `LdkOfflineReceiveNodeCustomizer` calls `setOfflineReceiveConfig` when the toggle is on and a settlement node id is known, with development defaults: invoice expiry 3600 s, safety margin 120 s, settlement deadline 144 blocks, deadline margin 6, claim margin 20, voucher expiry 288, witness retention 288 with minimum receipts 0, fees 0/0, poll 5 s.
  - `OfflineReceiveNativeModule` contributes the customizer, the client provider and the native service.
- `app/src/offlineReceiveTest/java/`: `LdkOfflineReceiveClientTest` covers the status and exception mapping with a fake `OfflineReceivePaymentInterface`.

The dev toggle lives in Settings > Dev settings under **OFFLINE RECEIVE (EXPERIMENTAL)** (`OfflineReceiveToggle`), next to fields for the settlement node id override and comma separated witness node ids. The node reads the configuration when it is built, so restart the app after changing it; until then `canReceive` answers false because the library reports `OfflineReceiveDisabled`.

### Building against a local ldk-node

Publish the ldk-node Android bindings to `~/.m2` (for example as `com.synonym:ldk-node-android:0.7.0-ffor.1`; `mavenLocal()` is already a repository in `settings.gradle.kts`), then either uncomment `ldkNodeLocalVersion` in `gradle.properties` or pass it on the command line:

```sh
./gradlew -PldkNodeLocalVersion=0.7.0-ffor.1 :app:compileDevDebugKotlin
./gradlew -PldkNodeLocalVersion=0.7.0-ffor.1 :app:testDevDebugUnitTest
./gradlew -PldkNodeLocalVersion=0.7.0-ffor.1 installDevDebug
```

The property overrides the `ldk-node-android` version through a resolution strategy (the catalog stays at the release version, and the native debug symbols artifact follows the override), adds `src/offlineReceive` and `src/offlineReceiveTest` to the source sets, and sets `BuildConfig.FEATURE_OFFLINE_RECEIVE_NATIVE`. Without it the default build is unchanged and the feature stays unavailable.

### Pending

- End-to-end validation on a device against a settlement peer and witnesses with the offline receive protocol: not yet performed. Nothing in this repository proves a voucher was activated or settled.
- Resuming a `Ready` invoice after process death depends on the library releasing the invoice again for the resumed request; only the request identity reuse is verified here.
- Claim recovery screen, backup and restore of claim material, and restoring a prepared invoice into a new receive session remain open, as listed above.

## Design

The existing screen map identifies Edit Invoice as `Send (Contact) (Lightning) > Edit invoice populated`. The checkbox and expiry state are new behavior without an available design. The shared Android and iOS checkbox identifier is `ReceiveOffline`.

## Verification

`LdkOfflineReceiveServiceTest.kt` covers the ready path, the bounded timeout, `Failed`, `Expired` and `Settled` mapping, request identity persistence and reuse after a simulated restart (ready recovery and pending resume), superseding a different intent, caller cancellation, invoice validation against the request and payee, and the disabled toggle. `GatedOfflineReceiveServiceTest.kt` covers the production gate and `OfflineReceiveSettingsSourceTest.kt` the defaults and overrides. `OfflineReceiveRepoTest.kt` covers exact amount and liquidity limits, stopped nodes, unavailable providers, preparation revalidation, invalid responses, metadata persistence, confirmed settlement, unpaid events, wallet reset, and payment-before-registration races. `EditInvoiceVMTest.kt` covers selection reset, stale asynchronous replies, awaited preparation, blocked reuse of settled invoices, and failure without ordinary invoice fallback. `ReceiveInvoiceUtilsTest.kt` covers QR behavior after node shutdown and protection from ordinary wallet refreshes. `EditInvoiceContentTest.kt` covers the checkbox callback.

# Offline receive integration draft

This change prepares the receive flow for FFOR and remains disabled with the current native dependency. `UnavailableOfflineReceiveService` is the production binding. It reports no eligible amounts and rejects preparation. Neither an ordinary BOLT 11 invoice nor a CJIT invoice is represented as an offline invoice.

The receive editor offers **Receive Offline** on Auto and Spending only after the provider accepts the exact positive amount and the amount fits current inbound liquidity. Savings, hardware receive, amountless invoices, unsupported peers, and unavailable initial capability checks keep the option hidden. Selection resets when the amount changes. A same-amount capability refresh preserves selected offline intent and blocks confirmation while unresolved. If support becomes unavailable, the user must explicitly deselect offline receive to request an ordinary invoice. Preparation rechecks eligibility for a new operation, and the editor returns to the QR only after successful preparation. Errors stay in the editor. Superseded asynchronous responses cannot enable the option or publish an obsolete invoice.

A prepared invoice belongs to the current receive session. Its amount, description, and invoice take precedence over ordinary wallet refreshes, and it remains displayable after the local node stops. Reopening an unchanged, unexpired invoice reuses the prepared invoice. Expiry is checked when the screen resumes and every foreground second; it removes the Lightning QR and copy surface and asks the user to edit the invoice. An ordinary invoice is never substituted when offline preparation fails or a prepared invoice expires. Starting a new receive session resets the selection.

## Required native adapter

`OfflineReceiveService` is an app boundary, not an assertion that these APIs exist in LDK. Before replacing the unavailable binding, the adapter must provide:

- Exact amount eligibility using a compatible channel and settlement peer, including voucher slot limits, fees, dust, claim safety margins, and reserved capacity.
- Online preparation that waits for signed durable activation of the voucher and stores recovery material before returning `PreparedOfflineInvoice`.
- A valid invoice for the requested amount and description, with the actual invoice expiry supplied as an absolute timestamp in milliseconds. The app parses BOLT 11 with signature validation and checks network, exact millisatoshis, payment hash, description, and expiry against the prepared metadata before showing a QR.
- Durable idempotency for the supplied request ID. Retries of unchanged invoice parameters retain that ID and must recover an existing activation or safely resume it. The app permits reconciliation retries after a reservation reduces available liquidity; fresh operations still require an eligibility check.
- Durable recovery after app termination or caller cancellation, including reservations created by a request whose UI has since been dismissed or changed.
- Settlement reconciliation and ordinary payment events for the prepared payment hash, with idempotent activity accounting and removal of settled invoices from the receive session.
- Backup and restore behavior for claim material, recovery deadlines, and invoice metadata. The in-memory UI session is not a recovery store.

The current draft does not connect payment notifications or tag metadata to a prepared invoice, restore a prepared invoice into a new receive session, or provide a claim recovery screen. These are activation prerequisites alongside the native implementation. Production support must remain unavailable until those paths and cross-implementation offline settlement are verified.

The native and settlement work is tracked in [ldk-node #117](https://github.com/synonymdev/ldk-node/issues/117). A provider fake verifies UI and repository behavior; it is not evidence of Lightning settlement. No native dependency version is changed by this draft.

## Design

The existing screen map identifies Edit Invoice as `Send (Contact) (Lightning) > Edit invoice populated`. The checkbox and expiry state are new behavior without an available design. The shared Android and iOS checkbox identifier is `ReceiveOffline`.

## Verification

`OfflineReceiveRepoTest.kt` covers exact amount and liquidity limits, stopped nodes, unavailable providers, preparation revalidation, and invalid responses. `EditInvoiceVMTest.kt` covers selection reset, stale asynchronous replies, awaited preparation, and failure without ordinary invoice fallback. `ReceiveInvoiceUtilsTest.kt` covers QR behavior after node shutdown and protection from ordinary wallet refreshes. `EditInvoiceContentTest.kt` covers the checkbox callback.

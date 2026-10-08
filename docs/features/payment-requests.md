# Payment Requests (Paykit)

Send and receive one-time Paykit Payment Requests between linked Pubky contacts, pay public Paykit endpoints, and complete the Pubky marketplace purchase leg; recurring requests are in `subscriptions.md`.

## What it does

- A user with a Pubky profile creates a Payment Request (amount, optional note, expiry) for a saved, Paykit-linked contact. The request travels over Paykit's private transport.
- The payer sees incoming requests as a bell on Home, a sheet, a list (Payments tab of Subscriptions) and a detail screen, and pays through the normal Send confirmation sheet.
- Paying opens the Send flow with saved sender, amount and note; the payment control stays disabled until fresh endpoint resolution and wallet validation finish.
- After payment Bitkit submits a payment proof (`bitcoin-onchain-txid` or `bitcoin-bolt11-preimage`) to the issuer.
- Public Paykit: `ContactPay` on a saved contact pays an endpoint the contact published; Paykit-linked contacts get a Request or Pay choice sheet.
- Pubky marketplace leg: a seller wallet approves a Paykit and watch-only account auth request, a linked buyer wallet receives the marketplace request and pays it.
- Issuer contract: `docs/paykit-issuer-interoperability.md` (asset `btc`, one-time, endpoint identifiers per network, endpoint payload `{"value": ...}`); accepted and rejected fixtures in `app/src/test/resources/paykit-issuer-interoperability.json`.
- Rejection and retry rules (parse, resolution, presentation reasons; 15 explicit attempts; automatic retry every 120 s): `docs/payment-requests.md`.
- The whole feature is hidden unless `PaykitFeatureFlags.isUiEnabled` is true: build flag `PAYKIT_UI_DISABLED` is not set and the local setting is on (default on, `SettingsStore.isPaykitEnabled`).

## How a user reaches it

- Enable: Settings > Advanced > Dev Settings (`DevSettings`, listed only while dev mode is on) > "Enable Paykit UI" (`PaykitUiToggle`), shown only when the build allows Paykit UI; the setting defaults to on. Contact payments: Settings, Payments section, `ContactPaymentsToggle` (shown only with Paykit on and a Pubky profile; see `profile.md`).
- Create a request: Home > Receive (`Receive`) > `ReceivePaymentRequestContacts` (only when at least one eligible contact exists) > `PaymentRequestRecipient` (rows `PaymentRequestContact<pubky>`) > `PaymentRequestAmount` (`PaymentRequestAmountField`, `PaymentRequestAmountContinue`) > `PaymentRequestDetails` (`PaymentRequestNote`, `PaymentRequestExpiry<option>`, `PaymentRequestSend`) > `PaymentRequestSent`.
- Create from a contact: Contacts > `Contact_<pubky>` > `ContactPay` > `RequestOrPaySheet` (buttons have no tag, find by text) > Request opens `PaymentRequestAmount`.
- Incoming, automatic: a new request opens the Send confirmation (`PaymentRequestConfirm`) in the foreground after the next poll.
- Incoming, manual: Home bell `PaymentRequestsBell` (shown only while requests are pending) > `PaymentRequestsSheet` > `PaymentRequestsSeeAll` > Subscriptions, Payments tab (`Tab-payments`, `PaymentRequestsScreen`) > row `PaymentRequestRow-<id>` > `PaymentRequestDetailsScreen`, or `PaymentRequestPay-<id>` / `PaymentRequestDetailsPay`.
- Drawer menu > `DrawerSubscriptions` > Subscriptions screen (`SubscriptionsScreen`) > `Tab-payments`.
- Routes `Subscriptions`, `SubscriptionDetail` and `PaymentRequestDetails` are `InternalOnly` in `app/src/main/java/to/bitkit/ui/ContentView.kt`: no deeplink. `bitkit://contact?pubky=<key>` opens Contact Detail (see `deeplinks.md`).
- Marketplace: a `pubkyauth://` URL scanned or pasted in the main scanner opens `PubkyAuthApprovalSheet` (see `profile.md`).

## Code

- `app/src/main/java/to/bitkit/ui/screens/paymentrequests/CreatePaymentRequestScreen.kt`: amount, details, recipient and sent screens, shown inside the Receive sheet (`ui/screens/wallets/receive/ReceiveSheet.kt`).
- `app/src/main/java/to/bitkit/ui/screens/paymentrequests/PaymentRequestsScreen.kt`: `PaymentRequestsSheet` (bell sheet), `PaymentRequestsScreen` (Payments tab body), `PaymentRequestCard` (Dismiss and Pay).
- `app/src/main/java/to/bitkit/ui/screens/paymentrequests/IncomingPaymentRequestDetailsScreen.kt`: detail screen, status, pay, tags.
- `app/src/main/java/to/bitkit/repositories/PaykitPaymentRequestRepo.kt`: request lists, parse gate, `propose`, `accept`, `reject`, `dismiss`, refresh, presentation state.
- `app/src/main/java/to/bitkit/repositories/PaykitPaymentRequestPresentationStore.kt`: persisted presented/accepted ids per identity.
- `app/src/main/java/to/bitkit/repositories/PaykitPaymentProofRepo.kt`, `PaykitPaymentProofStore.kt`: proof preparation, completion, failure; included in wallet backups via `models/PaykitPaymentStateBackup.kt`.
- `app/src/main/java/to/bitkit/repositories/PaykitIssuerInterop.kt`: endpoint identifiers and payload parsing. `PaykitReceivedPaymentContacts.kt`: maps received payments to contacts.
- `app/src/main/java/to/bitkit/repositories/PublicPaykitRepo.kt`: publishes own public endpoints, resolves and begins public contact payments.
- `app/src/main/java/to/bitkit/repositories/PrivatePaykitRepo.kt`, `PrivatePaykitAddressReservationRepo.kt`, `PrivatePaykitContactResolver.kt`, `PrivatePaykitErrorClassifier.kt`, `PrivatePaykitModels.kt`: private links and endpoints per saved contact, reserved receive addresses; stores in `data/PrivatePaykitStores.kt`.
- `app/src/main/java/to/bitkit/repositories/ContactPaymentSettingsRepo.kt`: contact payments on/off and endpoint reconcile. `usecases/RefreshContactPaykitLinkUseCase.kt`: link refresh.
- `app/src/main/java/to/bitkit/services/PaykitSdkService.kt`: wraps the Paykit SDK. `PaykitSdkOperationLock.kt` serializes SDK calls.
- `app/src/main/java/to/bitkit/viewmodels/AppViewModel.kt`: polling (`startPaykitPaymentRequestPolling`), automatic presentation, `showPaymentRequests`, `openIncomingPaymentRequest`, `createPaymentRequest`.
- `app/src/main/java/to/bitkit/ui/screens/wallets/send/SendConfirmScreen.kt`: request summary (`PaymentRequestFrom`, `PaymentRequestFor`, `PaymentRequestInvoiceNote`). `ui/screens/contacts/ContactDetailScreen.kt` and `ContactDetailViewModel.kt`: `ContactPay`, `RequestOrPaySheet`.
- `app/src/main/java/to/bitkit/ui/screens/profile/PubkyAuthApprovalSheet.kt`, `PubkyAuthApprovalViewModel.kt`, `models/PubkyAuthClaimCodec.kt`, `models/PubkyAuthRequest.kt`: marketplace consent; `docs/pubky-auth-companion-claims.md` is the claim contract.
- `app/src/main/java/to/bitkit/flags/PaykitFeatureFlags.kt`: feature gate. `ui/screens/wallets/HomeScreen.kt`: bell.

## How to drive it

Backend: regtest. Two Bitkit instances (or one plus a fixture issuer) with Pubky identities, saved as each other's contacts and linked; payer funded (on-chain or spending). Setup details: `journeys/payment-requests/README.md`.

- `journeys/payment-requests/request-summary.xml`: collapsed confirmation shows From and For; Sent receipt subtitle follows protocol history.
- `journeys/payment-requests/automatic-presentation.xml`: request opens automatically in foreground, waits for another sheet, not reopened after review.
- `journeys/payment-requests/confirmation-controls.xml`: fixed amount and swipe footer at 360 dp and 1.3 font scale.
- `journeys/payment-requests/contact-request-or-pay.xml`: Request or Pay sheet from Contact Detail (open with the `bitkit://contact` deeplink).
- `journeys/payment-requests/issuer-interoperability.xml`: canonical regtest P2WPKH fixture from a `paykit-server` issuer opens confirmation.
- `journeys/payment-requests/definite-pre-broadcast-retry.xml`: failing LNURL callback, then retry pays the same request.
- `journeys/payment-requests/accepted-device-ownership.xml`: two installs, one identity; only the accepting install retries.
- `journeys/payment-requests/requested-resolution-failure.xml`: explicit Pay shows terminal toast after retries; request stays.
- `journeys/payment-requests/absolute-payment-deadline.xml`, `payment-deadline-history.xml`: absolute `At` deadlines and expired history.
- `journeys/payment-requests/delete-and-readd-contact.xml`, `delete-contact-with-active-subscription.xml`: contact deletion revokes private requests; active subscription blocks deletion.
- `journeys/pubky-marketplace/` (`README.md` has the fixture runtime and evidence table): `wallet-leg.xml` seller grant, buyer receives, pays, confirms; `paykit-only-approval.xml` and `paykit-reconnect.xml` consent and cancel checks.
- `journeys/paykit-clock-changes.md`: manual clock, timezone, connection-loss and reminder checks; needs a device clock that can change.
- E2E: `bitkit-e2e-tests/test/specs/paykit.e2e.ts` tags `@pubky @paykit @pubky_staging @staging`, test `@paykit_1`. Runs in the staging shard `pubky_paykit` (`@pubky_staging`, `.github/workflows/e2e-staging.yml`), real staging Pubky contacts `STAGING_PAYKIT_CONTACTS`. Covers only public on-chain contact payment: unsaved pubky routes to Add Contact, add contact, `ContactPay`, 10 000 sats on-chain, `SendSuccess`, contact activity shows "Sent to".
- Unit tests: `app/src/test/java/to/bitkit/repositories/Paykit*Test.kt`, `PaykitIssuerInteropTest.kt`.

## What proves it

- Payer: `PaymentRequestConfirm` with the requester and amount, swipe `GRAB`, `SendSuccess`; the request leaves `PaymentRequestsScreen` pending list; activity item and balance change.
- Requester: `PaymentRequestSent` after sending; open Sent receipt status; "Proof submitted" subtitle when no note.
- Marketplace: `SendSuccess`, buyer activity details, transaction at one or more confirmations in the fixture (the fixture's chain and purchase status are the authority, not `SendSuccess`).
- Consent screens: `PubkyAuthWatchOnlyConsent`, `PubkyAuthPaykitAccess`, `PubkyAuthAuthorize`, `PubkyAuthOK`, buttons `PubkyAuthWatchOnlyApprove`, `PubkyAuthWatchOnlyCancel`.

## Not covered by tests

- No e2e spec for creating, receiving, paying, dismissing or rejecting a Payment Request; only journeys, which need a second instance or fixture issuer.
- Lightning (`btc-lightning-bolt11`) issuer endpoint, expired BOLT 11 and non-LNURL values: only unit tests and the LNURL retry journey.
- Hardware wallet payment of a request: manual procedure in `journeys/payment-requests/README.md`, blocked without fault injection.
- Dismiss (`PaymentRequestDismiss-<id>`), detail Add Tag (`PaymentRequestAddTag`) and the Home entry `ReceivePaymentRequestContacts` appear in no journey or spec.
- Private Paykit endpoint refresh, address reservation rotation, backup and restore of accepted requests and proofs, `ContactPaymentsToggle` on/off.
- Marketplace live approval (41, 84 and 124 byte claim delivery) and periodic payout detection need the fixture runtime; consent and cancel journeys need valid auth URL fixtures.
- Public Paykit payment of a Lightning endpoint, and an unavailable public endpoint (`ableToPay` false) beyond the Add Contact route check.

## Gotchas

- Needs a Pubky profile and a link (Paykit Encrypted Link) with the contact; an unlinked contact never receives or sends requests. The request is not presented until Bitkit polls: foreground poll every 10 s, maintenance after 30 s then every 60 s.
- Right after launch, Paykit session restore and link refresh hold the SDK; `contact-request-or-pay.xml` waits about a minute before timing Pay.
- Closing the preparing Send sheet leaves the request pending and suppresses automatic reopening for the app session; use Pay to retry.
- Requests with an absolute payment deadline that passed, recurring requests, wrong-network or unknown identifiers and non-`btc` assets are filtered; see `docs/payment-requests.md`.
- `android layout` can omit tags on plain `Box` and `Column`; use the raw UI Automator hierarchy. `PaymentRequestRow-<id>` and `PaymentRequestPay-<id>` use the same names as iOS, but iOS appends the billing period to the row id.
- Do not change the device clock in the deadline journeys; use `journeys/paykit-clock-changes.md` on a disposable device.
- Android emulators reach host fixtures at `10.0.2.2`; the marketplace README lists the `adb reverse` ports (6286-6288, 15411, 15412).
- Unclear: how dev mode is switched on (not read); `PaymentRequestDismiss-<id>` is listed in the journeys README identifiers but no journey step uses it.

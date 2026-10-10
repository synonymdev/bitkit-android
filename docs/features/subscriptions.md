# Subscriptions (Paykit)

Create, review, pay, cancel and delete recurring Paykit Payment Requests between linked Pubky contacts, with a due-payment reminder notification on the payer.

## What it does

- A creator proposes a recurring request (name, description, icon, amount, frequency, expiration) to a saved, Paykit-linked contact. The creator's row appears in the CREATED section as "Proposal sent" (or "Proposal queued" when not yet published).
- The payer sees the proposal under PROPOSALS, opens Review and Subscribe, and accepts with a swipe. If the first period is due on acceptance the Send flow starts; otherwise the Subscribed confirmation opens. Both end on Close.
- Overview shows monthly cost, and the sections PROPOSALS, ACTIVE, EXPIRED, CREATED (`subscriptionSections`). The Payments tab hosts the one-time request list (see `payment-requests.md`).
- A subscription detail screen lists periods and offers More info (payer, when metadata exists) and Cancel (payer) or Delete (creator). Cancel needs `subscription.canCancel` and no end date; it is refused while a payment proof for the subscription is in progress.
- Frequencies: `PaykitRecurrenceUnit` Day, Week, Month, Year (default Month in the create form). Requests with a payment deadline cannot be accepted or paid (`docs/payment-requests.md`); the review explains unsupported payment details and offers no Subscribe control (`journeys/payment-requests/payment-deadline-history.xml`).
- A proposal larger than 1000 bytes (`PaykitSubscriptionProposal.MAX_MESSAGE_BYTES`) is rejected with a "content too long" error toast.
- Payer reminders: see `notifications.md`.
- Gated by the same Paykit UI flag as `payment-requests.md`; the drawer entry is hidden when it is off.

## How a user reaches it

- Home > open drawer menu > `DrawerSubscriptions` > `SubscriptionsScreen` (`Tab-overview`, `Tab-payments`). Route `Routes.Subscriptions(showPayments)` in `app/src/main/java/to/bitkit/ui/ContentView.kt`; no deeplink.
- Create: `SubscriptionCreate` > sheet `CreateSubscription` (amount pencil > keypad, `SubscriptionName`, `SubscriptionDescription`, `SubscriptionIconPicker`, frequency selector, `SubscriptionExpiration`) > `SubscriptionChooseRecipient` > recipient list (rows `SubscriptionContact<pubky>`) > `SubscriptionPropose` > `SubscriptionProposalSent` (`SubscriptionConfirmationBody`).
- Review: tap a proposal row `SubscriptionRow-<paymentRequestId>` (payer) opens the Review and Subscribe sheet (`Sheet.Subscription`, `SubscriptionRoute.Review`).
- Detail: tap an active, expired or created row to open `Routes.SubscriptionDetail`; Cancel or Delete opens the swipe-confirm sheet (`SubscriptionRoute.Cancel`).
- Reminder tap: notification "Subscription Payment Due" opens the app and presents the unpaid period (see `notifications.md`).

## Code

- `app/src/main/java/to/bitkit/ui/screens/subscriptions/SubscriptionsScreen.kt`: `SubscriptionsScreen`/`SubscriptionsContent` (tabs, sections, pinned header and footer), `SubscriptionDetailScreen`, `SubscriptionSheet` (Review, Success, Details, Cancel), status and frequency text, next-transition timer.
- `app/src/main/java/to/bitkit/ui/screens/subscriptions/CreateSubscriptionScreen.kt`: `CreateSubscriptionSheet` steps Details, Amount, Recipient, Sent. `SubscriptionRow.kt`: row.
- `app/src/main/java/to/bitkit/repositories/PaykitSubscription.kt`: model, recurrence and billing-period math, lifecycle predicates (`isPayer`, `canCancel`, `runsUntilPaidThrough`).
- `app/src/main/java/to/bitkit/repositories/PaykitSubscriptionProposal.kt`: proposal size check. `PaykitPaymentRequestRepo.kt`: `proposeSubscription`, `accept(subscription)`, `cancel`, `subscriptionProposals`.
- `app/src/main/java/to/bitkit/repositories/PaykitSubscriptionNotificationScheduler.kt`: WorkManager reminders. `app/src/main/java/to/bitkit/repositories/PaykitPaymentRequestPresentationStore.kt`: subscription presentation state (also in backups via `models/PaykitPaymentStateBackup.kt`).
- `app/src/main/java/to/bitkit/viewmodels/AppViewModel.kt`: `showSubscriptionCreator`, `createSubscription`, `acceptSubscriptionAndStartPayment`, `cancelSubscription`, `onPaykitSubscriptionNotificationTapped`.
- `app/src/main/java/to/bitkit/utils/SubscriptionClockOffset.kt`: debug-only day offset for scheduling; set in Dev Settings (`SubscriptionClockOffset`, `SubscriptionClockOffset-<days>`; presets 0, 1, 7, 30, 31, 62, 365 days). `utils/SubscriptionIcon.kt`: icon choices. `ui/components/DrawerMenu.kt`: drawer entry; `SheetHost.kt`: `SubscriptionRoute`.

## How to drive it

Backend: regtest, two Bitkit instances with Pubky identities saved as each other's contacts and linked (creator and payer); payer funded for the amount. Setup in `journeys/subscriptions/README.md` (uses `Journey Sub`, 5,000 sats, Monthly).

- `journeys/subscriptions/create-and-propose.xml`: creator builds and proposes; 600-letter description is rejected, shorter one sent; row in CREATED.
- `journeys/subscriptions/review-and-subscribe.xml`: payer sees PROPOSALS row, Review and Subscribe, swipe, first payment or Subscribed, Close.
- `journeys/subscriptions/cancel-and-delete.xml`: payer cancels an active subscription; creator deletes a pending proposal (separate subscription).
- `journeys/subscriptions/payments-tab.xml`: Payments tab badge, incoming Dismiss and Pay, outgoing pending row, detail screen.
- `journeys/subscriptions/fixed-onchain-destination.xml`: issuer-proposed monthly request with fixed P2WPKH address; address reuse does not block a period; a paid period cannot be paid again.
- `journeys/subscriptions/cancellation-during-confirmation.xml`: issuer cancels while the confirmation is open.
- Related: `journeys/payment-requests/delete-contact-with-active-subscription.xml`, `payment-deadline-history.xml`; `journeys/paykit-clock-changes.md` for reminders, timezones and daylight saving.
- E2E: none. `bitkit-e2e-tests/test/specs/paykit.e2e.ts` does not touch subscriptions. Unit tests: `PaykitSubscriptionTest.kt`, `PaykitSubscriptionProposalTest.kt`, `PaykitPaymentRequestRepoSubscriptionTest.kt`, `PaykitSubscriptionNotificationSchedulerTest.kt` under `app/src/test/java/to/bitkit/repositories/`.

## What proves it

- Creator: `SubscriptionProposalSent` with recipient, name and amount; `SubscriptionRow-<id>` in CREATED with subtitle "Proposal sent".
- Payer: row moves from PROPOSALS to ACTIVE; Send flow ends in `SendSuccess` when payment is due on acceptance, else the Subscribed sheet.
- Cancel: swipe-confirm sheet closes and the row leaves ACTIVE or changes state (`cancel-and-delete.xml`); delete: a proposal without paid periods leaves CREATED.
- Reminder: a "Subscription Payment Due" notification and, on tap, the unpaid period's confirmation.

## Not covered by tests

- No e2e spec at all; every flow needs two instances and manual or agent-driven journeys.
- Day, Week and Year frequencies, finite subscriptions with an end date, proposal expiration choices (`SubscriptionExpiration`: Hour, Day, Week default, Month; `PaymentRequestExpiration`), and the icon picker beyond presence.
- Lightning-funded subscription payments, and renewal of a second period (journeys use Monthly to avoid renewal mid-run; only `SubscriptionClockOffset` can advance time on debug builds).
- Review of an unsupported proposal (payment deadline or unsupported frequency) beyond the unit tests and `payment-deadline-history.xml`.
- Subscription tags: `SubscriptionDetailScreen`, `SubscriptionSheet` screens and their buttons have no `testTag` in code (grep of `ui/screens/subscriptions/`); drive them by text.

## Gotchas

- The Subscriptions screen layers top bar, tabs and footer over the list with a blur; a row partly under the tab bar or Create button is expected. Assert row presence, not full visibility.
- Accepting does not return to the list by itself; with payment due the Send flow must be finished.
- The creator's Delete is available only while the subscription is proposed or active; a cancellation arriving from the payer already removes it.
- `SubscriptionRow-<paymentRequestId>` matches iOS; the Payments row id differs (iOS appends the billing period).
- `android layout` can omit tags on plain `Box` and `Column`; use the raw UI Automator hierarchy.
- Deleting a contact with an active subscription is refused until the subscription ends.

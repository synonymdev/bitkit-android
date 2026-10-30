# Allowances journeys

Cover the Paykit allowance lifecycle between two Bitkit instances: the payer sets an allowance for a
contact, the payee's wallet accepts it by itself, requests within the limits are paid without asking, a request above a
limit falls back to the normal Payment Request sheet, and either side ends it. The restart journey
pins the one rule that must never break: a payment interrupted mid-flight is never paid twice.
The restore journey checks that a payer restored from its recovery phrase keeps its allowance history.

## Setup

Run Bitkit against regtest with Paykit UI enabled on two instances that have each other saved as
contacts and linked over Paykit, exactly as for
[`../subscriptions/README.md`](../subscriptions/README.md). One instance plays the payer (the one
that sets the allowance), the other the payee (the one that sends requests). Automatic payments go
over Lightning only, so the payer needs a spending balance above 50,000 sats with a usable channel,
and the payee needs receiving capacity; fund both through the staging LSP.

Grant both instances notification permission first (`adb shell pm grant to.bitkit.dev
android.permission.POST_NOTIFICATIONS`): the payer's "Payment Executed" and "Limit Reached" events and
the payee's "Allowance Added" event post a system notification when they can, and fall back to a toast
that `android layout` cannot see. "Limit Reached" posts nothing while Bitkit is in the foreground and
unlocked, because the Payment Request sheet for the same request opens at the same time; behind the PIN
screen the sheet waits, so the notification is posted.

The journeys set $5 a payment and $50 a month, the second stop on each slider, and the cap journey
sets $5 a payment and $10 a month, the first monthly stop. Requests are one-time Payment Requests
created from the Payments tab of the payee, priced in bitcoin. On Android, enter the amount in sats:
a request entered in the fiat unit is priced in USD, and allowances pay only bitcoin-priced requests, so it
stays manual. iOS converts a dollar entry to a bitcoin amount when it creates the request, so the fiat unit
works there. Dollar amounts on screen are converted
at the current rate, so a sats amount next to them will differ between runs.

## Reference evidence

The source run drove every journey on 2026-09-24 across two Android 16 emulators against the
staging regtest LSP, with Paykit built from pubky/paykit-rs#161. A $2 and a $4 request were paid
about two seconds after arriving, a $20 request asked, the third $4 request on a $10 monthly cap
raised Limit Reached, ending the allowance from either side stopped automatic payments, and a payer
killed right after handing the payment to the node never paid twice after relaunch.

## Identifiers used

- Tab: `Tab-allowances`; empty state `AllowancesEmpty`; footer button `AllowanceAdd`
- Contact picker: `AllowanceContact-<displayName>`
- Set sheet: `SetAllowance`, sliders `AllowancePerPayment` and `AllowanceMonthly` with stops
  `AllowancePerPaymentStop-<index>` and `AllowanceMonthlyStop-<index>`, `AllowanceSummary`,
  `AllowanceSave`
- List row: `AllowanceRow-<entryId>` with its status line `AllowanceRowStatus`; the entry id is the payer's grant id, or the contact key and allowance id on the payee
- Offer notification (payee): the toast `AllowanceOfferAcceptedToast` when notifications are off
- Review sheet: `AllowanceReview`, `AllowanceCounterparty`, `AllowancePerPaymentValue`,
  `AllowanceMonthlyValue`, `AllowanceAccept`, `AllowanceDecline`; it opens only on the allower's wallet
  for an allowance the payee asked for, never for the payer's own offer
- Detail sheet: `AllowanceDetail`, `AllowancePaidSoFar`, `AllowanceDetailStatus`
- Payments tab: `Tab-payments`, `PaymentRequestCreate`, `PaymentRequestRow-<paymentRequestId>`
  whose subtitle ends with "· Auto-paid" for an automatic payment
- Incoming request: `PaymentRequestsBell`, `PaymentRequestsSheet`

A payer who requires a PIN for payments sees the PIN pad (or biometrics) over the sheet when saving or ending
an allowance, and when accepting an allowance a payee asked for; the payee's answers and automatic payments never
ask. `pin-gates-allowance-changes.xml` covers it.

The payee's wallet accepts the payer's offer as soon as it arrives, about a second after the
proposal is received, and posts "<payer> set up an allowance for you"; no review sheet opens and no
tap is needed. `android layout` can omit test tags applied to plain `Box` and `Column`
containers; use the raw UI Automator hierarchy when a documented container tag is missing.

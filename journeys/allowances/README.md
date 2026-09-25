# Allowances journeys

Cover the Paykit allowance lifecycle between two Bitkit instances: the payer sets an allowance for a
contact, the payee accepts it, requests within the limits are paid without asking, a request above a
limit falls back to the normal Payment Request sheet, and either side ends it. The restart journey
pins the one rule that must never break: a payment interrupted mid-flight is never paid twice.

## Setup

Run Bitkit against regtest with Paykit UI enabled on two instances that have each other saved as
contacts and linked on receiver path `bitkit/wallet`, exactly as for
[`../subscriptions/README.md`](../subscriptions/README.md). One instance plays the payer (the one
that sets the allowance), the other the payee (the one that sends requests). Automatic payments go
over Lightning only, so the payer needs a spending balance above 50,000 sats with a usable channel,
and the payee needs receiving capacity; fund both through the staging LSP.

Grant the payer notification permission first (`adb shell pm grant to.bitkit.dev
android.permission.POST_NOTIFICATIONS`): the "Payment Executed" and "Limit Reached" events post a
system notification when they can, and fall back to a toast that `android layout` cannot see.

The journeys set $5 a payment and $50 a month, the second stop on each slider, and the cap journey
sets $5 a payment and $10 a month, the first monthly stop. Requests are one-time Payment Requests
created from the Payments tab of the payee, in the fiat unit. Dollar amounts on screen are converted
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
- List row: `AllowanceRow-<allowanceId>` with its status line `AllowanceRowStatus`
- Review sheet (payee): `AllowanceReview`, `AllowanceCounterparty`, `AllowancePerPaymentValue`,
  `AllowanceMonthlyValue`, `AllowanceAccept`, `AllowanceDecline`
- Detail sheet: `AllowanceDetail`, `AllowancePaidSoFar`, `AllowanceDetailStatus`
- Payments tab: `Tab-payments`, `PaymentRequestCreate`, `PaymentRequestRow-<paymentRequestId>`
  whose subtitle ends with "· Auto-paid" for an automatic payment
- Incoming request: `PaymentRequestsBell`, `PaymentRequestsSheet`

The review sheet on the payee opens on its own about a second after the proposal arrives; no tap is
needed to reach it. `android layout` can omit test tags applied to plain `Box` and `Column`
containers; use the raw UI Automator hierarchy when a documented container tag is missing.

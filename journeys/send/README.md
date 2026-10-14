# Send

`bip21-core-validation.xml` is shared with iOS, with identical actions and fixtures. It requires a released bitkit-core SDK containing [PR #151](https://github.com/synonymdev/bitkit-core/pull/151); the current 0.5.18 dependency cannot satisfy its duplicate-key checks. Keep the adoption PR in draft until that dependency is bumped and the journey passes.

Deliver each `Open bitcoin:...` action with `adb shell "am start -a android.intent.action.VIEW -d '<uri>' to.bitkit.dev"`, replacing XML `&amp;` with `&` in the actual URI. Inspect errors immediately. Use `android layout` for payment state and screenshots for transient error toasts, which the semantic dump does not expose.

Use an onboarded regtest wallet with at least 100,000 sats in Savings and Quickpay disabled. The fixture address is not a destination to pay; close every payment sheet without confirming payment.

The native-binding tests also cover labels, proof-of-payment aliases and question marks in notes. App tests cover manual entry and payment-request failure handling. Automatic clipboard prompting additionally has a manual check because arbitrary OS clipboard injection is not listed in the Capabilities table. Do not type long URI fixtures with `adb shell input text`, which can truncate them.

`own-invoice-guard.xml` covers the existing Lightning self-payment guard and is unchanged by this adoption.

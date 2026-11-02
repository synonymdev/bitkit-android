# Android instrumented test lanes

`CI` runs the `ComposeUi` annotation on pull requests (including drafts) and master
pushes. These tests render controlled state without funded wallets or external
backends. The required `build` job aggregates `build-and-test` and `compose-ui`;
it runs even after dependency failure/skip and succeeds only when both succeed.
The active master ruleset already requires `build` (alongside `lint` and
`e2e-status`), so this change does not require editing repository protection.
Keep this aggregate name stable. The migration workflow displays its separate build
as `migration-build`, so its skipped PR job cannot collide with the required `build`
context. Its job ID and dependencies remain unchanged. The APK comment uses the
build artifact directly.

`Android Instrumented Tests` runs the complement of `ComposeUi` on master pushes
and defaults to that same `integration` selection on manual dispatch. Excluding
Compose instead of selecting `CoreServiceIntegration` retains device/storage tests
and opt-in, unannotated fixtures. External failures remain visible in this separate
workflow and do not decide the deterministic PR gate. Manual `all` still runs every
test; individual annotation names and comma-separated selections remain supported.
Every selection must produce a report with at least one executed test, not just skips.

Locally, use `just test lane ComposeUi` for the deterministic lane. The workflow
entry point accepts `ANDROID_TEST_SUITES=ComposeUi`, `integration`, or `all` when
running `bash .github/scripts/run-ui-tests.sh`. Use an owned disposable emulator:
native fixtures create/wipe wallets, and recovery fixtures retain state for UI work.
Use an AOSP image without Google Play Services, matching CI. Google APIs images can
start FCM token delivery between test cases, when the Hilt test application has no
active component, crashing the test process. This lane does not validate push delivery.
The CI emulator is Pixel 4, API 30, AOSP (`default`), x86_64; dev/debug uses the staging regtest backend.

Funding-dependent RBF, recovery, and UTXO-selection tests wait up to two minutes
for every returned deposit transaction to be confirmed and for its exact amount
to appear in native spendable outputs. They sync between observations, preserve
subsequent balance/selection assertions, and fail immediately on sync/native errors.
Timeout diagnostics include expected transaction amounts, wallet tip, balances,
confirmed deposits, and spendable outpoints. The workflow's 45-minute timeout is a
backstop for native calls that cannot be interrupted by coroutine cancellation.

The existing bitkit-docker stack supplies Bitcoin/Electrum and LNURL fixtures,
but does not supply the Blocktank regtest API used by these native tests' funding
helpers. Moving only Electrum to localhost would fund a different chain. Keep this
lane on staging until funding and wallet sync can both use isolated infrastructure;
do not promote the combined suite to a required PR check merely after one green run.

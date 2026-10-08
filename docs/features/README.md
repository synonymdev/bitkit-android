# Feature map

Read `docs/features/<feature>.md` before changing or testing that feature. A PR that changes a flow, a screen route, a `testTag`, a journey or an e2e spec updates the file of that feature in the same PR. The files tell an agent what a feature does, how to reach it on a device, which code owns it, how to drive it (journeys and e2e specs), what proves it worked, what no test covers, and the traps already found.

## How the files are shaped

Every feature file has the same sections in the same order: What it does, How a user reaches it, Code, How to drive it, What proves it, Not covered by tests, Gotchas. Paths are relative to the repo root; e2e specs are written `bitkit-e2e-tests/test/specs/<name>.e2e.ts` (the `synonymdev/bitkit-e2e-tests` repo, Appium/WebdriverIO, not part of this repo). Feature names match the iOS map so the two stay diffable.

- Journeys (`journeys/<folder>/*.xml`) are the QA contract for a PR and are driven with the `android` CLI; see `journeys/README.md`. A journey is not the source of truth: when it disagrees with the app, it is probably stale.
- E2E specs run in CI by tag: `e2e.yml` (local `bitkit-docker` backend), `e2e-staging.yml` (staging regtest), `e2e_migration.yml`. A spec with a tag in none of those greps does not run there. Each file says which shard runs its specs.
- E2E specs do not cover every flow, and there are no Maestro flows. "Not covered by tests" in each file lists what neither journeys nor specs reach.
- A `testTag` becomes `resource-id` in `android layout`. Tags built from a variable are written with a placeholder (`Tab-<name>`).
- Screen to Figma frame mapping is in `docs/screens-map.md`; other topic docs sit next to it in `docs/`.
- Every statement was read from the code, docs, journeys or specs at app commit `484e6dd15` and e2e commit `54978d9`. Nothing was run on a device.

## Index

| Feature | File | Journeys folder | E2E spec(s) |
| --- | --- | --- | --- |
| Onboarding: create wallet, restore wallet | `onboarding.md` | `restore-wallet` | `onboarding.e2e.ts` |
| Home: balances, suggestions, pull-to-refresh | `home.md` | `home` | none of its own; `settings.e2e.ts` touches units and balance |
| Receive: on-chain, Lightning, CJIT | `receive.md` | `receive`, `onchain-receive` | `receive.e2e.ts`, `onchain.e2e.ts`, `lightning.e2e.ts`, `numberpad.e2e.ts`, `receive-ln-payments.e2e.ts`, `mainnet/cjit.e2e.ts` |
| Send: on-chain, Lightning, scan/paste, amount limits, coin selection step | `send.md` | `send`, `amount-limits`, `coin-selection` | `send.e2e.ts`, `onchain.e2e.ts`, `lightning.e2e.ts`, `numberpad.e2e.ts`, `mainnet/ln.e2e.ts`, `mainnet/probe.e2e.ts` |
| Send: LNURL, Lightning Addresses | `lnurl.md` | `lnurl` | `lnurl.e2e.ts`, `mainnet/ln.e2e.ts` |
| Transfer: savings to spending, external and LNURL channel | `transfer.md` | `transfers`, `amount-limits` (transfer files) | `transfer.e2e.ts`, `mainnet/channel-order.e2e.ts` |
| Transfer: spending to savings, channel close, settle rules | `transfer-savings.md` | `transfer`, `transfers` | `transfer.e2e.ts`, `multiaddress.e2e.ts` (helper) |
| Activity: list, detail, tags, boost | `activity.md` | `activity`, `tags` | `boost.e2e.ts` |
| Backup: recovery phrase, backup and restore | `backup.md` | `backup`, `backup-restore` | `backup.e2e.ts` |
| Security: PIN, biometrics, recovery mode | `security.md` | `security` | `security.e2e.ts` |
| Settings: general, support, developer | `settings.md` | (`settings` is listed in `settings-advanced.md`) | `settings.e2e.ts` |
| Settings: advanced (address types, coin selection, node, Electrum, RGS, channels) | `settings-advanced.md` | `settings`, `coin-selection`, `node-lifecycle` | `multiaddress.e2e.ts`, `settings.e2e.ts` |
| Contacts | `contacts.md` | `contacts`, `pubky-profile` | `pubky-profile.e2e.ts` |
| Profile / Pubky | `profile.md` | `profile`, `pubky-profile` | `pubky-profile.e2e.ts` |
| Widgets (in-app and Android home-screen) | `widgets.md` | `widgets` | `widgets.e2e.ts` |
| Payment requests (Paykit) | `payment-requests.md` | `payment-requests`, `pubky-marketplace`, `paykit-clock-changes.md` | `paykit.e2e.ts` |
| Subscriptions (Paykit) | `subscriptions.md` | `subscriptions` | none |
| Notifications: CJIT push, background payments, permission | `notifications.md` | `cjit-notifications`, `notification-permission`, `onchain-receive` | none |
| App update and RN migration | `app-update.md` | `app-update` | `migration.e2e.ts` |
| Deeplinks | `deeplinks.md` | `deeplinks` | none |
| Hardware wallet: pairing, receive, send | `hardware-wallet.md` | `hardware-wallet` | `hardware-wallet.e2e.ts` |
| Hardware wallet: transfer to spending | `hardware-wallet-transfer.md` | `hardware-wallet` | `hardware-wallet.e2e.ts` |
| Shop | `shop.md` | `shop` | none |

Not mapped to a feature file: `bitkit-e2e-tests/test/qa-fixtures/qa-fixture.e2e.ts` (`@qa_fixture`, sets up a local QA device, not in CI).

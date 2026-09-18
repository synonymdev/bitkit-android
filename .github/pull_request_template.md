<!-- Closes | Fixes | Resolves #ISSUE_ID -->
<!-- Changelog: For user-facing changes, add one fragment in changelog.d/next/ or changelog.d/hotfix/. Do not edit CHANGELOG.md in normal PRs. -->
<!-- Brief summary of the PR changes, linking to the related resources (issue/design/bug/etc) if applicable. -->

### Description

<!-- One bullet per change: what changed and why. -->

#### Out of Scope

<!-- One bullet per item this PR deliberately leaves out (`path/or/area: item`); reviewers treat them as your non-goals. `None.` when nothing is left out. Required for feat, fix, and refactor PRs; delete it for version, changelog, or dependency bumps and release PRs; other chore, docs, and test PRs at your discretion. -->

### Design

<!-- Figma frames for the changed UI (start from docs/screens-map.md). Otherwise `N/A — no UI changes.` or `N/A — no design available.` State an uncertain match; never invent a link. -->

### Preview

<!-- Screenshot or recording of the changed UI; `N/A` when there is no user-visible change. -->

### QA Notes

#### Journeys

<!-- One line per journey this PR adds or updates: `new` or `updated`, the bare journey file name in backticks, then what it proves — `- [ ] new `send-amount-over-balance.xml` — error shows before the 15 s timeout`; prefix the shortest disambiguating folder only when two journeys share a name. `N/A — no user-visible behaviour change.` is the only empty value. Leave the boxes unchecked; the reviewer ticks a line after driving it. -->

#### Manual Tests

<!-- Only for a step needing a capability the Capabilities table in `journeys/README.md` does not list: action → expectation — the missing capability, as in `- [ ] Pair a Trezor over BLE → Home shows the hardware wallet card — BLE pairing not in Capabilities`. `N/A` when there is none. -->

#### Automated Checks

<!-- Flat list in the keyword order `added`, `updated`, `removed`, `ran`: keyword, bare test file name, dash, the behaviour proven — `- added `TransferViewModelTest.kt` — rejects amounts over the spending balance`; `ran` only for what CI does not run. `N/A` when nothing changed. -->

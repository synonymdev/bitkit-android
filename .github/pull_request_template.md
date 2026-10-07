<!-- Closes | Fixes | Resolves #ISSUE_ID -->
<!-- Optional related PRs, after issue-closing lines and before the summary; include only confirmed references, with repository-qualified numbers:
Twin: owner/other-native-app#number
Companion: owner/related-repo#number
Dependency: owner/prerequisite-repo#number
Twin = same Android/iOS change; Companion = coordinated non-prerequisite work (e.g. E2E coverage); Dependency = prerequisite (e.g. bitkit-core or another library). Describe dependency merge/release order when needed. Omit absent labels. -->
<!-- Changelog: For user-facing changes, add one fragment in changelog.d/next/ or changelog.d/hotfix/. Do not edit CHANGELOG.md in normal PRs. -->
<!-- Brief summary of the PR changes, linking to the related resources (issue/design/bug/etc) if applicable. -->

<!-- Keep in draft during development/fixes; mark ready only after applicable validation. Prefer returning to draft while addressing review feedback. Draft-aware reviewers skip drafts; automation must honor this separately. -->

### Description

<!-- One bullet per change: what changed and why. -->

#### Out of Scope

<!-- One bullet per item this PR deliberately leaves out (`path/or/area: item`); reviewers treat them as your non-goals. `None.` when nothing is left out. Required for feat, fix, and refactor PRs; delete it for version, changelog, or dependency bumps and release PRs; other chore, docs, and test PRs at your discretion. -->

### Design

<!-- Figma frames for the changed UI (start from docs/screens-map.md). Otherwise `N/A — no UI changes.` or `N/A — no design available.` State an uncertain match; never invent a link. -->

### Preview

<!-- Screenshot or recording of the changed UI; `N/A` when there is no user-visible change. -->

### QA Notes

#### Setup

<!-- Enough setup for the requested device journeys/manual tests: executable steps or a reproducible shared recipe (resolved commit) plus PR-specific deviations. Include only relevant service/library versions or pins, staging vs local backend/network, configuration/flags, prerequisites, fixture accounts/identities and preparation/reset steps. Do not assume required Shop/Marketplace, Pubky Ring, homeserver or library versions exist on staging; explain compatible setup or missing prerequisites. Justified N/A for documentation-only/no-device-testing changes. Preserve authored QA notes on updates. -->

#### Journeys

<!-- One line per journey this PR adds or updates: `new` or `updated`, the bare journey file name in backticks, then what it proves — `- [ ] new `send-amount-over-balance.xml` — error shows before the 15 s timeout`; prefix the shortest disambiguating folder only when two journeys share a name. `temporary` only when the author asks for a reproduction that cannot be committed; its XML and any `.diff` go in a collapsed `<details>` block under the line. Two empty values: `N/A — no user-visible behaviour change.`, or `N/A — not drivable; see Manual Tests.` when every flow touched needs a capability the Capabilities table in `journeys/README.md` does not list. Leave the boxes unchecked; the reviewer ticks a line after driving it. -->

#### Manual Tests

<!-- Only for a step needing a capability the Capabilities table in `journeys/README.md` does not list: action → expectation — the missing capability, as in `- [ ] Pair a Trezor over BLE → Home shows the hardware wallet card — BLE pairing not in Capabilities`. `N/A` when there is none. -->

#### Automated Checks

<!-- Flat list in the keyword order `added`, `updated`, `removed`, `ran`: keyword, bare test file name, dash, the behaviour proven — `- added `TransferViewModelTest.kt` — rejects amounts over the spending balance`; `ran` only for what CI does not run. `N/A` when nothing changed. -->

#### Evidence

<!-- Authors are strongly encouraged to run the tests requested from reviewers before requesting review; mandatory checks still apply. Briefly state cases actually verified, app commit/build, environment, observed results and supporting links where useful. Tests added/requested or checkboxes alone are not proof of execution. For docs-only changes, report documentation checks. -->

#### Gaps

<!-- Identify failed, blocked or untested cases, the failure or reason they were not run, and missing prerequisites. Never present partial verification as a complete pass. None only when no applicable gaps remain; justify runtime N/A for docs-only changes. Preserve authored notes and tested revisions when updating. -->

### Models used

<!-- Informational: use the actual model/effort pairs from session metadata, with separate inline-code spans as below. Use one Review line for all passes; deduplicate pairs, and separate distinct pairs with commas. Never add per-round model rows. Optionally add `- Review rounds: N` when the completed-pass count is known, not a count of parallel reviewer agents. Use `Unknown` if unrecorded or `reasoning: Not exposed` if no setting is exposed. Use `Not used` for phases without AI, and `Review: Not performed` when no review occurred; omit effort for these values. Preserve known pairs on updates. -->

- Planning/scoping: `<model name>` (reasoning: `<effort>`)
- Implementation: `<model name>` (reasoning: `<effort>`)
- Review: Not performed

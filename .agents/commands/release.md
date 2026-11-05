---
description: "Create a new release: bump version, create PR, build mainnet, tag, draft release"
allowed_tools: Bash, Read, Edit, Write, Glob, Grep, WebFetch, WebSearch, AskUserQuestion, mcp__github__create_pull_request, mcp__github__list_pull_requests, mcp__github__pull_request_read, mcp__github__get_file_contents, mcp__github__update_pull_request
---

Automate the full release process for bitkit-android.

**Examples:**
- `/release` - Interactive, prompts for version (defaults to patch bump)

## Steps

### 1. Read Current Version

Read `app/build.gradle.kts` and extract:
- `versionCode` (integer, e.g. `176`)
- `versionName` (string, e.g. `"2.0.2"`)

Parse versionName into `{major}.{minor}.{patch}` components.

Compute defaults:
- Next patch: `{major}.{minor}.{patch+1}`
- Next minor: `{major}.{minor+1}.0`
- Next major: `{major+1}.0.0`
- Next versionCode: `versionCode + 1`

### 2. Ask for Version

Use `AskUserQuestion` with header "Version":

**Question:** `"New version? (current: {versionName}, build {versionCode})"`

**Options:**
1. `{major}.{minor}.{patch+1}` (Recommended) — description: "Patch release"
2. `{major}.{minor+1}.0` — description: "Minor release"
3. `{major+1}.0.0` — description: "Major release"

The user can always pick "Other" to enter a custom version string.

Store the chosen version as `newVersionName` and compute `newVersionCode = versionCode + 1`.

### 2b. Ask for Base (patch releases only)

If the user chose a **patch** release, use `AskUserQuestion`:

**Question:** `"Branch from? Patch releases can be cut from master or from a previous tag with cherry-picked commits."`

**Options:**
1. "master" (Recommended) — description: "Branch from latest master"
2. "Previous tag" — description: "Branch from a tag (e.g. v{oldVersionName}), then cherry-pick commits"

If "Previous tag": ask `"Which tag?"` with a text input (default: `v{oldVersionName}`). Store as `{baseRef}`.

If "master" or if the release is minor/major: `{baseRef} = master`.

Set `{changelogTarget}`:
- If `{baseRef}` is `master`: `next`
- Otherwise: `hotfix`

### 3. Create Release Branch & Bump Version

```bash
git fetch origin
git checkout {baseRef}
```

If `{baseRef}` is `master`, pull latest: `git pull origin master`. Skip pull if baseRef is a tag.

```bash
git checkout -b release-{newVersionName}
```

If the base is a tag (not master), print:
```
Release branch created from {baseRef}.
Cherry-pick the commits you need onto this branch now, then continue.
```
Wait for the user to confirm they are done cherry-picking before proceeding.

Finalize changelog after the release branch contains all release commits:

```bash
scripts/collect-changelog.sh --target {changelogTarget}
```

Read `CHANGELOG.md` and check whether `## [Unreleased]` has any entries beneath it after collecting fragments.

**If entries exist:**
1. Replace `## [Unreleased]` with `## [{newVersionName}] - {YYYY-MM-DD}` (today's date)
2. Insert a fresh empty `## [Unreleased]` section above the new version heading
3. Update the compare link references at the bottom of the file:
   - Change `[Unreleased]` link to compare from `v{newVersionName}...HEAD`
   - Add a new `[{newVersionName}]` link comparing `v{oldVersionName}...v{newVersionName}`

**If no entries:** Print `⚠ CHANGELOG.md has no unreleased entries — continuing without changelog update.` and proceed.

Edit `app/build.gradle.kts`:
- Change `versionCode = {old}` to `versionCode = {newVersionCode}`
- Change `versionName = "{old}"` to `versionName = "{newVersionName}"`

```bash
git add app/build.gradle.kts
git commit -m "chore: version {newVersionName}"
git push -u origin release-{newVersionName}
```

If changelog collection updated `CHANGELOG.md` or deleted consumed fragments, run `git add CHANGELOG.md changelog.d` before the commit.

### 4. Create Version Bump PR

Read `.github/pull_request_template.md` for structure. Create PR:

- **Title:** `chore: bump version {newVersionName}`
- **Base:** master
- **Body:**
```
Bump version to {newVersionName} (build {newVersionCode}) for release.

### Description

- `versionCode`: {oldVersionCode} → {newVersionCode}
- `versionName`: {oldVersionName} → {newVersionName}

### Design

N/A — no UI changes.

### Preview

N/A

### QA Notes

Sanity planning: deferred until the intended release candidates are available (step 8a). No sanity tests have run.
```

Store the PR URL for the summary.

### 5. Tag & Draft GitHub Release

Create the tag and draft release early so auto-generated release notes are available during QA.

Determine the previous version tag for changelog generation: `v{oldVersionName}`.

```bash
git tag -a v{newVersionName} -m "v{newVersionName}"
git push origin v{newVersionName}
```

```bash
gh release create v{newVersionName} \
  --title "v{newVersionName}" \
  --draft \
  --generate-notes \
  --notes-start-tag v{oldVersionName} \
  --target release-{newVersionName}
```

### 6. Generate Store Release Notes

Before drafting, apply the shared [store release-note checks](https://github.com/synonymdev/bitkit-e2e-tests/blob/main/docs/store-release-notes.md) for **Google Play**. Load the guide from one resolved `bitkit-e2e-tests` default-branch revision, check the current official policy links and record the guide revision/check date in `.ai/store-notes-check-{newVersionName}.md`. If a required source is unavailable, record the missing check; branch/build preparation may continue, but do not claim store notes are verified or submit until it is resolved.

Read the `## [{newVersionName}]` section from `CHANGELOG.md` as the primary source for release content. If that section is empty or was not created in Step 2c, fall back to fetching auto-generated release notes:

```bash
gh release view v{newVersionName} --json body --jq .body
```

Using the changelog entries (or auto-generated notes as fallback) as context, write a concise user-facing summary of the release (2-3 sentences max, no commit hashes or PR numbers, written for end users not developers). Focus on new features and important bug fixes. Omit chores, maintenance, refactoring, CI changes, and test coverage improvements — these are not relevant to Play Store users. Validate the English draft before translating it into the five source languages below. Then check every translation independently using the shared guide, including platform references, feature accuracy, grammar and field limits. Prefer platform-neutral benefit wording for both stores; do not infer that Google has Apple’s blanket platform-reference rule.

Create `.ai/` directory if it doesn't exist. Save to `.ai/release-notes-{newVersionName}.md`:

```markdown
# Release Notes v{newVersionName}

## English
{summary}

## French
{french translation}

## Spanish
{spanish translation}

## Portuguese
{portuguese translation}

## German
{german translation}
```

After the language checks, read the latest GitHub draft body and replace its existing `## Store Release Notes` section with the approved English summary; prepend that section only if it is absent. Preserve the changelog and all other authored sections. Write the complete updated body to a UTF-8 file and pass it with `gh release edit --notes-file`; read back the edited section. Re-running this step must not duplicate store-note sections or overwrite the engineering changelog.

Record generation/language/synchronization results in `.ai/store-notes-check-{newVersionName}.md`. Store-console verification remains `pending` until the actual saved fields have been read back. Print both local note/check-record paths for review.

### 6b. Create Shared Google Doc for Release Notes

Publish the release notes as a Google Doc for store-notes review. The doc must be readable by everyone at Synonym ("Anyone at Synonym with the link can view"), matching earlier release notes docs. Do not look up people by display name — domain sharing and Slack `<@UID>` mentions handle distribution.

**Reuse if present.** Search Drive for the exact native Google Doc title `v{newVersionName} Android`. Reuse the oldest match; report any duplicates instead of creating another. Read the live document and compare its text with `.ai/release-notes-{newVersionName}.md`, ignoring only heading syntax and trivial whitespace. Reconcile human edits before replacing differing wording; ask when the intended content conflicts. Update the same document in place through supported Docs tools, preserving its structure and unrelated content, using a revision guard when available. Read back every edited language and record the sync result. If editing is unavailable, mark Drive sync `pending` with the manual follow-up; do not claim the copies match. Re-apply domain sharing only if missing.

**Otherwise create the doc** with the Google Drive MCP `create_file` tool. Drive converts Markdown into a native Google Doc, so pass the notes file verbatim:
- `title`: `v{newVersionName} Android`
- `contentMimeType`: `text/markdown`
- `textContent`: full contents of `.ai/release-notes-{newVersionName}.md`

Store the returned `id` and `viewUrl`.

**Share with the Synonym domain.** The Drive MCP `share_file` tool only accepts user/group emails, so use Composio's Google Drive toolkit instead (`COMPOSIO_SEARCH_TOOLS` → `COMPOSIO_MULTI_EXECUTE_TOOL`, tool `GOOGLEDRIVE_CREATE_PERMISSION`):
- `file_id`: the document id
- `type`: `domain`
- `domain`: `synonym.to`
- `role`: `reader`

Do not share with individual emails and never use `type: anyone` (public).

**Verify** with the Google Drive MCP `get_file_permissions`: the result must contain a permission with `type: domain`, `domain: synonym.to`, `role: reader` (domain permissions use `domain`, not `emailAddress`).

**Fallbacks (never block the release on this step):**
- Google Drive MCP unavailable: print `⚠ Google Drive MCP unavailable — create the doc manually from .ai/release-notes-{newVersionName}.md` and continue with sharing status `not created`.
- Composio has no active `googledrive` connection: keep the doc and print `⚠ Domain sharing not applied — open the doc, Share → General access → "Synonym" → Viewer`, then continue with sharing status `created, domain share pending`.
- Share succeeded but verification does not show the domain permission: print the same manual-share warning and continue with sharing status `created, domain share pending`.

Store the doc URL and sharing status (`shared with Synonym` | `created, domain share pending` | `not created`) for the summary.

### 6c. Prepare #bitkit-native Slack Draft (local only)

Prepare a footer-free Slack announcement for the team in `#bitkit-native` (`C07BJ7DNPCG`). **Do not post to Slack in this step** — write the local file only. Posting happens after this platform's build is uploaded (see **Post Slack** after step 8). **Default:** wait until both RCs are ready, then one complete parent+reply. **Rare:** single-platform hotfix — post for this platform only (see 8b). **Never** create a Slack draft or channel message that contains `TODO`. Mentions in the posted draft must use `<@UID>` from the Mentions roster below — never resolve recipients by display name.

**Write the local draft** to `.ai/slack-release-{newVersionName}.md` (create `.ai/` if needed). Format: Slack MCP markdown (`- ` lists, `[text](url)` links, `<@UID>` mentions). Fill this platform from the current release; fill the sibling from its release branch / PR / tag / Google Doc / TestFlight build when known. Incomplete sibling fields may stay as `TODO` **in this local file only**.

Template (Android is this platform; iOS is the sibling):

```markdown
# Slack draft — #bitkit-native — Release {newVersionName}

Format: Slack MCP markdown (`- ` lists, `[text](url)` links, `<@UID>` mentions).
Post via `slack_send_message_draft` only (never `slack_send_message`).
Send each draft from the Slack app, not the Cursor widget.
Do not post until both RCs are ready (no TODO in the Reply body).

## Thread parent

Release `{newVersionName}` :thread:

## Reply

Release candidates:
- iOS {newVersionName} ({iOS build}) - available in TestFlight ([github.com/synonymdev/bitkit-ios/releases](https://github.com/synonymdev/bitkit-ios/releases))
- Android {newVersionName} ({newVersionCode}) - available on [github.com/synonymdev/bitkit-android/releases](https://github.com/synonymdev/bitkit-android/releases)

cc: <@U04NN8MV2GY> <@U03DQKN95BK>
cc: <@U031TCP84D8> <@U06UXSF134N> for design review

Proposed Store Release notes:
- Android: [v{newVersionName} Android]({Android Google Doc URL from 6b})
- iOS: [v{newVersionName} iOS]({iOS Google Doc URL})

cc: <@U07D3A8RSPN>

Please find GH release notes for both platforms for detailed changes:
- [github.com/synonymdev/bitkit-android/releases](https://github.com/synonymdev/bitkit-android/releases)
- [github.com/synonymdev/bitkit-ios/releases](https://github.com/synonymdev/bitkit-ios/releases)

Version bump PRs (release branches, merged into master after the store release):
- Android: [{Android PR URL label}]({Android PR URL from step 4})
- iOS: [{iOS PR URL label}]({iOS PR URL})

Sanity plan: {canonical release PR QA Notes link and planning status, or explicit deferred/blocked reason from step 8a}.
Execution results will be recorded in the [testing framework](https://docs.google.com/spreadsheets/d/10_DufEPwKUCExkXF-jTY7njE0mh5ZspwhRw42NBBd-o).

## Mentions

- U04NN8MV2GY Jean-Christophe
- U03DQKN95BK Pav
- U031TCP84D8 Aldert
- U06UXSF134N Oliver Toledo
- U07D3A8RSPN Jacobo
```

Print the path to the local draft. Store it for the summary. Slack posting is deferred until after the APK upload.

### 7. Build Mainnet Release

```bash
just release
```

Expected APK path: `app/build/outputs/bitkit/mainnetRelease/bitkit-mainnet-release-{newVersionCode}-universal.apk`
Expected AAB path: `app/build/outputs/bitkit/mainnetRelease/bitkit-mainnet-release-{newVersionCode}.aab`
Expected native debug symbols path: `app/build/outputs/native-debug-symbols/mainnetRelease/native-debug-symbols-{newVersionCode}.zip`

Verify all three files exist. The native debug symbols file must be from the same `just release` build as the APK/AAB. Keep the build-numbered filename, e.g. `native-debug-symbols-{newVersionCode}.zip`, so it matches the APK/AAB build number. `just release` resolves upstream native debug symbol artifacts from the Rust dependency packages, merges them into the final archive, and refuses placeholder symbols from stripped packaged `.so` files.

### 8. Upload APK and Native Symbols to Draft Release

```bash
gh release upload v{newVersionName} \
  app/build/outputs/bitkit/mainnetRelease/bitkit-mainnet-release-{newVersionCode}-universal.apk \
  app/build/outputs/native-debug-symbols/mainnetRelease/native-debug-symbols-{newVersionCode}.zip
```

For the Play Store release, upload the AAB as usual, then upload `native-debug-symbols-{newVersionCode}.zip` for the exact version/build in Play Console: App bundle explorer → Downloads → Assets. Verify Play lists the native debug symbols after upload. Keep the release-built archive in GitHub releases or internal release storage; Play Console may only show delete/replace controls after upload, which is enough for release verification.

### 8a. Generate Release Sanity Plan (after candidates are available)

Run this handoff before finalizing the Slack reply, including when Slack is skipped or already posted. The earlier local Slack draft is only a scaffold; do not generate a sanity suite while preparing the release branch/tag.

**Readiness:** use the intended release scope (both platforms by default; only one for an explicitly selected single-platform release). Android needs a completed signed mainnet APK with its version/build, source SHA and artifact location verified. iOS needs the matching build **available to testers in TestFlight**; an archive, upload or processing build is not sufficient. Use build/distribution evidence or explicit confirmation of availability, not the existence of a tag or version PR.

- If an intended candidate is missing, record `deferred — awaiting <platform/build>` in the version PR's QA Notes, local Slack draft and final summary, then continue the remaining release steps. Resume this handoff when availability is confirmed; do not repeat version bumps, tagging or builds merely to resume planning. This also applies to a manual Xcode upload.
- Once all intended candidates are available, **execute** `/release-sanity {newVersionName}` using the shared [release-sanity skill](https://github.com/synonymdev/bitkit-e2e-tests/blob/main/.agents/skills/release-sanity/SKILL.md) and [release QA method](https://github.com/synonymdev/bitkit-e2e-tests/blob/main/docs/release-qa.md). This means applying the agent instructions, not running a shell command or only printing a reminder. Load both files from `bitkit-e2e-tests` at one resolved default-branch commit (from a matching local checkout or GitHub) and record that revision. If unavailable, record `blocked — shared release-sanity method unavailable` and the follow-up needed; do not substitute an old checklist or claim completion.
- Supply the previous release tags/SHAs, intended platform scope, actual candidate SHAs/builds/artifact locations, version PRs and current automation/device evidence. Test jobs still running do not defer planning once candidates exist: mark the plan provisional, retain pending/failed/skipped evidence, and refresh affected coverage when results settle or candidate revisions change. Use the shared method for case selection rather than copying it here. Generating the plan does not run payments/device tests, launch reruns or approve the release.

**One plan per version:** first inspect both version PRs for an existing plan. Reuse its canonical location; otherwise put the plan in Android's version PR for a dual-platform or Android-only release, or iOS's version PR for an iOS-only release. Keep the proposed cases, pinned evidence and gaps in a managed `<!-- release-sanity:start -->` / `<!-- release-sanity:end -->` section under QA Notes. Link the other version PR's QA Notes to that canonical section instead of independently generating a second suite. Only include the intended platform(s); selecting a single-platform scope later must re-enter this handoff before Slack finalization.

Read the latest PR body before each edit, preserve other authored content and existing case IDs/results, and verify the edited body. New cases start `Not run`; when the candidate changes, preserve earlier results at their tested revisions and reassess affected cases. Mirror the plan locally in `.ai/release-sanity-{newVersionName}.md`, with the canonical PR URL. Re-entering from the other platform refreshes the same record, not a duplicate. Put the plan URL and planning status (`generated`, `generated — evidence pending`, `deferred` or `blocked`) in the local Slack draft and final summary. Planning status is separate from test execution status and release sign-off.

### 8b. Post Slack (both RCs, or rare single-platform hotfix)

**Both ready** (default) means all of the following (no `TODO` left in the Reply body):
- Android: APK is on the `v{newVersionName}` GH draft release (just uploaded), version-bump PR URL known, store-notes Google Doc URL known.
- iOS: `v{newVersionName}` tag/draft exists, matching build available to testers in TestFlight (not merely uploading/processing), version-bump PR URL known, store-notes Google Doc URL known when the iOS doc was created.

Refresh `.ai/slack-release-{newVersionName}.md` with finalized values and the plan link/status from step 8a. Skipping Slack or finding an existing announcement does not skip that handoff.

**If the sibling is not ready yet:** ask the user:
1. `Wait for sibling` (default) — print `⏸ Slack not posted — waiting until iOS {newVersionName} is on TestFlight (and its doc/PR are known). Local draft: .ai/slack-release-{newVersionName}.md`. Do not call Slack. Continue the release.
2. `Single-platform hotfix` (rare) — rewrite the Reply to **Android-only**: drop iOS RC / doc / PR lines (do not leave `TODO`); open with a clear Android-only/hotfix cue in the first line of the reply (e.g. `Android-only hotfix:`). Then post per the steps below.
3. `Skip Slack` — print a manual reminder and continue.

Before drafting a reply, recheck step 8a if candidate availability or the intended platform scope changed in this Slack step (including selecting a single-platform hotfix). Generate/update the plan when eligible; otherwise retain the explicit deferred/blocked reason. Do not describe planning as completed without its record.

**If ready to post** (both platforms filled, or user chose single-platform hotfix): post as Slack drafts (one attached draft per channel at a time). **Never call `slack_send_message`** — it always appends `Sent using Cursor`. Use `slack_send_message_draft` only; the user must send each draft **from the Slack app** (Cursor widget also adds the footer).

1. Locate the parent with `slack_read_channel` on `C07BJ7DNPCG` (`slack_read_channel` returns **top-level messages only** — not reply bodies). If `Release \`{newVersionName}\` :thread:` is already posted and does **not** include `Sent using`, reuse its `ts` and skip to step 4. Otherwise create the **parent** draft in `#bitkit-native` (`channel_id`: `C07BJ7DNPCG`) with body exactly: `Release \`{newVersionName}\` :thread:`
2. Tell the user: send that draft **from the Slack app** (not the Cursor widget), then confirm when done — or proceed to poll.
3. Poll with `slack_read_channel` on `C07BJ7DNPCG` every **5 seconds** until that parent appears (no `Sent using`). Cap at ~2 minutes. If not found after the cap: ask the user whether the parent was posted (`Yes, continue` / `Skip Slack and continue`). On Yes, locate the parent once more; on Skip, print a manual-post reminder and continue the release (do not keep polling).
4. **Dedup before drafting a reply.** Call `slack_read_thread` with `channel_id` `C07BJ7DNPCG` and `message_ts` set to the parent `ts` from steps 1–3. Inspect reply bodies:
   - Complete dual-platform reply already exists (no `TODO`, finalized Android **and** iOS RC / doc / PR lines) → skip Slack; print that the announcement is already posted.
   - Existing reply is a single-platform hotfix for the **same** platform as this run (`Android-only hotfix:` here, or only this platform's RC line) → skip.
   - Existing reply is a single-platform hotfix for the **other** platform (or only one RC line) and this run is dual-ready → do **not** treat it as dual-complete; continue to step 5 with the dual-platform Reply body.
   - No reply yet → continue to step 5.
5. Create the **reply** draft with `thread_ts` set to that parent's message `ts`, body = the `## Reply` section from `.ai/slack-release-{newVersionName}.md` (markdown body only, no headings). The body must contain **no** `TODO`.
6. Tell the user: open the thread in `#bitkit-native` and send the reply draft **from Slack**.

**Fallbacks (never block the release):** Slack MCP unavailable → print `⚠ Slack draft not created — post manually from .ai/slack-release-{newVersionName}.md when ready` and continue.

### 9. Return to Master

```bash
git checkout master
```

### 10. Required Release Handoffs

The sanity-planning handoff in step 8a runs once the intended candidates are available, before Slack finalization, even if Slack is skipped. If deferred, resume that handoff when the missing candidate becomes available. The follow-ups below happen after their own milestones; do not run them early just because the release branch and tag exist.

1. After the Android and iOS `release-{newVersionName}` branches are staging or migration green, switch to `bitkit-e2e-tests` and run:

   ```text
   /archive-release {newVersionName}
   ```

   This archives the validated regtest APK and iOS simulator build. In the same E2E follow-up, update the released platform entry in `config/migration-baselines.json` to `{newVersionName}` and record the uploaded asset checksum in `config/migration-checksums.json`. Open and merge that E2E change so the next release tests migration from the actual previous release.

2. After the store release is live, switch to the shared `bitkit` repository and run `/updater` for Android (or Both when both platforms shipped). Confirm the published updater JSON before considering the release complete.

### 11. Output Summary

```
Release v{newVersionName} (build {newVersionCode})

Version bump PR: {PR URL}
Release branch: release-{newVersionName}
Tag: v{newVersionName}
Draft release: {release URL}
APK uploaded: bitkit-mainnet-release-{newVersionCode}-universal.apk
Native debug symbols uploaded: native-debug-symbols-{newVersionCode}.zip
Store release notes: .ai/release-notes-{newVersionName}.md
Store-note checks: .ai/store-notes-check-{newVersionName}.md ({generation status}; store readback {pending | verified})
Release notes doc: {Google Doc URL or "(not created)"} ({sharing status from 6b})
Slack draft: .ai/slack-release-{newVersionName}.md ({posted parent+reply | deferred — waiting for sibling RC | single-platform hotfix posted | already posted | skipped})

Sanity plan: {canonical PR QA Notes URL or pending reason} ({generated | generated — evidence pending | deferred | blocked})
Local plan: .ai/release-sanity-{newVersionName}.md (only when generated)

Next steps:
- If sanity planning is deferred/blocked: resume step 8a once candidates/method are available; apply `/release-sanity {newVersionName}` without re-cutting the release
- Refresh provisional sanity coverage when pending test results settle or candidate revisions change
- If Slack was deferred: post once the sibling RC is ready, or choose single-platform hotfix if this cut ships alone (no TODOs in the reply)
- Send any #bitkit-native Slack drafts from the Slack app (if not already)
- Share the release notes doc with Jacobo for review (if domain share is still pending, apply it first)
- QA the APK
- If patching the release branch: increment only versionCode, re-tag, rebuild, and re-upload
- Submit to Play Store when QA passes; before each authorized submission/resubmission, complete the shared [store release-note checks](https://github.com/synonymdev/bitkit-e2e-tests/blob/main/docs/store-release-notes.md), verify every configured console locale against approved notes, and resolve pending content/policy checks. Record the selected build/release policy and actual readback in `.ai/store-notes-check-{newVersionName}.md`
- Publish the draft release on GitHub after store release
- Merge release branch PR into master
- After staging/migration is green: run `/archive-release {newVersionName}` from `bitkit-e2e-tests`, then update its migration baseline and checksum
- After the store release is live: run `/updater` from the shared `bitkit` repository
```

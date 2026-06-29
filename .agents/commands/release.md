---
description: "Create a new release: bump version, create PR, build mainnet, tag, draft release"
allowed_tools: Bash, Read, Edit, Write, Glob, Grep, AskUserQuestion, mcp__github__create_pull_request, mcp__github__list_pull_requests, mcp__github__pull_request_read, mcp__github__get_file_contents, mcp__github__update_pull_request
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

### Preview

N/A

### QA Notes

N/A
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

Read the `## [{newVersionName}]` section from `CHANGELOG.md` as the primary source for release content. If that section is empty or was not created in Step 2c, fall back to fetching auto-generated release notes:

```bash
gh release view v{newVersionName} --json body --jq .body
```

Using the changelog entries (or auto-generated notes as fallback) as context, write a concise user-facing summary of the release (2-3 sentences max, no commit hashes or PR numbers, written for end users not developers). Focus on new features and important bug fixes. Omit chores, maintenance, refactoring, CI changes, and test coverage improvements — these are not relevant to Play Store users. Translate the summary into 5 languages.

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

Then prepend the English summary to the draft release body on GitHub:

```bash
# Write store summary via heredoc (avoids shell expansion of apostrophes, $, backticks)
cat > /tmp/release-notes.md <<'NOTES_EOF'
## Store Release Notes

{english summary}

---

NOTES_EOF
# Append existing auto-generated notes
gh release view v{newVersionName} --json body --jq .body >> /tmp/release-notes.md
gh release edit v{newVersionName} --notes-file /tmp/release-notes.md
```

Print the path to the release notes file so the user can share it for review.

### 7. Build Mainnet Release

```bash
just release
```

Expected APK path: `app/build/outputs/apk/mainnet/release/bitkit-mainnet-release-{newVersionCode}-universal.apk`
Expected AAB path: `app/build/outputs/bundle/mainnetRelease/bitkit-mainnet-release-{newVersionCode}.aab`
Expected native debug symbols path: `app/build/outputs/native-debug-symbols/mainnetRelease/native-debug-symbols-{newVersionCode}.zip`

Verify all three files exist. The native debug symbols file must be from the same `just release` build as the APK/AAB. Keep the build-numbered filename, e.g. `native-debug-symbols-{newVersionCode}.zip`, so it matches the APK/AAB build number. `just release` resolves upstream native debug symbol artifacts from the Rust dependency packages, merges them into the final archive, and refuses placeholder symbols from stripped packaged `.so` files.

### 8. Upload APK and Native Symbols to Draft Release

```bash
gh release upload v{newVersionName} \
  app/build/outputs/apk/mainnet/release/bitkit-mainnet-release-{newVersionCode}-universal.apk \
  app/build/outputs/native-debug-symbols/mainnetRelease/native-debug-symbols-{newVersionCode}.zip
```

For the Play Store release, upload the AAB as usual, then upload `native-debug-symbols-{newVersionCode}.zip` for the exact version/build in Play Console: App bundle explorer → Downloads → Assets. Verify Play lists the native debug symbols after upload. Keep the release-built archive in GitHub releases or internal release storage; Play Console may only show delete/replace controls after upload, which is enough for release verification.

### 9. Return to Master

```bash
git checkout master
```

### 10. Output Summary

```
Release v{newVersionName} (build {newVersionCode})

Version bump PR: {PR URL}
Release branch: release-{newVersionName}
Tag: v{newVersionName}
Draft release: {release URL}
APK uploaded: bitkit-mainnet-release-{newVersionCode}-universal.apk
Native debug symbols uploaded: native-debug-symbols-{newVersionCode}.zip
Store release notes: .ai/release-notes-{newVersionName}.md

Next steps:
- Share release notes with Jacobo for review
- QA the APK
- If patching the release branch: increment only versionCode, re-tag, rebuild, and re-upload
- Submit to Play Store when QA passes
- Publish the draft release on GitHub after store release
- Merge release branch PR into master
```

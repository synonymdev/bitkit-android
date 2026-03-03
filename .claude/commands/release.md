---
description: "Create a new release: bump version, create PR, build mainnet, publish draft release"
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

### 3. Create Release Branch & Bump Version

```bash
git checkout master
git pull origin master
git checkout -b release/{newVersionCode}
```

Edit `app/build.gradle.kts`:
- Change `versionCode = {old}` to `versionCode = {newVersionCode}`
- Change `versionName = "{old}"` to `versionName = "{newVersionName}"`

```bash
git add app/build.gradle.kts
git commit -m "chore: version {newVersionName}"
git push -u origin release/{newVersionCode}
```

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

### 5. Build Mainnet Release

```bash
./gradlew assembleMainnetRelease
```

Expected APK path: `app/build/outputs/apk/mainnet/release/bitkit-mainnet-release-{newVersionCode}-universal.apk`

Verify the file exists. If the build fails, stop and report the error to the user.

### 6. Tag & Push

Determine the previous version tag for changelog generation: `v{oldVersionName}`.

```bash
git tag -a v{newVersionName} -m "v{newVersionName}"
git push origin v{newVersionName}
```

### 7. Create Draft GitHub Release

```bash
gh release create v{newVersionName} \
  --title "v{newVersionName}" \
  --draft \
  --generate-notes \
  --notes-start-tag v{oldVersionName}
```

### 8. Upload APK to Draft Release

```bash
gh release upload v{newVersionName} \
  app/build/outputs/apk/mainnet/release/bitkit-mainnet-release-{newVersionCode}-universal.apk
```

### 9. Return to Master

```bash
git checkout master
```

### 10. Output Summary

```
Release v{newVersionName} (build {newVersionCode})

Version bump PR: {PR URL}
Release branch: release/{newVersionCode}
Tag: v{newVersionName}
Draft release: {release URL}
APK uploaded: bitkit-mainnet-release-{newVersionCode}-universal.apk

Next: publish the draft release on GitHub when ready.
```

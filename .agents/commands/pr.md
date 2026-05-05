---
description: "/pr [base] [--dry] [--draft] [-- instructions] — Create a PR on GitHub"
argument_hint: "[branch] [--dry] [--draft] [-- instructions]"
allowed_tools: Bash, Read, Glob, Grep, Write, AskUserQuestion, mcp__github__create_pull_request, mcp__github__list_pull_requests, mcp__github__get_file_contents, mcp__github__issue_read
---

Create a PR on GitHub using the `gh` CLI for the currently checked-out branch.

**Examples:**
- `/pr` - Interactive mode, prompts for PR type
- `/pr master` - Interactive with explicit base branch
- `/pr --dry` - Generate description only, save to `.ai/`
- `/pr --draft` - Create as draft PR
- `/pr develop --draft` - Draft PR against non-default branch
- `/pr -- focus on commit abc123` - With custom instructions for description
- `/pr master --draft -- describe migration test in QA` - Combined with all options

## Steps

### 1. Check for Existing PR
Run `gh pr view --json number,url 2>/dev/null` to check if a PR already exists for this branch.
- If PR exists: Output `PR already exists: [URL]` and stop
- If no PR: Continue

### 2. Parse Arguments
- `--dry`: Skip PR creation, only generate and save description
- `--draft`: Create PR as draft
- First non-flag argument: base branch (default: auto-detected, see Step 2.5)
- Everything after `--` separator (if present): Custom instructions for PR generation
- **If no flags provided**: Use `AskUserQuestion` to prompt user:
  - Open PR (create and publish)
  - Draft PR (create as draft)
  - Dry run (save locally only)

### 2.5. Determine Base Branch
If no base branch argument provided, detect the repo's default branch:
- Run: `git symbolic-ref refs/remotes/origin/HEAD | sed 's@^refs/remotes/origin/@@'`
- Use result as default (typically `main` or `master`)
- If command fails, fall back to `master`

### 3. Gather Context
- Get current branch name: `git branch --show-current`
- Extract repo identifier: `git remote get-url origin | sed 's/\.git$//' | sed -E 's#.*[:/]([^/]+/[^/]+)$#\1#'` (e.g., `synonymdev/bitkit-android`)
- Read PR template from `.github/pull_request_template.md`
- Fetch 10 most recent PRs (open or closed) from the extracted repo for writing style reference
- Run `git log $base..HEAD --oneline` for commit messages
- Run `git diff $base...HEAD --stat` for understanding scope of changes
- **If custom instructions provided:**
  - If instructions reference a specific commit SHA (pattern like `commit [a-f0-9]{7,40}`):
    - Read full commit message: `git log -1 --format='%B' <commit_sha>`
  - Store instructions for use in description generation
- **Read instruction files from `.ai/pr/`:**
  - Scan `.ai/pr/` for markdown files: `ls .ai/pr/*.md 2>/dev/null`
  - Read each `.md` file found (using the Read tool)
  - Treat their contents as supplementary instructions for description generation, merged with any `--` instructions
  - These file-based instructions follow the same priority rules as custom `--` instructions (see Step 6, "Custom Instructions")
- **Scan for media files in `.ai/pr/`:**
  - List non-markdown files: `ls .ai/pr/* 2>/dev/null | grep -vE '\.md$'`
  - Supported types: `.png`, `.jpg`, `.jpeg`, `.gif`, `.mp4`, `.mov`, `.webm`, `.webp`
  - Store the list of found media files with their paths for use in Preview section (Step 6)

### 4. Extract Linked Issues
Scan commits for issue references:
- Pattern to match: `#123` (just the issue number reference)
- Extract unique issue numbers: `git log $base..HEAD --oneline | grep -oE "#[0-9]+" | sort -u`
- Fetch each issue title: `gh api "repos/$REPO/issues/NUMBER" --jq '.title'` (using repo from Step 3)
- These will be used to start the PR description with linking keywords (see Step 6)

### 5. Identify Suggested Reviewers
Find potential reviewers based on:
- `.github/CODEOWNERS` file patterns (if exists)
- Recent contributors to changed files: `git log --format='%an' -- $(git diff $base..HEAD --name-only) | sort | uniq -c | sort -rn | head -3`
- Exclude the current user from suggestions

### 6. Generate PR Description
Starting from the template in `.github/pull_request_template.md`:

**Title Rules:**
- Format: `prefix: title` (e.g., `feat: add user settings screen`)
- Keep under 50 characters
- Use branch name as concept inspiration
- Prefixes: `feat`, `fix`, `chore`, `refactor`, `docs`, `test`

**Issue Linking (at the very start):**
If linked issues were found in commit messages, begin the PR description with linking keywords:
- Use `Fixes #123` for bug fixes
- Use `Closes #123` for features/enhancements
- One per line, before the "This PR..." opening separated by one empty line
- Reference: https://docs.github.com/en/get-started/writing-on-github/working-with-advanced-formatting/using-keywords-in-issues-and-pull-requests

Example:
```
Fixes #528
Closes #418

This PR adds support for...
```

**Opening Format:**
- Single change: Start with "This PR [verb]s..." as a complete sentence
  - Example: `This PR adds a Claude Code /pr command for generating PRs.`
- Multiple changes: Start with "This PR:" followed by a numbered list
  - Example:
    ```
    This PR:
    1. Adds a Claude Code /pr command for generating PRs
    2. Fixes issue preventing Claude Code reviews to be added as PR comments
    3. Updates reviews workflow to minimize older review comments
    ```
- Each list item should start with a verb (Adds, Fixes, Updates, Removes, Refactors, etc.)

**Description Rules:**
- Base content around all commit messages in the branch
- Use branch name as the conceptual anchor
- Match writing style of recent PRs
- Focus on functionality over technical details
- Avoid excessive bold formatting like `**this:** that`
- Minimize code and file references like `TheClassName` or `someFunctionName`, `thisFileName.ext`
- Exception: for refactoring PRs (1:10 ratio of functionality to code changes), more technical detail is ok

**Custom Instructions (if provided):**
When the user provides custom instructions after `--`:
- Parse any referenced commit SHAs and read their full messages
- Focus the description content on areas the user emphasizes
- Structure QA Notes according to user's specific manual testing instructions and automated coverage notes
- Custom instructions take priority over default generation rules for sections they address
- Preserve exact manual testing steps provided by the user (don't summarize or omit details)
- If custom instructions include automated checks, place them under `#### Automated Checks`

**QA Notes / Validation:**
- QA Notes separate actionable human QA instructions from automated verification coverage.
- Always use this structure:
  ```md
  ### QA Notes
  #### Manual Tests
  #### Automated Checks
  ```
- Keep local verification commands, Gradle tasks, detekt, lint, unit tests, build passes, cargo test, cargo clippy, npm test, typecheck, CI coverage, or similar automated checks out of `#### Manual Tests`; list them under `#### Automated Checks`.
- Use `#### Automated Checks` to list automated checks that were run locally and any automated coverage added or updated with the change, such as unit tests, integration tests, or CI checks.
- For workflow behavior validation, include `(after merge)` in the automated check item because workflow changes only take effect for PRs opened after the workflow update merges.
- If no actionable manual validation exists, write `N/A` under `#### Manual Tests`.
- If no automated checks were run and no automated coverage changed, write `N/A` under `#### Automated Checks`.
- Write manual tests using this template:
  ```md
  - [ ] **{numbering}.** {optional_condition + →} {screen_action} → {next_screen_action}: expectation
  ```
- Use a list of unchecked checkboxes for each individual test.
- Use a numbered prefix for each test, in bold, for example `**1.**`, `**2.**`.
- Use `regression:` for regression checks, positioned after the numbering.
- Use sub-lists for variations of the same test.
- Use letter suffixes in numbering for each variation when a test has a sub-list, for example `**3a.**`, `**3b.**`.
- Always use `→` to denote navigation, for example `Send → Amount`.
- Use screen names from code, formatted as separate words without the `Screen` suffix, for example `SendAmountScreen` becomes `Send Amount`.
- Use short-form wording like `in-sheet` for sheet screens, `nav` for navigation, `back` for back nav, and `LN` for Lightning Network.

**For library repos (has `bindings/` directory or `Cargo.toml`):**
Structure manual QA around integration validation only. Automated checks belong under `#### Automated Checks`.

Example:
```
### QA Notes
#### Manual Tests
- [ ] **1.** Consumer app → exercise updated binding flow: behavior matches previous release.
- [ ] **2.** `regression:` Android integration screen → trigger changed API path: no crash or stale data.
#### Automated Checks
- [x] cargo test
- [x] Android binding integration tests
```

Concrete style target:
```md
### QA Notes
#### Manual Tests
- [ ] **1.** No usable channels/spending balance → scan LN invoice: error shows immediately, not after 15s.
- [ ] **2.** Scanner → scan fixed amount LN invoice: Send Confirm or QuickPay opens directly.
- [ ] **3a.** `regression:` Send → scanner/paste fixed amount LN invoice: in-sheet nav to Confirm or QuickPay.
  - [ ] **3b.** `regression:` Variable amount LN invoice/LNURL-pay: lands on Amount view.
- [ ] **4a.** Activity Detail of LN transfer → tap Connection: lands on Channel Detail.
  - [ ] **4b.** back: returns to Activity Detail.
- [ ] **5a.** Settings → Lightning Connections → tap channel: still opens Channel Detail.
  - [ ] **5b.** back: returns to Connections List.
- [ ] **6.** `regression:` Channel Detail → tap Close Connection: works.
#### Automated Checks
- [x] ./gradlew compileDevDebugKotlin
- [x] ./gradlew testDevDebugUnitTest
- [x] ./gradlew detekt
```

**Preview Section (conditional):**
Only include if the PR template (`.github/pull_request_template.md`) contains a `### Preview` heading:

- **If media files were found in `.ai/pr/`:**
  - For each media file, add a labeled markdown placeholder in the Preview section
  - Use format: `<!-- MEDIA: .ai/pr/filename.ext — Upload this file here via GitHub web UI -->` as a marker
  - Add a brief description comment based on the filename (e.g., `pay2blink.mp4` -> "Pay to Blink flow recording")
  - Example output:
    ```
    ### Preview

    <!-- MEDIA: .ai/pr/pay2blink.mp4 — Upload this file here via GitHub web UI -->
    ```

- **If no media files found in `.ai/pr/`:**
  - Fall back to generated placeholders: `IMAGE_1`, `VIDEO_2`, etc.
  - Add code comment under each placeholder describing what it should show
  - Example: `<!-- VIDEO_1: Record the send flow by scanning a LN invoice and setting amount to 5000 sats -->`

### 7. Save PR Description
Before creating the PR:
- Get next PR number: `gh api "repos/$REPO/issues?per_page=1&state=all&sort=created&direction=desc" --jq '.[0].number'` then add 1 (using repo from Step 3)
- Create `.ai/` directory if it doesn't exist
- Save to `.ai/pr_NN.md` where `NN` is the predicted PR number

### 8. Create the PR (unless --dry)
If not dry run:
```bash
gh pr create --base $base --title "..." --body "..." [--draft]
```
- Add `--draft` flag if draft mode selected
- If actual PR number differs from predicted, rename the saved file
- **If media files exist in `.ai/pr/`:**
  - Output the PR edit URL for easy access (append `/edit` to the PR URL)
  - Instruct the user to drag-and-drop each media file into the Preview section via the GitHub web UI
  - Note: GitHub does not support programmatic media upload to PR bodies

### 8b. Backfill Changelog PR Number

If the PR was created (not dry run) and `CHANGELOG.md` was modified in the branch (`git diff $base...HEAD --name-only | grep CHANGELOG.md`):
- Read `CHANGELOG.md` and scan `## [Unreleased]` entries for lines missing a `#NUMBER` suffix
- Append the actual PR number to those entries, e.g. `- Add foo` becomes `- Add foo #123`
- If any entries were updated, create a new commit with message `chore: backfill changelog pr number` and push

### 9. Output Summary

**If PR created:**
```
PR Created: [PR URL]
Saved: .ai/pr_NN.md

Suggested reviewers:
- @username1 (X files modified recently)
- @username2 (CODEOWNER)
```

**If dry run:**
```
Dry run complete
Saved: .ai/pr_NN.md

To create PR: /pr [--draft]

Suggested reviewers:
- @username1 (X files modified recently)
- @username2 (CODEOWNER)
```

**Media TODOs (only if Preview section was included):**
If the PR description includes a Preview section, append media action items:

If media files were found in `.ai/pr/`:
```
Media to upload (drag-and-drop into Preview section on GitHub):
- .ai/pr/pay2blink.mp4
- .ai/pr/screenshot.png
```

If no media files were found (generated placeholders):
```
## TODOs
- [ ] IMAGE_1: [description]
- [ ] VIDEO_2: [description]
```
List all media placeholders as TODOs with their descriptions.

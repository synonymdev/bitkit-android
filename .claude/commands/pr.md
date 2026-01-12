---
description: Create a PR on GitHub for the current branch
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
- Structure QA Notes according to user's specific testing instructions
- Custom instructions take priority over default generation rules for sections they address
- Preserve exact testing steps provided by the user (don't summarize or omit details)

**QA Notes / Testing Scenarios:**
- Structure with numbered headings and steps
- Make steps easily referenceable
- Be specific about what to test and expected outcomes

**For library repos (has `bindings/` directory or `Cargo.toml`):**
Structure QA Notes around testing and integration:

Example:
```
### QA Notes

#### Testing
- [ ] `cargo test` passes
- [ ] `cargo clippy` clean
- [ ] Android bindings: `./build_android.sh`
- [ ] iOS bindings: `./build_ios.sh`

#### Integration
- Tested in: [bitkit-android#XXX](link)
- Or N/A if internal refactor with no API changes
```

**Preview Section (conditional):**
Only include if the PR template (`.github/pull_request_template.md`) contains a `### Preview` heading:
- Create placeholders for media: `IMAGE_1`, `VIDEO_2`, etc.
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
If the PR description includes a Preview section with media placeholders, append:
```
## TODOs
- [ ] IMAGE_1: [description]
- [ ] VIDEO_2: [description]
```
List all media placeholders as TODOs with their descriptions.

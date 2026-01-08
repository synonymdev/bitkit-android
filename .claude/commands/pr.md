---
description: Create a PR on GitHub for the current branch
argument_hint: "[branch] [--dry] [--draft]"
allowed_tools: Bash, Read, Glob, Grep, Write, AskUserQuestion, mcp__github__create_pull_request, mcp__github__list_pull_requests, mcp__github__get_file_contents
---

Create a PR on GitHub using the `gh` CLI for the currently checked-out branch.

**Examples:**
- `/pr` - Interactive mode, prompts for PR type
- `/pr master` - Interactive with explicit base branch
- `/pr --dry` - Generate description only, save to `.ai/`
- `/pr --draft` - Create as draft PR
- `/pr develop --draft` - Draft PR against develop branch

## Steps

### 1. Check for Existing PR
Run `gh pr view --json number,url 2>/dev/null` to check if a PR already exists for this branch.
- If PR exists: Output `PR already exists: [URL]` and stop
- If no PR: Continue

### 2. Parse Arguments
- `--dry`: Skip PR creation, only generate and save description
- `--draft`: Create PR as draft
- First non-flag argument: base branch (default: `master`)
- **If no flags provided**: Use `AskUserQuestion` to prompt user:
  - Open PR (create and publish)
  - Draft PR (create as draft)
  - Dry run (save locally only)

### 3. Gather Context
- Get current branch name: `git branch --show-current`
- Read PR template from `.github/pull_request_template.md`
- Fetch 10 most recent PRs (open or closed) from `synonymdev/bitkit-android` for writing style reference
- Run `git log $base..HEAD --oneline` for commit messages
- Run `git diff $base...HEAD --stat` for understanding scope of changes

### 4. Generate PR Description
Starting from the template in `.github/pull_request_template.md`:

**Title Rules:**
- Format: `prefix: title` (e.g., `feat: add user settings screen`)
- Keep under 50 characters
- Use branch name as concept inspiration
- Prefixes: `feat`, `fix`, `chore`, `refactor`, `docs`, `test`

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
- Minimize code references like `TheClassName` or `someFunctionName`
- Exception: for refactoring PRs (1:10 ratio of functionality to code changes), more technical detail is ok

**QA Notes / Testing Scenarios:**
- Structure with numbered headings and steps
- Make steps easily referenceable
- Be specific about what to test and expected outcomes

**Preview Section:**
- Create placeholders for media: `IMAGE_1`, `VIDEO_2`, etc.
- Add code comment under each placeholder describing what it should show
- Example: `<!-- VIDEO_1: Record the send flow by scanning a LN invoice and setting amount to 5000 sats -->`

### 5. Save PR Description
Before creating the PR:
- Get next PR number: `gh api "repos/synonymdev/bitkit-android/issues?per_page=1&state=all&sort=created&direction=desc" --jq '.[0].number'` then add 1
- Create `.ai/` directory if it doesn't exist
- Save to `.ai/pr_NN.md` where `NN` is the predicted PR number

### 6. Create the PR (unless --dry)
If not dry run:
```bash
gh pr create --base $base --title "..." --body "..." [--draft]
```
- Add `--draft` flag if draft mode selected
- If actual PR number differs from predicted, rename the saved file

### 7. Output Summary

**If PR created:**
```
PR Created: [PR URL]
Saved: .ai/pr_NN.md

## TODOs
- [ ] IMAGE_1: [description]
- [ ] VIDEO_2: [description]
```

**If dry run:**
```
Dry run complete
Saved: .ai/pr_NN.md

To create PR: /pr [--draft]

## TODOs
- [ ] IMAGE_1: [description]
- [ ] VIDEO_2: [description]
```

List all media placeholders as TODOs with their descriptions.

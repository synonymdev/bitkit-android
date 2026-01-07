---
description: Create a PR on GitHub for the current branch
argument-hint: [branch=master]
allowed-tools: Bash, Read, Glob, Grep, Write, mcp__github__list_pull_requests, mcp__github__create_pull_request
---

Create a PR on GitHub using the `gh` CLI for the currently checked-out branch by diffing it vs. the reference branch named `$1` (default: `master` if not provided).

## Steps

### 1. Gather Context
- Get current branch name: `git branch --show-current`
- Use base branch from argument or default to `master`
- Read PR template from `.github/pull_request_template.md`
- Fetch 10 most recent PRs (open or closed) from `synonymdev/bitkit-android` for writing style reference
- Run `git log $base..HEAD --oneline` for commit messages
- Run `git diff $base...HEAD --stat` for understanding scope of changes

### 2. Generate PR Description
Starting from the template in `.github/pull_request_template.md`:

**Title Rules:**
- Format: `prefix: title` (e.g., `feat: add user settings screen`)
- Keep under 45 characters
- Use branch name as concept inspiration
- Prefixes: `feat`, `fix`, `chore`, `refactor`, `docs`, `test`, `style`

**Description Rules:**
- Base content around all commit messages in the branch
- Use branch name as the conceptual anchor
- Match writing style of recent PRs
- Focus on functionality over technical details
- Avoid excessive bold formatting like `**this:** that`
- Minimize code references like `TheClassName` or `someFunctionName`
- Exception: for refactoring PRs (1:10 ratio of functionality to code changes), more technical detail is acceptable

**QA Notes / Testing Scenarios:**
- Structure with numbered headings and steps
- Make steps easily referenceable
- Be specific about what to test and expected outcomes

**Preview Section:**
- Create placeholders for media: `IMAGE_1`, `VIDEO_2`, etc.
- Add code comment under each placeholder describing what it should show
- Example: `<!-- VIDEO_1: Record the send flow by scanning a LN invoice and setting amount to 5000 sats -->`

### 3. Create the PR
Use `gh pr create` with the generated title and body:
```bash
gh pr create --base $base --title "..." --body "..."
```

### 4. Save PR Description
After creating the PR:
- Create `.ai/` directory if it doesn't exist
- Save the generated description to `.ai/pr_NN.md` where `NN` is the PR number from GitHub

### 5. Output Summary

**Format:**
```
PR Created: [PR URL]

Generated file: /absolute/path/to/.ai/pr_NN.md

## TODOs for you:
- [ ] IMAGE_1: [description of what to capture]
- [ ] VIDEO_2: [description of what to record]
...
```

List all media placeholders as TODOs with their descriptions so the user knows exactly what screenshots/recordings to add.

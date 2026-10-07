# GitHub Copilot Code Review Instructions

When performing a code review, respond in English.

## Draft PRs

Check the PR's current draft status before starting and before publishing a review. Skip draft PRs
and wait until they are ready for review, following `AGENTS.md` under Draft status and review
readiness. This guidance does not itself configure or disable external review automation.

## Review summary metadata

Follow `AGENTS.md` under "Review summary metadata": end the review summary with
`Reviewer model: <model name>` and `Reasoning effort: <effort>` on separate lines, once per review
(including no-findings reviews), not on each inline comment. Check available session/run metadata
for the actual model and effort; use `Unknown` if unrecorded or `Not exposed` if a reasoning setting
is not exposed. Do not infer values from the tool name or copy the implementation model. If multiple
model/effort combinations contributed, list each pair. This is informational and does not replace
the PR's Models used section. Unassisted human reviews do not need this footer.

## Architecture & Patterns

When performing a code review, ensure ViewModels are never injected as dependencies into services or repositories. Only Android activities and composable functions should use ViewModels.

When performing a code review, verify that all async operations use `viewModelScope.launch` instead of `GlobalScope.launch` in ViewModels.

When performing a code review, ensure Repository pattern is followed

When performing a code review, verify StateFlow is used for reactive state management in ViewModels with proper `_uiState` and `uiState` pattern.

## Error Handling & Safety

When performing a code review, flag any use of the not-null assertion operator (`!!`) and suggest safe calls (`?.`) with proper null handling.

When performing a code review, ensure all coroutine operations use proper error handling with `runCatching`

## UI & Compose Best Practices

When performing a code review, ensure all user-facing strings use `stringResource(R.string.*)` instead of hardcoded strings.

When performing a code review, verify that expensive Compose computations are wrapped in `remember` blocks.

When performing a code review, check that Material3 design guidelines are followed for UI components.

When performing a code review, ensure proper state management patterns with `MutableStateFlow` and `StateFlow`.

When performing a code review, check if modifiers, if present, are in last place in args list

## Code Quality & Readability

When performing a code review, focus on readability and avoid nested if-else, replacing with early return wherever possible.

When performing a code review, ensure unused code is removed after refactoring.

When performing a code review, verify that existing extensions and utilities are used rather than creating duplicate functionality.

## Dependency Injection & Services

When performing a code review, verify proper Hilt dependency injection patterns are followed.

When performing a code review, ensure services contain business logic and don't directly depend on ViewModels.

## Build & Testing

When performing a code review, suggest Unit Test for composable components and business logic covering the most important cases

## Lightning & Bitcoin Specific

When performing a code review, verify that Bitcoin/Lightning operations are properly handled in the service layer.

When performing a code review, verify that propper Bitcoin and Lightning technical terms are used when naming code components

## Performance & Memory

When performing a code review, check for potential memory leaks in coroutines and ensure proper scope usage.

When performing a code review, verify that database operations use Room patterns correctly.

When performing a code review, ensure network operations use Ktor patterns and proper error handling.

## Documentation & Maintenance

When performing a code review, ensure code follows the established patterns from the existing codebase.

When performing a code review, verify that complex business logic is properly documented.

When performing a code review, check that new features integrate well with the existing MVVM architecture.

## Business Logic

When performing a code review on balance management logic, get context from `/docs/balance.md` file.

When performing a code review on transfer logic, get context from `/docs/transfer.md` file.

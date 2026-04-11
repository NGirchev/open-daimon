# Claude Code Rules for open-daimon

For general project rules, conventions, and architecture guidelines see [AGENTS.md](AGENTS.md).

## Critical Rules

- NEVER run `git commit`, `git push`, `git stash pop`, `git reset`, `git rebase`, `git merge`, or `git cherry-pick` without explicit user request. Always ask first.
- Stay strictly within the scope of files and components the user specifies. Do not modify unrelated files, move test files, or refactor code outside the requested change.
- Do not introduce new dependencies or update `pom.xml` without asking.

## Debugging

- When the user provides logs, errors, or output and says they are current — trust them. Do not re-explore or second-guess the recency of provided information.
- Analyze the root cause BEFORE exploring the codebase. Do not explore aimlessly.
- Propose a fix targeting ONLY the specific file/component mentioned by the user.

## Java / Testing

- After modifying Java files, always run `./mvnw clean compile -pl <module>` before running tests.
- Run only the specific failing test, not the full suite, unless the user asks otherwise.
- After each edit, verify compilation passes before proceeding to the next change.
- When fixing a bug in a specific service (e.g. `TelegramUserPriorityService`), do NOT touch other services with similar names (e.g. `DefaultUserPriorityService`).
- Always use proper `import` statements for all types. Never use fully-qualified class names inline (e.g. `java.io.ByteArrayOutputStream`) — add an import and use the short name.

## Fixture Smoke Tests

- When changing logic related to a use case in `docs/usecases/`, run fixture tests: `./mvnw clean verify -pl opendaimon-app -am -Pfixture`
- Fixture tests are tagged with `@Tag("fixture")` and located in `opendaimon-app/src/it/java/.../it/fixture/`
- Use case → fixture test mapping:
  - `forwarded-message.md` → `ForwardedMessageFixtureIT`
  - `auto-mode-model-selection.md` → `AutoModeModelSelectionFixtureIT`
  - `text-pdf-rag.md` → `TextPdfRagFixtureIT`
  - `image-pdf-vision-cache.md` → `ImagePdfVisionCacheFixtureIT`
- If a fixture test fails after your change, investigate and fix before proceeding.

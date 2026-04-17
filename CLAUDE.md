# Claude Code Rules for open-daimon

@AGENTS.md

## Critical Rules

- NEVER run `git commit`, `git push`, `git stash pop`, `git reset`, `git rebase`, `git merge`, or `git cherry-pick` without explicit user request. Always ask first.
- Stay strictly within the scope of files and components the user specifies. Do not modify unrelated files, move test files, or refactor code outside the requested change.
- Do not introduce new dependencies or update `pom.xml` without asking.

## Debugging

- **Always check application logs first** in `logs/` before guessing or speculating about issues. Read the logs, then analyze.
- Before analyzing logs or errors, review module and use case documentation loaded in context. If not loaded, read the corresponding `*_MODULE.md` from the module root. Understand the expected behavior from documentation before looking at code.
- When the user provides logs, errors, or output and says they are current — trust them. Do not re-explore or second-guess the recency of provided information.
- Analyze the root cause BEFORE exploring the codebase. Do not explore aimlessly.
- Propose a fix targeting ONLY the specific file/component mentioned by the user.
- **Escalation rule:** If the same issue persists after 2–3 fix attempts, STOP and ask the user for guidance. Do not keep guessing — the user likely has context you are missing.

## Java / Testing

- Run only the specific failing test, not the full suite, unless the user asks otherwise.
- When fixing a bug in a specific service (e.g. `TelegramUserPriorityService`), do NOT touch other services with similar names (e.g. `DefaultUserPriorityService`).
- Build/compile rules and style conventions live in `AGENTS.md` (sections `Build & Verification` and `Project Style Guide`).

## Fixture Smoke Tests

- When changing logic related to a use case in `docs/usecases/`, run fixture tests: `./mvnw clean verify -pl opendaimon-app -am -Pfixture`
- Fixture tests are tagged with `@Tag("fixture")` and located in `opendaimon-app/src/it/java/.../it/fixture/`
- Full use case -> test mapping and run instructions load automatically when working on fixture files.
- If a fixture test fails after your change, investigate and fix before proceeding.

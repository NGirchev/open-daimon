# Testcontainers Rules

> MANDATORY: Read `docs/testcontainers-plan.md` before making ANY changes to integration tests, manual tests, or `TestDatabaseConfiguration`/`AbstractContainerIT`.

## Before changing IT/manual tests

1. Read `docs/testcontainers-plan.md` — contains requirements, lessons learned, and anti-patterns
2. Check current state of `AbstractContainerIT` or `TestDatabaseConfiguration`
3. Verify `mvn clean verify` is green BEFORE and AFTER changes

## After changing IT/manual tests

1. Run `mvn clean verify -pl opendaimon-app -am` — must be green
2. Count postgres container starts in logs: `grep "Creating container for image: postgres:17.0"` — should be exactly 1
3. Verify no zombie containers: `docker ps -a --filter ancestor=postgres:17.0`
4. Update `docs/testcontainers-plan.md` with any new lessons learned

## Anti-patterns (NEVER do)

- Never use `.withReuse(true)` without explicit user approval
- Never create subclasses/delegates of `PostgreSQLContainer` for `@ServiceConnection`
- Never assume `@ServiceConnection` won't fall back to `application.yml` — always verify JDBC URL in logs
- Never combine `@Testcontainers`/`@Container` annotations with singleton pattern

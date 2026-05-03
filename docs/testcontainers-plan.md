# Testcontainers: Requirements, Plan & Lessons Learned

## Requirements

1. **One container** — singleton PostgreSQL container per JVM, not one per Spring context
2. **Tests connect to testcontainer** — NEVER fall back to `application.yml` datasource (`localhost:5432`)
3. **Each test gets unique DB** — `CREATE DATABASE testdb_<uuid>` per Spring context for data isolation
4. **Container dies at the end** — Ryuk cleans up after JVM exits, no zombie containers

## Lessons Learned (DO NOT REPEAT)

### 1. `.withReuse(true)` without env config = zombie containers
- `.withReuse(true)` in code makes `isShouldBeReused()` return `true`
- Ryuk skips cleanup for such containers
- But without `testcontainers.reuse.enable=true` in `~/.testcontainers.properties`, reuse doesn't actually work
- Result: containers NEVER get cleaned up — Testcontainers bug [#8323](https://github.com/testcontainers/testcontainers-java/issues/8323)
- **Rule: never use `.withReuse(true)` unless you explicitly need cross-JVM reuse AND accept manual cleanup**

### 2. `PostgreSQLContainerDelegate` breaks `@ServiceConnection`
- Creating a subclass of `PostgreSQLContainer` and returning it as `@ServiceConnection` bean does NOT work
- Spring Boot's `@ServiceConnection` checks the actual container state, not just getter methods
- When it doesn't recognize the delegate as a running container, it falls back to `application.yml` → `localhost:5432` → connects to docker-compose postgres instead of testcontainer
- **Rule: never create wrapper/delegate subclasses of Testcontainers containers for `@ServiceConnection`**

### 3. `@ServiceConnection` vs `@DynamicPropertySource`
- `@ServiceConnection` on a `@Bean` works only when the bean IS the real container object
- `@DynamicPropertySource` explicitly sets `spring.datasource.url/username/password` — no ambiguity, no fallback
- For singleton pattern with per-context databases, `@DynamicPropertySource` is the correct approach
- **Rule: use `@DynamicPropertySource` when you need to customize the JDBC URL (e.g., per-context DB)**

### 4. Singleton container pattern
- Official Testcontainers pattern: `static final` field + `static { container.start(); }` in abstract base class
- Ryuk automatically kills the container when JVM exits (~10 sec timeout)
- Do NOT combine with `@Testcontainers`/`@Container` annotations — they stop the container after the first test class
- Spring context caching still works: tests with identical config share one context (and one DB)

### 5. IDEA runs each test class in a separate JVM
- Singleton container is per-JVM, not per-IDEA-session
- 11 test classes from IDEA = 11 JVMs = 11 containers
- This is expected behavior, each container is cleaned by its own Ryuk
- To share one container across JVMs, need `withReuse(true)` (but then no auto-cleanup)

### 6. IDEA can run all tests in ONE JVM — `too many clients`
- When running a folder of tests from IDEA, all test classes run in a **single JVM**
- Each test class with unique config creates its own Spring context → own HikariCP pool
- Default HikariCP pool = 10 connections; default PostgreSQL max_connections = 100
- 11 contexts × 10 connections = 110 > 100 → `FATAL: sorry, too many clients already`
- **Fix options:**
  - A) Increase `max_connections` on container: `.withCommand("postgres", "-c", "max_connections=300")`
  - B) Reduce HikariCP pool size for tests: `spring.datasource.hikari.maximum-pool-size=5` in test properties
  - C) Both A and B for safety margin
- **Recommended: option C** — increase container limit to 300, reduce pool to 5. Supports up to 60 contexts.

### 7. Per-context database isolation is needed
- Different `@SpringBootTest(classes=...)` configs create different Spring contexts
- In Maven (one JVM), multiple contexts share one container
- Without separate databases, tests from different contexts pollute each other's data
- `@DataJpaTest` uses `@Transactional` rollback per method, but Flyway migrations commit immediately
- Parallel execution (`threadCount=2`) causes conflicts on unique constraints

## Implementation Plan

### Step 1: Create `AbstractContainerIT` — DONE

Abstract base class in `src/test/java/.../test/AbstractContainerIT.java`.

Contains:
- **PostgreSQL** — singleton static container, per-context UUID database
- **MinIO** — singleton static container, endpoint/credentials via `@DynamicPropertySource`
- `@DynamicPropertySource` sets `spring.datasource.*` and `open-daimon.common.storage.minio.*`

Key decisions:
- No `.withReuse(true)` → Ryuk cleans up
- `@DynamicPropertySource` → never falls back to `application.yml`
- UUID database name → full isolation between contexts
- Static singletons → one postgres + one minio per JVM

### Step 2: Migrate all IT tests

For each of the 38 test files that use `@Import(TestDatabaseConfiguration.class)`:
1. Add `extends AbstractContainerIT`
2. Remove `TestDatabaseConfiguration.class` from `@Import`
3. If `@Import` is empty after removal, remove the annotation
4. Replace `import ...TestDatabaseConfiguration` with `import ...AbstractContainerIT`

Groups:
- `@DataJpaTest` repository tests (7 files) — keep `@AutoConfigureTestDatabase(replace = NONE)`
- `@SpringBootTest` config/smoke tests (2 files)
- `@SpringBootTest` telegram tests (4 files)
- `@SpringBootTest` springai tests (2 files)
- `@SpringBootTest` fixture tests (3 files + 2 without TestDatabaseConfiguration)
- `@SpringBootTest` manual ollama tests (~10 files)
- `@SpringBootTest` manual openrouter tests (~10 files)

### Step 3: Delete old code

- Delete `TestDatabaseConfiguration.java` (replaced by `AbstractContainerIT`)

### Step 4: Verify

1. `mvn clean verify` — must be green, check only 1 `Creating container for image: postgres:17.0` in logs
2. Run 3 manual tests from Maven — must be green
3. Run 1 manual test from IDEA — must be green, container must die after test

### Step 5: Fix `too many clients` — reduce HikariCP pool + share contexts

**Problem:** IDEA runs all manual tests in one JVM. Each test has its own `TestConfig` inner class
→ Spring sees them as different contexts → 11 HikariCP pools × 10 connections = 110 > PostgreSQL max_connections=100.

**Root cause:** 8 of 11 Ollama tests have **empty** `TestConfig {}` and identical annotations
(`@ActiveProfiles({"integration-test", "manual-ollama"})`, `properties = "open-daimon.agent.enabled=false"`).
Spring doesn't know they're identical because each references its own inner class.
Same pattern for OpenRouter tests.

**Fix — two parts:**

#### Part A: Reduce HikariCP pool size for tests (safety net)

Add to `AbstractContainerIT.configureProperties()`:
```java
registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
```
This limits each context to 2 connections. Even 30 contexts = 60 connections < 100.

#### Part B: Share contexts across manual tests (proper fix)

**Ollama tests — 3 groups by context config:**

| Group | Tests | `classes` | `properties` | `TestConfig` |
|-------|-------|-----------|--------------|-------------|
| **ollama-simple** (8 tests) | DocRag, GreekImageVision, ImagePdfVisionRag, ImagesWithTextPdfVisionRag, ObjectsImageVision, TextPdfRag, XlsRag, ConversationHistoryGateway | Shared `OllamaManualTestConfig` | `agent.enabled=false` | Empty `{}` |
| **ollama-agent** (1 test) | AgentMode | Own `TestConfig` (empty, but no `agent.enabled=false`) | none | Empty `{}` |
| **ollama-agent-webtools** (2 tests) | ConversationHistory, WebToolCalling | Shared `OllamaWebToolsManualTestConfig` | `agent.enabled=false` or `true` + WebTools bean | Has `WebTools` bean |

Wait — `ConversationHistoryOllamaManualIT` has `agent.enabled=true` and WebTools, while `WebToolCallingOllamaManualIT` has `agent.enabled=false` and WebTools. Different properties → different contexts even with same TestConfig. Keep separate.

**Revised Ollama groups:**

| Group | Config class | Shared by | properties |
|-------|-------------|-----------|------------|
| `OllamaSimpleManualTestConfig` | Empty | DocRag, GreekImageVision, ImagePdfVisionRag, ImagesWithTextPdfVisionRag, ObjectsImageVision, TextPdfRag, XlsRag, ConversationHistoryGateway | `agent.enabled=false` |
| `AgentModeOllamaManualIT.TestConfig` | Empty | AgentMode only | none (agent enabled by default) |
| `ConversationHistoryOllamaManualIT.TestConfig` | WebTools bean | ConversationHistory only | `agent.enabled=true`, `agent.max-iterations=10`, `agent.tools.http-api.enabled=true` |
| `WebToolCallingOllamaManualIT.TestConfig` | WebTools bean | WebToolCalling only | `agent.enabled=false` |

Result: **8 tests share 1 context** (was 8 separate) + 3 individual = **4 contexts instead of 11**.

**OpenRouter tests — same pattern:**

| Group | Shared by | properties |
|-------|-----------|------------|
| `OpenRouterSimpleManualTestConfig` | DocRag, GreekImageVision, ImagePdfVisionRag, ImagesWithTextPdfVisionRag, ObjectsImageVision, TextPdfRag, XlsRag, ConversationHistoryGateway | `agent.enabled=false` |
| `AgentModeOpenRouterManualIT.TestConfig` | AgentMode only | `agent.enabled=true`, etc. |
| `ConversationHistoryOpenRouterManualIT.TestConfig` | ConversationHistory only | `agent.enabled=true`, WebTools |

Result: **8 tests share 1 context** + 2 individual = **3 contexts instead of 10**.

**Total: 7 contexts instead of 21** → 7 × 2 = 14 connections (with pool=2).

**Implementation:**

1. Create `OllamaSimpleManualTestConfig` in `src/it/java/.../it/manual/config/`
2. Create `OpenRouterSimpleManualTestConfig` in `src/it/java/.../it/manual/config/`
3. Update 8 Ollama tests: `classes = OllamaSimpleManualTestConfig.class`
4. Update 8 OpenRouter tests: `classes = OpenRouterSimpleManualTestConfig.class`
5. Remove empty inner `TestConfig` from those 16 tests
6. Keep inner `TestConfig` in AgentMode, ConversationHistory, WebToolCalling tests (they have unique configs)

### Step 6: Verify

1. `mvn clean verify` — green, 1 postgres + 1 minio
2. Run all 11 Ollama manual tests from IDEA in one batch — no `too many clients`
3. Run all 10 OpenRouter manual tests from IDEA — no `too many clients`
4. `docker ps -a --filter ancestor=postgres:17.0` — no zombies after tests

### Step 7: Cleanup

- Remove `testcontainers.reuse.enable=true` from `~/.testcontainers.properties` (if present)
- Update this document with results

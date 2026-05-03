# Handoff: module hygiene / dependency analyze / ArchUnit

Date: 2026-04-30
Project: open-daimon

## User request
Implement Maven Central readiness plan:
- minimal dependency declarations per module (`declare what you use`)
- reactor-wide `dependency:analyze`
- wire `maven-dependency-plugin:analyze-only` into `verify` with `failOnWarning=true`
- add ArchUnit boundary/layer rules
- add Maven Enforcer rules: dependency convergence, upper bounds, ban commons-logging, ban Spring Boot starters in non-app modules.

User then asked to split remaining work by module and persist state for a new session.

## Important project constraints
- Do not revert unrelated user/AI dirty changes.
- Public APIs matter. Avoid public type/method removals/renames unless explicitly approved.
- Modules are published/consumed independently; each module must declare directly-used libraries even if transitively available.
- No `@Service`, `@Component`, `@Repository` in main sources; explicit `@Bean` config only.
- Code/docs in repo must be English.

## Dirty state known before this work
Unrelated/generated files existed and should not be reverted unless user asks:
- `.serena/project.yml` modified
- docs/team files added
- various repository interfaces had `@Repository` removed by prior work
- some POMs were already partially edited

## Completed changes
### Root `pom.xml`
- Spring Boot aligned to `3.5.13`.
- Removed explicit Spring Framework BOM override.
- Updated several managed versions:
  - `postgresql.version=42.7.10`
  - `flyway.version=11.7.2`
  - `flyway-database-postgresql.version=11.7.2`
  - `jakarta-xml-bind.version=4.0.4`
  - `lombok.version=1.18.44`
  - `testcontainers.version=1.21.4`
  - `h2.version=2.3.232`
  - `maven-dependency-plugin.version=3.8.1`
  - `maven-enforcer-plugin.version=3.6.2`
  - `archunit.version=1.4.2`
- Added commons-logging exclusions to managed `httpclient` and `pdfbox`.
- Added pluginManagement for `maven-dependency-plugin:analyze-only` bound to `verify` with `failOnWarning=true`, `ignoreNonCompile=true`, `outputXML=true`.
- Added pluginManagement for `maven-enforcer-plugin` bound to `verify` with `dependencyConvergence`, `requireUpperBoundDeps`, and transitive banned `commons-logging:commons-logging`.
- Activated dependency/enforcer plugins in root `<build><plugins>`.

### Module POMs
- Copied dependency-cleanup baseline POMs from `../open-daimon-2` into current repo before patching further.
- Added module-local enforcer config banning transitive `org.springframework.boot:spring-boot-starter*` in non-app modules:
  - `opendaimon-common`
  - `opendaimon-spring-ai`
  - `opendaimon-rest`
  - `opendaimon-telegram`
  - `opendaimon-ui`
  - `opendaimon-gateway-mock`
- `opendaimon-app/pom.xml`: added `com.tngtech.archunit:archunit-junit5` test dependency and analyzer ignores for ArchUnit.
- `opendaimon-spring-ai/pom.xml`: replaced Spring AI starter runtime deps with non-starter autoconfigure deps:
  - `spring-ai-autoconfigure-model-chat-memory`
  - `spring-ai-autoconfigure-model-chat-memory-repository-jdbc`
- `opendaimon-common/pom.xml`: removed unused main deps reported by analyzer:
  - `reactor-netty-http`
  - `hibernate-validator`
  - `postgresql`
  - `micrometer-registry-prometheus`
  - `resilience4j-spring-boot2`

### Code boundary changes
Moved direct repository access out of delivery/service clients and behind services:
- `ConversationThreadService` gained:
  - `findThreads(ThreadScopeKind scopeKind, Long scopeId)`
  - `closeCurrentThread(ThreadScopeKind scopeKind, Long scopeId)`
  - existing `findByThreadKey` marked read-only transactional
- `OpenDaimonMessageService` gained:
  - `findByThreadOrderBySequenceNumberAsc(ConversationThread thread)`
  - `findByThreadAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(ConversationThread thread, Integer minSequenceNumber)`
- `HistoryTelegramCommandHandler` uses `ConversationThreadService` and `OpenDaimonMessageService`.
- `ThreadsTelegramCommandHandler` uses `ConversationThreadService.findThreads`.
- `NewThreadTelegramCommandHandler` uses `ConversationThreadService.closeCurrentThread`.
- `SummarizingChatMemory` uses `ConversationThreadService` and `OpenDaimonMessageService`.
- `TelegramCommandHandlerConfig` and `SpringAIAutoConfig` wiring updated accordingly.

### Tests partially updated
- `SummarizingChatMemoryTest` updated from repository mocks to service mocks.
- Telegram handler tests were patched but not re-verified after patch due user interrupt:
  - `ThreadsTelegramCommandHandlerTest`: removed repository mock and uses `threadService.findThreads`.
  - `HistoryTelegramCommandHandlerTest`: uses `ConversationThreadService` and `OpenDaimonMessageService` mocks.
  - `NewThreadTelegramCommandHandlerTest`: removed repository mock and verifies `closeCurrentThread`.

### ArchUnit
- Deleted old frozen `ArchitectureTest` and frozen store files:
  - `opendaimon-app/src/test/resources/archunit.properties`
  - files under `opendaimon-app/archunit_store/`
- Added new `opendaimon-app/src/test/java/io/github/ngirchev/opendaimon/arch/ArchitectureTest.java` with rules:
  - no `@Service`, `@Component`, `@Repository` in common/springai/telegram/rest/ui main packages
  - no cyclic library module dependencies
  - telegram must not depend on rest
  - rest must not depend on telegram
  - only app/root package may depend on multiple delivery channels
  - repository layer may only be accessed by service/config layers

## Verification completed before interrupt
- `./mvnw -pl opendaimon-app -am clean compile -DskipTests` passed.
- `./mvnw dependency:analyze -DskipTests` first failed on `SummarizingChatMemoryTest`; fixed.
- Re-run of `dependency:analyze -DskipTests` progressed and found module warnings before telegram test compile failure:
  - `opendaimon-common`: no dependency problems at that point.
  - `opendaimon-spring-ai`: unused declared warnings for:
    - `org.springframework.ai:spring-ai-autoconfigure-model-chat-memory` runtime
    - `org.springframework.ai:spring-ai-autoconfigure-model-chat-memory-repository-jdbc` runtime
    - `com.h2database:h2` test
  - `opendaimon-rest`: warnings:
    - unused declared `org.hamcrest:hamcrest:test`
    - non-test scoped test-only `com.fasterxml.jackson.core:jackson-core:compile`
    - non-test scoped test-only `org.springframework:spring-beans:compile`
  - `opendaimon-telegram`: test compile failed because handler tests still used old constructors; patched afterwards, but not re-run.
- Targeted command `./mvnw -pl opendaimon-telegram -am test -DskipITs -DskipIT -DfailIfNoTests=false` failed in upstream `opendaimon-common` tests because `hibernate-validator` had been removed and Spring configuration properties validation needs a provider at test runtime.

## Current blocker at interrupt
`opendaimon-common` tests fail with:
`jakarta.validation.NoProviderFoundException: Unable to create a Configuration, because no Jakarta Bean Validation provider could be found.`
This came from `BulkHeadPropertiesTest` loading Spring context. Likely fix: add `org.hibernate.validator:hibernate-validator` back as test-scoped dependency in `opendaimon-common`, not compile scoped, unless production module needs to provide validation provider to downstream consumers. Verify analyzer afterwards.

## Suggested module-by-module continuation plan
1. `opendaimon-common`
   - Add `hibernate-validator` as test dependency or otherwise provide validation provider only for tests.
   - Run: `./mvnw -pl opendaimon-common test dependency:analyze -DskipITs -DskipIT`.
   - Ensure no analyzer warnings.

2. `opendaimon-spring-ai`
   - Decide on analyzer handling for runtime Spring AI autoconfig glue and H2.
   - If runtime autoconfig jars are intentionally present for Boot auto-configuration, add module-local `ignoredUnusedDeclaredDependencies` with precise comments.
   - Remove H2 if genuinely unused, or ignore if Boot test infra loads it implicitly.
   - Review `jakarta.persistence-api`: currently test scoped and compile has warnings about missing enum constants during app compile; may need compile scope if main bytecode references persistence types indirectly.
   - Run: `./mvnw -pl opendaimon-spring-ai -am clean compile test dependency:analyze -DskipITs -DskipIT`.

3. `opendaimon-rest`
   - Remove `org.hamcrest:hamcrest` if no direct imports.
   - For `spring-beans` and `jackson-core`, either move to test scope if truly test-only, or add `ignoredNonTestScopedDependencies` if they must remain main-runtime deps. Existing comment incorrectly only handles unused-declared category.
   - Run: `./mvnw -pl opendaimon-rest -am clean compile test dependency:analyze -DskipITs -DskipIT`.

4. `opendaimon-telegram`
   - Re-run tests after patched constructors.
   - Confirm Caffeine is declared directly because `TelegramChatPacerImpl` imports it.
   - Run: `./mvnw -pl opendaimon-telegram -am clean compile test dependency:analyze -DskipITs -DskipIT`.

5. `opendaimon-ui` and `opendaimon-gateway-mock`
   - Run module analyzer/enforcer separately and fix only local warnings.

6. `opendaimon-app` ArchUnit
   - Run: `./mvnw -pl opendaimon-app -am test -Dtest=ArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false`.
   - Fix real violations, do not restore freeze store.

7. Reactor final checks
   - `./mvnw clean compile`
   - `./mvnw dependency:analyze -DskipTests`
   - targeted ArchUnit
   - `./mvnw clean verify`

## Notes for next session
- Do not keep editing globally. Finish one module at a time and verify that module before moving on.
- Watch Maven Enforcer merge behavior: module-local banned starter config may override root rules unless Maven merges as expected. Confirm with `clean verify`.
- The banned starter pattern `org.springframework.boot:spring-boot-starter*` may need to be split into `spring-boot-starter` and `spring-boot-starter-*` if enforcer does not match as intended.
- If Maven needs network and sandbox blocks it, rerun exact command with escalation per Codex instructions.
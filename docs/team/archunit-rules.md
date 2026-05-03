---
slug: archunit-rules
title: "ArchUnit Architecture Rules"
owner: ngirchev
created: 2026-04-28
updated: 2026-05-03
status: done
base_branch: fsm
---

## Summary

OpenDaimon now has executable ArchUnit checks for the core Spring Boot library architecture:

- `opendaimon-app` keeps the cross-module rules: no Spring component-discovery stereotypes in published library modules, no concrete `@Repository` classes, and no cyclic dependencies between `common`, `spring-ai`, `telegram`, and `rest`.
- `opendaimon-common` keeps common-module rules for stereotypes, configuration placement, property classes, service naming, implementation suffixes, and repository placement.
- `opendaimon-rest`, `opendaimon-telegram`, and `opendaimon-spring-ai` now each have module-local layer rules.

The rules encode the project style from `AGENTS.md`: library modules are consumed by downstream applications, so bean creation is explicit through configuration classes, configuration properties are kept in `config`, and service layers must not leak controller, handler, DTO, or transport concerns inward.

## Scope

In scope:

- `opendaimon-common`
- `opendaimon-spring-ai`
- `opendaimon-telegram`
- `opendaimon-rest`
- cross-module checks from `opendaimon-app`

Out of scope:

- `opendaimon-ui`
- `opendaimon-gateway-mock`
- application runtime wiring beyond the existing cross-module `ArchitectureTest`

`opendaimon-ui` and `opendaimon-gateway-mock` are intentionally out of scope for module-local ArchUnit suites. They are thin support modules without independent repository/domain/service layering. For those modules, use compile checks, dependency analysis/enforcer checks, and focused behavior tests when behavior changes. Reconsider ArchUnit only if either module grows stable internal architectural boundaries that need executable enforcement.

## Rule Set

### Cross-Module Rules

File: `opendaimon-app/src/test/java/io/github/ngirchev/opendaimon/arch/ArchitectureTest.java`

- Published library modules must not use `@Service` or `@Component`.
- Concrete classes must not use `@Repository`; Spring Data repository interfaces remain allowed.
- Library modules must not form package-level dependency cycles.
- `IncludeOpendaimonOnly` keeps ArchUnit import behavior stable across both `mvn test` and `mvn verify -Pfixture` by allowing exploded classes and only project-owned `opendaimon-*` JARs.

### Common Rules

File: `opendaimon-common/src/test/java/io/github/ngirchev/opendaimon/common/arch/CommonArchitectureTest.java`

- No `@Service` or `@Component`.
- Configuration classes and `@Bean` methods stay under `common.config`.
- `@ConfigurationProperties` classes stay under `common.config`, end with `Properties`, and use validation.
- Services live under `common.service`, service implementations end with `Impl`, and interfaces stay interface-only.
- Repository access is limited to repository and service layers.

### REST Rules

File: `opendaimon-rest/src/test/java/io/github/ngirchev/opendaimon/rest/arch/RestArchitectureTest.java`

- No `@Service` or `@Component`.
- No concrete `@Repository` classes.
- `@Bean`, `@Configuration`, and `@AutoConfiguration` classes stay under `rest.config`.
- `@ConfigurationProperties` classes stay under `rest.config`, end with `Properties`, and use validation.
- `@RestController` classes stay under `rest.controller`.
- `@ControllerAdvice` and `@RestControllerAdvice` classes stay under `rest.exception`.
- Repository access is limited to config, repository, and service layers.
- `rest.service..` does not depend on `rest.dto..` or `rest.handler..`.

Implementation cleanup required for these rules:

- `RestChatCommand` and `RestChatCommandType` moved from `rest.handler` to `rest.command`.
- REST service return types were split into internal service models under `rest.service.model`.
- Controllers now map internal service models to public DTOs at the boundary.

### Telegram Rules

File: `opendaimon-telegram/src/test/java/io/github/ngirchev/opendaimon/telegram/arch/TelegramArchitectureTest.java`

- No `@Service` or `@Component`.
- No concrete `@Repository` classes.
- `@Bean`, `@Configuration`, and `@AutoConfiguration` classes stay under `telegram.config`.
- `@ConfigurationProperties` classes stay under `telegram.config`, end with `Properties`, and use validation.
- Repository access is limited to config, repository, and service layers.
- `telegram.service..` does not depend on `telegram.command.handler..`.

Implementation cleanup required for these rules:

- Telegram message FSM types moved from `telegram.command.handler.impl.fsm` to `telegram.service.fsm`.
- `TelegramMessageSender` and `TelegramDeliveryFailedException` moved to `telegram.service`.
- `TelegramSupportedCommandProvider` moved to `telegram.command`.

### Spring AI Rules

File: `opendaimon-spring-ai/src/test/java/io/github/ngirchev/opendaimon/ai/springai/arch/SpringAIArchitectureTest.java`

- No `@Service` or `@Component`.
- No concrete `@Repository` classes.
- `@Bean`, `@Configuration`, and `@AutoConfiguration` classes stay under `ai.springai.config`.
- `@ConfigurationProperties` classes stay under `ai.springai.config`, end with `Properties`, and use validation.
- Runtime slices are checked for cycles across `advisor`, `agent`, `embedding`, `memory`, `rag`, `rest`, `retry`, `service`, and `tool`.

Implementation cleanup required for these rules:

- `AgentAutoConfig` moved from `ai.springai.agent` to `ai.springai.config`.
- `AgentProperties` moved from `ai.springai.agent` to `ai.springai.config`.
- `OpenRouterModelsProperties` moved from `ai.springai.retry` to `ai.springai.config`.
- `AutoConfiguration.imports` now references `ai.springai.config.AgentAutoConfig`.

## Maven Wiring

Root `pom.xml` owns the ArchUnit version through `archunit.version`.

The modules with local ArchUnit tests declare ArchUnit test dependencies directly:

- `opendaimon-app`
- `opendaimon-common`
- `opendaimon-rest`
- `opendaimon-telegram`
- `opendaimon-spring-ai`

Modules that use `archunit-junit5-engine` only through test discovery list it in the Maven dependency plugin's `ignoredUsedUndeclaredDependencies`, matching the existing `opendaimon-common` pattern.

## Verification

Commands used during this cleanup:

```bash
./mvnw -pl opendaimon-rest -am test -DskipITs -DskipIT
./mvnw -pl opendaimon-telegram -am test -DskipITs -DskipIT
./mvnw -pl opendaimon-spring-ai -am test -DskipITs -DskipIT
```

The final cleanup pass should also run:

```bash
./mvnw -pl opendaimon-app -am test -Dtest=ArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false -DskipITs -DskipIT
./mvnw -pl opendaimon-rest -am dependency:analyze -DskipITs -DskipIT
./mvnw -pl opendaimon-telegram -am dependency:analyze -DskipITs -DskipIT
./mvnw -pl opendaimon-spring-ai -am dependency:analyze -DskipITs -DskipIT
```

## Status

Done when all module-local ArchUnit suites and dependency analysis checks pass, and no references remain to the old package locations for moved REST, Telegram, and Spring AI types.

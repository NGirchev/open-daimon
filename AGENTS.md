# AGENTS.md

## Agent Role

Act as a senior Java developer who follows the project style consistently — a multi-module Java project with Spring Boot starters. Use solutions that fit the existing structure rather than the most obvious or popular ones. Always check existing code to match the same style.

## User Profile

Java tech lead, experienced, intolerant of sloppy work. Requires tests and verification of hypotheses — code is not accepted without them. Significant changes must be agreed. Listen to the user and do what they ask; if you disagree, argue with reasoning.

## Rules for AI Agents

### Serena activation on session start

- At the beginning of each new session in this repository, verify Serena state first.
- If Serena reports `Active Project: None`, immediately call `activate_project("open-daimon")`.
- Do this before any code exploration or edits to ensure project-aware symbol tooling works correctly.

### MCP tools for information lookup

- Two MCP servers are available and should be used for information lookup when relevant:
  - `Serena` — codebase navigation, symbol search, and project-aware exploration.
  - `Context7` — library/framework documentation lookup and API usage search.
- Prefer these MCP tools first for discovery and verification before broader ad-hoc searching.

### Documentation maintenance

- Every module that has a behavior reference doc (e.g. `SPRING_AI_MODULE.md`, `TELEGRAM_MODULE.md`) must be updated when the behavior it describes changes.
- If you add or change a use case, command flow, branching condition, input/output format, or error path — update the corresponding doc in the same commit.
- Docs live next to the module root (e.g. `opendaimon-spring-ai/SPRING_AI_MODULE.md`, `opendaimon-telegram/TELEGRAM_MODULE.md`).

### Language in code and documentation

- **Code, comments, javadoc, commit messages, and in-repo documentation** must be written in **English**.
- User-facing strings (i18n in `.properties`, bot messages) may be in any language.
- Exception and log messages in code must be in English.

## Project Style Guide

### Java & Dependencies

- **Java 21** with modern features
- **Lombok** (`@Getter`, `@Setter`, `@RequiredArgsConstructor`, `@Slf4j`)
- **Vavr** for functional patterns
- **Package structure:** `io.github.ngirchev.opendaimon.<module>.<layer>`

### Dependency order in pom.xml

1. Project modules (groupId: `io.github.ngirchev`)
2. Spring dependencies
3. Database dependencies
4. Other utilities
5. Test dependencies (scope: `test`)

**All versions MUST be in `<properties>`!**

### Spring Bean Configuration

**Do NOT use `@Service`, `@Component`** for automatic bean scanning.
- Create beans explicitly in configuration classes via `@Bean` methods
- Configuration classes live in the `config` package of each module

**ObjectProvider** for optional/lazy beans; **@Lazy** to break circular dependencies at creation time.

### Service Layer

- Interfaces for services (e.g. `UserService`, `UserPriorityService`)
- Implementations with `Impl` suffix
- `@RequiredArgsConstructor` for dependency injection

### Entities

- Base entities only in `opendaimon-common` (`User`, `Message`)
- Module-specific entities in modules (`TelegramUser`, `RestUser`)
- **JPA Inheritance JOINED** for User (discriminator: `user_type`, values: `TELEGRAM`, `REST`)
- **JPA Inheritance SINGLE_TABLE** for Message (discriminator: `message_type`, metadata JSONB)
- `@PrePersist` and `@PreUpdate` for automatic timestamps

### Configuration

- Namespace: `open-daimon.*` (modules `telegram`, `rest`, `ui`, `ai.spring-ai`); toggles use `*.enabled`
- **Feature Toggles:** centralized in `FeatureToggle` (opendaimon-common). Never use raw string literals in `@ConditionalOnProperty` — use `FeatureToggle.Module`, `FeatureToggle.Feature`, or `FeatureToggle.TelegramCommand`.
- **@ConfigurationProperties:** all values required (set in `application.yml`, not in code). Use `@Validated` with `@NotNull`, wrapper types (`Integer`, `Double`, `Boolean`).
- Module auto-configs: `CoreAutoConfig`, `TelegramAutoConfig`, `RestAutoConfig`, `SpringAIAutoConfig`

### Database Migrations

- All migrations in `opendaimon-app/src/main/resources/db/migration/`
- Modular paths: `core/`, `telegram/`, `rest/`, `springai/`
- Naming: `V<number>__<description>.sql`
- Indexes required for FKs and frequent queries
- Use `IF NOT EXISTS` for idempotency
- Timestamps: `TIMESTAMP WITH TIME ZONE`

### Metrics

- Use `OpenDaimonMeterRegistry` from `opendaimon-common`
- Format: `<module>.<action>.<metric>` (e.g. `telegram.message.processing.time`)

### Prioritization

- Use `PriorityRequestExecutor` for all AI requests — never call AI services directly
- Priorities: ADMIN (10 threads), VIP (5 threads), REGULAR (1 thread)
- Whitelist managed via `WhitelistService`

### Testing

- Unit tests for services (Mockito), integration tests for repositories (Testcontainers)
- Coverage at least 80% for critical business logic
- Do not mock entities — use real objects
- Use `@DataJpaTest` for repository tests

### Build & Verification

- Always run `./mvnw clean compile` after code changes before running tests
- Verify compilation separately before running tests

## See Also

- **Architecture & Modules:** [ARCHITECTURE.md](ARCHITECTURE.md)
- **Build & Test Commands:** [Makefile](Makefile)

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

- **Code, comments, javadoc, commit messages, and in-repo documentation** (AGENTS.md, READMEs in packages) must be written in **English**.
- User-facing strings (i18n in `.properties`, bot messages) may be in any language.
- Exception and log messages in code must be in English.

### When creating new services and components

1. **Do NOT use `@Service`, `@Component`, `@Repository`** for automatic bean scanning
2. **Create beans explicitly** in configuration classes via `@Bean` methods
3. **Configuration classes** live in the `config` package of each module
4. **Example**:
   ```java
   // ❌ WRONG:
   @Service
   public class MyService { ... }
   
   // ✅ CORRECT:
   public class MyService { ... }  // No annotations
   
   @Configuration
   public class MyModuleConfig {
       @Bean
       @ConditionalOnMissingBean
       public MyService myService(...) {
           return new MyService(...);
       }
   }
   ```
5. **Exception:** `@Repository` on JPA repository interfaces is allowed (interfaces, not classes)

### When creating new modules

1. **Create pom.xml** with the correct dependency structure (see [CODE_STYLE.md](CODE_STYLE.md))
2. **Add the module** to parent pom.xml in the `<modules>` section
3. **Package structure:** `io.github.ngirchev.opendaimon.<module-name>.<layer>`
4. **If entities are needed:** extend `User` or `Message` from `opendaimon-common`
5. **Create a Flyway migration** in `opendaimon-app/src/main/resources/db/migration/`
6. **Create a configuration class** for all beans of the module (e.g. `MyModuleConfig`)

### When working with entities

1. **Do not duplicate entities** across modules — use inheritance
2. **Base entities** only in `opendaimon-common`
3. **Module-specific fields** in subclasses (e.g. `telegram_id` in `TelegramUser`)
4. **Use JPA Inheritance JOINED** for User
5. **Use JPA Inheritance SINGLE_TABLE** for Message (all messages in one table, specific data in metadata JSONB)
6. **Discriminator** is required for polymorphic queries

### When adding new AI providers

1. **Create a new module** `ai-<provider-name>` (e.g. `ai-anthropic`)
2. **Create a Service** with `generateResponse(String prompt, ...)`
3. **Create Properties** for configuration (API key, URL)
4. **Add the dependency** to modules that will use the provider
5. **Do not add entities** — providers are stateless

### When working with the database

1. **All migrations** in `opendaimon-app/src/main/resources/db/migration/`
2. **Naming:** `V<number>__<description>.sql` (e.g. `V1__Create_initial_tables.sql`)
3. **Indexes are required** for foreign keys and frequently queried fields
4. **Use `IF NOT EXISTS`** for idempotency
5. **Timestamps:** `TIMESTAMP WITH TIME ZONE` (not `TIMESTAMP`)

### When adding metrics

1. **Use `OpenDaimonMeterRegistry`** from `opendaimon-common`
2. **Metric format:** `<module>.<action>.<metric>` (e.g. `rest.request.processing.time`)
3. **Types:** Counter, Timer, Gauge
4. **Add description** in the Grafana dashboard

### When working with prioritization

1. **Use `PriorityRequestExecutor`** for all AI requests
2. **Do not call AI services directly** — only via the executor
3. **Priorities:** ADMIN (10 threads), VIP (5 threads), REGULAR (1 thread)
4. **Whitelist** is managed via `WhitelistService`

### Security

1. **API keys** ONLY in environment variables
2. **Do not commit** `application.yml` with real keys
3. **Use `@PreAuthorize`** to protect REST endpoints (if you add Spring Security)
4. **Validate input** with Jakarta Validation (`@Valid`, `@NotNull`, etc.)

### Testing

1. **Unit tests** for services (Mockito)
2. **Integration tests** for repositories (Testcontainers)
3. **Coverage** at least 70% for critical business logic
4. **Do not mock entities** — use real objects
5. **Use `@DataJpaTest`** for repository tests

### Build & Verification

1. **Always run `mvn clean`** before compile or test to avoid stale bytecode issues
2. **Always run `mvn clean compile`** after code changes before running tests
3. **Verify compilation separately** — run `mvn compile` before `mvn test` to catch compilation errors early

## See Also

- **Architecture & Modules:** [ARCHITECTURE.md](ARCHITECTURE.md)
- **Code Style & Configuration:** [CODE_STYLE.md](CODE_STYLE.md)
- **Build & Test Commands:** [Makefile](Makefile)

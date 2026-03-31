# Code Style and Configuration

## Dependency Order in pom.xml

**IMPORTANT:** Follow this order in EVERY pom.xml (see comments in files):
1. Project-specific modules (groupId: `io.github.ngirchev`)
2. Spring dependencies (groupId: `org.springframework`)
3. Database dependencies (jdbc, jpa, postgres, h2)
4. Other utilities and libraries (logging, json, etc.)
5. Test-related dependencies (scope: `test`)

**All versions MUST be in `<properties>`!**

## Java Code Style

- **Java 21** with modern features
- **Lombok** to reduce boilerplate (`@Getter`, `@Setter`, `@RequiredArgsConstructor`, `@Slf4j`)
- **Functional patterns** where possible (Vavr is used)
- **Package structure:** `io.github.ngirchev.opendaimon.<module>.<layer>` (e.g. `io.github.ngirchev.opendaimon.telegram.service`)

## Entity Guidelines

- Base entities in `opendaimon-common` (`User`, `Message`)
- Module-specific entities in modules (`TelegramUser`, `RestUser`)
- **JPA Inheritance JOINED** for User
- **JPA Inheritance SINGLE_TABLE** for Message (all messages in one table)
- `@PrePersist` and `@PreUpdate` for automatic timestamps
- Discriminator column: `user_type` (values: `TELEGRAM`, `REST`) for User
- Discriminator column: `message_type` for Message (default `MESSAGE`)

## Service Layer

- Interfaces for services (e.g. `UserService`, `UserPriorityService`)
- Implementations with `Impl` suffix (e.g. `UserPriorityServiceImpl`)
- `@RequiredArgsConstructor` for dependency injection
- `@Slf4j` for logging

## Spring Bean Configuration

**IMPORTANT:** This project does NOT use `@Service`, `@Component`, `@Repository` for automatic bean scanning!
- **All beans are created explicitly** in configuration classes via `@Bean` methods
- **Configuration classes** live in the `config` package of each module (e.g. `TelegramServiceConfig`, `CoreAutoConfig`)
- **Benefits:** explicit control over bean creation, conditional config via `@ConditionalOnProperty`, better testability
- **Example:** instead of `@Service` on a class, add a `@Bean` method in the corresponding `*Config` class

### ObjectProvider Example:

```java
// ✅ CORRECT: ObjectProvider for optional/lazy beans
@Bean
@ConditionalOnMissingBean
public MessageTelegramCommandHandler messageTelegramCommandHandler(
        ObjectProvider<TelegramBot> telegramBotProvider,  // Optional bean
        PriorityRequestExecutor priorityRequestExecutor,
        // ... other dependencies
) {
    return new MessageTelegramCommandHandler(telegramBotProvider, priorityRequestExecutor, ...);
}

// In handler class:
public class MessageTelegramCommandHandler {
    private final ObjectProvider<TelegramBot> telegramBotProvider;
    
    public void sendMessage(Long chatId, String text) {
        // Bean is obtained only when needed
        telegramBotProvider.getObject().sendMessage(chatId, text);
    }
}
```

**When to use ObjectProvider:**
- When the bean may be absent (optional)
- When lazy loading is needed (obtain bean only on use)
- To avoid circular dependencies
- When the bean is created conditionally (`@ConditionalOnProperty`)

**When to use @Lazy:**
- When the bean must always exist but initialization should be lazy
- To break a circular dependency at bean creation time

## Command Pattern

- Interface `CommandHandler<T extends CommandType, C extends Command<T>, R>`
- Each module has its own implementation (e.g. `TelegramCommandHandler`)
- Registry for handlers (`OpenDaimonCommandHandlerRegistry`)

## Metrics and Monitoring

- Use `OpenDaimonMeterRegistry` to register metrics
- Metric format: `<module>.<action>.<metric>` (e.g. `telegram.message.processing.time`)
- All metrics are exported to Prometheus

## Configuration

- Configuration namespace is `open-daimon.*` (modules `telegram`, `rest`, `ui`, `ai.spring-ai`); feature toggles use `*.enabled`.
- Config keys and comments live in `opendaimon-app/src/main/resources/application.yml`.

### Module Auto-Configuration

Each module provides an `@AutoConfiguration` class with conditional bean registration:
- `CoreAutoConfig` (opendaimon-common) — core services, registries
- `TelegramAutoConfig` — enabled via `open-daimon.telegram.enabled=true`
- `RestAutoConfig` — enabled via `open-daimon.rest.enabled=true`
- `SpringAIAutoConfig` — enabled via `open-daimon.ai.spring-ai.enabled=true`

### Properties Hierarchy

```yaml
open-daimon:
  common:
    summarization:
      max-context-tokens: 8000
      summary-trigger-threshold: 0.7
      keep-recent-messages: 20
    bulkhead:
      enabled: true
  telegram:
    enabled: true
  rest:
    enabled: true
  ai:
    spring-ai:
      enabled: true
```

### @ConfigurationProperties Guidelines

**IMPORTANT:** For `@ConfigurationProperties` classes:
- All values are required (must be set in `application.yml`)
- Do NOT set default values in code — only in configuration
- Use validation: `@Validated` with `@NotNull`, `@Min`, `@Max`
- Use wrapper types (`Integer`, `Double`, `Boolean`) for `@NotNull`

**Example:**
```java
@ConfigurationProperties(prefix = "open-daimon.context")
@Validated
@Getter
@Setter
public class ContextProperties {
    @NotNull(message = "maxContextTokens is required")
    @Min(value = 1000, message = "maxContextTokens must be >= 1000")
    private Integer maxContextTokens; // No default in code!
}
```

## Database Migrations

### Modular Flyway Strategy

- Each module has migration path: `src/main/resources/db/migration/<module>/`
- Paths: `core/`, `telegram/`, `rest/`, `springai/`
- Each module's `FlywayConfig` registers its locations
- Migrations run in order across all modules

### Adding a New Migration

1. Create file in the module path: `V<number>__Description.sql`
2. Use naming like `V1__Create_base_tables.sql`, `V2__Add_user_fields.sql`
3. Run `mvn flyway:migrate -pl opendaimon-common` to apply

### Migration Best Practices

1. **All migrations** in `opendaimon-app/src/main/resources/db/migration/`
2. **Naming:** `V<number>__<description>.sql` (e.g. `V1__Create_initial_tables.sql`)
3. **Indexes are required** for foreign keys and frequently queried fields
4. **Use `IF NOT EXISTS`** for idempotency
5. **Timestamps:** `TIMESTAMP WITH TIME ZONE` (not `TIMESTAMP`)

## Line Endings (Linux, Mac, Windows)

The repo uses **LF only** for text files (`.gitattributes`). To avoid spurious "modified" files when switching between machines:

- **Linux / Mac:** use default Git behaviour (`core.autocrlf` unset or `false`). No extra config needed.
- **Windows:** set `git config core.autocrlf input` so Git converts CRLF→LF on commit and does not touch files on checkout; then working tree stays LF and matches the repo.
- **One-time renormalization** (if many files show as changed only by line endings): run from repo root:
  ```bash
  git add --renormalize .
  git status   # review, then commit
  git commit -m "Normalize line endings to LF"
  ```
  After that, all tracked files are stored with LF and `git status` stays clean across Linux/Mac/Windows.

# Feature Toggle Conventions

All feature toggle property keys are centralized in
`io.github.ngirchev.opendaimon.common.config.FeatureToggle` (opendaimon-common module).

## Rule: No Raw String Literals

**NEVER** use raw string literals in `@ConditionalOnProperty` annotations.
Always reference constants from `FeatureToggle`:

```java
// WRONG
@ConditionalOnProperty(name = "open-daimon.telegram.enabled", havingValue = "true")

// CORRECT
@ConditionalOnProperty(name = FeatureToggle.Module.TELEGRAM_ENABLED, havingValue = "true")

// CORRECT (prefix-based)
@ConditionalOnProperty(prefix = FeatureToggle.TelegramCommand.PREFIX,
        name = FeatureToggle.TelegramCommand.START, havingValue = "true", matchIfMissing = true)
```

## Categories

| Inner Class | Purpose | Example |
|-------------|---------|---------|
| `Module` | Enable/disable an entire module | `TELEGRAM_ENABLED`, `SPRING_AI_ENABLED` |
| `Feature` | Enable/disable a feature within a module | `RAG_ENABLED`, `BULKHEAD_ENABLED` |
| `TelegramCommand` | Granular Telegram command toggles (prefix-based) | `PREFIX` + `START`, `MODEL` |
| `OpenRouterModels` | OpenRouter rotation toggle (prefix-based) | `PREFIX` + `ENABLED` |
| `Toggle` (enum) | Runtime companion for iteration/validation | Not for annotations |

## Naming Convention

Property keys follow: `open-daimon.<module>.<feature>.enabled`

- Module toggles: `open-daimon.<module>.enabled`
- Feature toggles: `open-daimon.<module>.<feature>.enabled`
- Command toggles: `open-daimon.telegram.commands.<command>-enabled`

Constant names use `SCREAMING_SNAKE_CASE` matching the property semantic:
`TELEGRAM_CACHE_REDIS_ENABLED` for `open-daimon.telegram.cache.redis-enabled`.

## How to Add a New Toggle

1. Add `public static final String` constant to the appropriate inner class in `FeatureToggle`
2. Add a corresponding entry to the `Toggle` enum
3. Add the default value in `opendaimon-app/src/main/resources/application.yml`
4. Use the constant in `@ConditionalOnProperty` annotations
5. Document the toggle with a `# FEATURE FLAG` comment in `application.yml`

## Telegram Command Toggles

| Constant | Property Key | Default | Description |
|---|---|---|---|
| `TelegramCommand.LANGUAGE` | `open-daimon.telegram.commands.language-enabled` | `true` | Enable the `/language` per-user language selection command. |
| `TelegramCommand.THINKING` | `open-daimon.telegram.commands.thinking-enabled` | `true` | Enable the `/thinking` per-user reasoning-visibility command (3 states: SHOW_ALL, HIDE_REASONING, SILENT). See [docs/telegram-thinking-modes.md](telegram-thinking-modes.md). |

## Default Values

All default values live exclusively in `application.yml` — never in Java code,
`@ConfigurationProperties`, or `@Value` annotations.

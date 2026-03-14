# Modular Flyway Migrations

## Overview

The project uses a modular Flyway migration system where each module is responsible for its own database tables. This allows enabling only the modules you actually need.

## Migration structure

### Core module (aibot-common)
**Always runs** — contains base tables:
- `V1__Create_base_user_table.sql` — user table
- `V2__Create_base_user_request_table.sql` — request table
- `V3__Create_service_response_table.sql` — AI response table
- `V4__Create_base_indexes.sql` — base indexes

### Telegram module (aibot-telegram)
**Runs only when `ai-bot.telegram.enabled=true`**:
- `V1__Create_telegram_user_table.sql` — Telegram user table
- `V2__Create_telegram_session_table.sql` — session table
- `V3__Create_telegram_request_table.sql` — Telegram request table
- `V4__Create_telegram_whitelist_table.sql` — whitelist table
- `V5__Create_telegram_indexes.sql` — Telegram indexes

### REST module (aibot-rest)
**Runs only when `ai-bot.rest.enabled=true`**:
- `V1__Create_rest_user_table.sql` — REST user table
- `V2__Create_rest_request_table.sql` — REST request table
- `V3__Create_rest_indexes.sql` — REST indexes

## Configuration

### Enabling modules

```yaml
ai-bot:
  telegram:
    enabled: true   # Enable Telegram module
  rest:
    enabled: false  # Disable REST module
```

### Disabling default Flyway

```yaml
spring:
  flyway:
    enabled: false  # Disable default Flyway
```

## Usage examples

### Telegram bot only
```yaml
ai-bot:
  telegram:
    enabled: true
    token: "${TELEGRAM_BOT_TOKEN}"
  rest:
    enabled: false
```

**Result**: Only base tables + Telegram tables are created

### REST API only
```yaml
ai-bot:
  telegram:
    enabled: false
  rest:
    enabled: true
```

**Result**: Only base tables + REST tables are created

### Full stack
```yaml
ai-bot:
  telegram:
    enabled: true
  rest:
    enabled: true
```

**Result**: All tables are created

## Migration execution order

1. **Core migrations** — always run first
2. **Telegram migrations** — run when module is enabled
3. **REST migrations** — run when module is enabled

## Benefits

- **Modularity** — only required tables are created
- **Flexibility** — easy to enable/disable modules
- **Performance** — fewer tables means faster operation
- **Clarity** — clear mapping of tables to modules
- **Scalability** — easy to add new modules

## Migrating from the old system

If you already have a database with the previous structure:

1. Back up the database
2. Update configuration to the modular setup
3. Restart the application
4. Verify that all tables were created correctly

## Troubleshooting

### Migrations not running
- Ensure `spring.flyway.enabled=false`
- Ensure modules are enabled in configuration
- Check logs for errors

### Tables not created
- Ensure the corresponding modules are enabled
- Ensure migrations are in the correct folders
- Check database access rights

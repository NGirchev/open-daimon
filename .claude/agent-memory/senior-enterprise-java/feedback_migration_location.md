---
name: Migration files live in opendaimon-common, not opendaimon-app
description: DB migrations for the core "user" table are in opendaimon-common/src/main/resources/db/migration/core/, not opendaimon-app
type: feedback
---

Core DB migrations (user table, agent tables, etc.) live in `opendaimon-common/src/main/resources/db/migration/core/`, not in `opendaimon-app`. The plan said `opendaimon-app` but inspection confirmed the correct location.

**Why:** Flyway is configured per module; common migrations travel with `opendaimon-common`.

**How to apply:** When adding a migration for a base entity, always glob `opendaimon-common/**/migration/core/V*.sql` to find the next free version number.

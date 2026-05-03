---
name: Verify conventions before writing
description: Always check existing code conventions (format, naming, patterns) before creating new code — don't assume
type: feedback
---

When adding new handlers, commands, or any component that follows an existing pattern — ALWAYS read 2-3 existing implementations first and match their exact conventions.

**Why:** Wrote `AgentTelegramCommandHandler.getSupportedCommandText()` returning `"agent - ..."` without `/` prefix, while all other handlers return `"/command - ..."`. This caused the command to not display correctly in Telegram bot menu. A simple check of any existing handler would have caught this.

**How to apply:** Before writing a new implementation of an interface/abstract class, read at least 2 existing implementations to understand the expected format, naming, and conventions. Don't assume based on the interface contract alone — look at the actual usage.

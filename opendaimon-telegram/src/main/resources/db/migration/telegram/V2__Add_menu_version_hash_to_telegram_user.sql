-- Per-chat Telegram command menu reconciliation marker.
-- Holds the SHA-256 hex of the command set (per language) that was last pushed to Telegram
-- via BotCommandScopeChat for this user. Nullable: users that never had a chat-scoped menu
-- set (language not yet chosen) stay on the Default scope and do not need reconciliation.
ALTER TABLE telegram_user
    ADD COLUMN IF NOT EXISTS menu_version_hash VARCHAR(64);

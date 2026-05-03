-- TelegramGroup child table joined to "user" via JOINED inheritance (discriminator: TELEGRAM_GROUP).
-- Represents a Telegram group or supergroup as a single logical participant: settings
-- (language, preferred model, agent mode, thinking mode, assistant role, menu version hash,
-- recent models) belong to the group row, shared by every member.
--
-- telegram_id holds the Telegram chat_id (negative for groups/supergroups). Parallel to
-- telegram_user.telegram_id; positive-vs-negative value space prevents collisions in practice.
CREATE TABLE IF NOT EXISTS telegram_group (
    id                BIGINT PRIMARY KEY REFERENCES "user"(id),
    telegram_id       BIGINT UNIQUE NOT NULL,
    title             VARCHAR(512),
    type              VARCHAR(32),
    menu_version_hash VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_telegram_group_telegram_id ON telegram_group(telegram_id);

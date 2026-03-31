-- Add telegram_message_id to message table for reply-to-image lookup.
-- Stores the Telegram message ID so that reply-to messages can be resolved
-- from the database (e.g. to retrieve attached images from MinIO).

ALTER TABLE message
    ADD COLUMN IF NOT EXISTS telegram_message_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_message_telegram_message_id
    ON message(telegram_message_id)
    WHERE telegram_message_id IS NOT NULL;

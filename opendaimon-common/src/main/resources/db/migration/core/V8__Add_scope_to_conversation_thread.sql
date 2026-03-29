-- Add scope columns for conversation thread ownership.
-- USER scope is legacy behavior; TELEGRAM_CHAT is used for shared chat/group history.

ALTER TABLE conversation_thread
    ADD COLUMN IF NOT EXISTS scope_kind VARCHAR(32);

ALTER TABLE conversation_thread
    ADD COLUMN IF NOT EXISTS scope_id BIGINT;

UPDATE conversation_thread
SET scope_kind = 'USER'
WHERE scope_kind IS NULL;

UPDATE conversation_thread
SET scope_id = user_id
WHERE scope_id IS NULL;

ALTER TABLE conversation_thread
    ALTER COLUMN scope_kind SET NOT NULL;

ALTER TABLE conversation_thread
    ALTER COLUMN scope_id SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_conversation_thread_scope
    ON conversation_thread(scope_kind, scope_id);

CREATE INDEX IF NOT EXISTS idx_conversation_thread_scope_active
    ON conversation_thread(scope_kind, scope_id, is_active, last_activity_at);


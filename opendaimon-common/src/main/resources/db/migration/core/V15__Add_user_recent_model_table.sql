-- =====================================================
-- Track recently selected AI models per user.
-- Populated by ModelTelegramCommandHandler on explicit pick;
-- cap is maintained write-side (top 8 by last_used_at).
-- =====================================================
CREATE TABLE IF NOT EXISTS user_recent_model (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
    model_name VARCHAR(255) NOT NULL,
    last_used_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_user_recent_model UNIQUE (user_id, model_name)
);

CREATE INDEX IF NOT EXISTS idx_user_recent_model_user_lastused
    ON user_recent_model(user_id, last_used_at DESC);

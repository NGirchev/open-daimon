-- Create Telegram user table
CREATE TABLE IF NOT EXISTS telegram_user (
    id BIGINT PRIMARY KEY REFERENCES "user"(id),
    telegram_id BIGINT UNIQUE NOT NULL
);

-- Create Telegram user session table
CREATE TABLE IF NOT EXISTS telegram_user_session (
    id BIGSERIAL PRIMARY KEY,
    telegram_id BIGINT NOT NULL REFERENCES telegram_user(id) ON DELETE CASCADE,
    session_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    expired_at TIMESTAMP WITH TIME ZONE,
    is_active BOOLEAN DEFAULT TRUE,
    bot_status VARCHAR(50),
    UNIQUE (telegram_id, session_id)
);

-- Create Telegram whitelist table
CREATE TABLE IF NOT EXISTS telegram_whitelist (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for Telegram module
CREATE INDEX IF NOT EXISTS idx_telegram_user_telegram_id ON telegram_user(telegram_id);
CREATE INDEX IF NOT EXISTS idx_user_session_user_id ON telegram_user_session(telegram_id);
CREATE INDEX IF NOT EXISTS idx_telegram_whitelist_user_id ON telegram_whitelist(user_id);


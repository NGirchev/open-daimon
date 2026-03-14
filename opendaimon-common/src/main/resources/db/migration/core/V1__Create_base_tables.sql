-- =====================================================
-- Create base user table
-- =====================================================
CREATE TABLE IF NOT EXISTS "user" (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(255),
    first_name VARCHAR(255),
    last_name VARCHAR(255),
    language_code VARCHAR(10),
    phone VARCHAR(20),
    is_premium BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_activity_at TIMESTAMP WITH TIME ZONE NOT NULL,
    is_blocked BOOLEAN DEFAULT FALSE,
    user_type VARCHAR(31) NOT NULL DEFAULT 'User',
    api_key VARCHAR(255),
    current_assistant_role_id BIGINT
);

-- =====================================================
-- Create bugreport and feedback table
-- =====================================================
CREATE TABLE IF NOT EXISTS bugreport (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
    text TEXT NOT NULL,
    type VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for bugreport
CREATE INDEX IF NOT EXISTS idx_bugreport_user_id ON bugreport(user_id);
CREATE INDEX IF NOT EXISTS idx_bugreport_type ON bugreport(type);
CREATE INDEX IF NOT EXISTS idx_bugreport_created_at ON bugreport(created_at DESC);

-- =====================================================
-- Create assistant role table with versioning
-- =====================================================
CREATE TABLE IF NOT EXISTS assistant_role (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    content_hash VARCHAR(255) NOT NULL,
    version INTEGER NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMP WITH TIME ZONE,
    usage_count BIGINT NOT NULL DEFAULT 0,
    
    -- Unique version per user
    CONSTRAINT uk_assistant_role_user_version UNIQUE (user_id, version)
);

-- Indexes for assistant_role
CREATE INDEX IF NOT EXISTS idx_assistant_role_user_id ON assistant_role(user_id);
CREATE INDEX IF NOT EXISTS idx_assistant_role_user_active ON assistant_role(user_id, is_active);
CREATE INDEX IF NOT EXISTS idx_assistant_role_content_hash ON assistant_role(content_hash);
CREATE INDEX IF NOT EXISTS idx_assistant_role_last_used ON assistant_role(last_used_at) 
    WHERE is_active = false;
CREATE INDEX IF NOT EXISTS idx_assistant_role_usage_count ON assistant_role(usage_count) 
    WHERE is_active = false;

-- Partial unique index for user active role (one active role per user)
CREATE UNIQUE INDEX IF NOT EXISTS uk_assistant_role_user_active 
    ON assistant_role(user_id) 
    WHERE is_active = true;

-- Add foreign key for current_assistant_role_id on user
ALTER TABLE "user" 
    ADD CONSTRAINT fk_user_current_assistant_role 
    FOREIGN KEY (current_assistant_role_id) 
    REFERENCES assistant_role(id) 
    ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_user_current_assistant_role ON "user"(current_assistant_role_id);

-- =====================================================
-- Add unique constraints for username and phone
-- =====================================================
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_username ON "user"(username) WHERE username IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_phone ON "user"(phone) WHERE phone IS NOT NULL;

-- =====================================================
-- Create conversation_thread table
-- =====================================================
CREATE TABLE IF NOT EXISTS conversation_thread (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
    thread_key VARCHAR(255) NOT NULL UNIQUE,
    title VARCHAR(500),
    summary TEXT,
    memory_bullets JSONB DEFAULT '[]'::jsonb,
    total_messages INTEGER DEFAULT 0,
    total_tokens BIGINT DEFAULT 0,
    is_active BOOLEAN DEFAULT TRUE,
    last_activity_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    closed_at TIMESTAMP WITH TIME ZONE
);

-- Indexes for conversation_thread
CREATE INDEX IF NOT EXISTS idx_conversation_thread_user_id ON conversation_thread(user_id);
CREATE INDEX IF NOT EXISTS idx_conversation_thread_thread_key ON conversation_thread(thread_key);
CREATE INDEX IF NOT EXISTS idx_conversation_thread_is_active ON conversation_thread(is_active);
CREATE INDEX IF NOT EXISTS idx_conversation_thread_last_activity ON conversation_thread(last_activity_at);
CREATE INDEX IF NOT EXISTS idx_conversation_thread_user_active ON conversation_thread(user_id, is_active, last_activity_at);

-- =====================================================
-- Create message table (replaces user_request and service_response)
-- Aligns with Spring AI Message concept
-- =====================================================
CREATE TABLE IF NOT EXISTS message (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL CHECK (role IN ('USER', 'ASSISTANT', 'SYSTEM')),
    content TEXT NOT NULL,
    thread_id BIGINT REFERENCES conversation_thread(id) ON DELETE SET NULL,
    sequence_number INTEGER,
    token_count INTEGER,
    assistant_role_id BIGINT REFERENCES assistant_role(id) ON DELETE SET NULL,
    request_type VARCHAR(50),
    api_key VARCHAR(255),
    service_name VARCHAR(100),
    status VARCHAR(50) CHECK (status IN ('PENDING', 'SUCCESS', 'ERROR')),
    processing_time_ms INTEGER,
    error_message TEXT,
    response_data JSONB DEFAULT '{}'::jsonb,
    metadata JSONB DEFAULT '{}'::jsonb,
    message_type VARCHAR(20) DEFAULT 'MESSAGE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for message
CREATE INDEX IF NOT EXISTS idx_message_user_id ON message(user_id);
CREATE INDEX IF NOT EXISTS idx_message_thread_id ON message(thread_id);
CREATE INDEX IF NOT EXISTS idx_message_role ON message(role);
CREATE INDEX IF NOT EXISTS idx_message_sequence ON message(thread_id, sequence_number);
CREATE INDEX IF NOT EXISTS idx_message_created_at ON message(created_at);
CREATE INDEX IF NOT EXISTS idx_message_assistant_role ON message(assistant_role_id);
CREATE INDEX IF NOT EXISTS idx_message_type ON message(message_type);


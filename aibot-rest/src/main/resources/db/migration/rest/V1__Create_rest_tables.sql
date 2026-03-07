-- Create REST user table
CREATE TABLE IF NOT EXISTS rest_user (
    id BIGINT PRIMARY KEY REFERENCES "user"(id),
    email VARCHAR(255) UNIQUE NOT NULL
);

-- Indexes for REST module
CREATE INDEX IF NOT EXISTS idx_rest_user_email ON rest_user(email);


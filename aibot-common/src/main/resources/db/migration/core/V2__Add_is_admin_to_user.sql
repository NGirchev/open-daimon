-- =====================================================
-- Add is_admin column to user table
-- =====================================================
ALTER TABLE "user" 
    ADD COLUMN IF NOT EXISTS is_admin BOOLEAN DEFAULT FALSE NOT NULL;

-- Index for fast admin lookup
CREATE INDEX IF NOT EXISTS idx_user_is_admin ON "user"(is_admin) WHERE is_admin = TRUE;


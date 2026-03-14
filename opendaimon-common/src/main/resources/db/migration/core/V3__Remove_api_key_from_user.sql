-- =====================================================
-- Remove api_key column from user table
-- =====================================================
ALTER TABLE "user" 
    DROP COLUMN IF EXISTS api_key;


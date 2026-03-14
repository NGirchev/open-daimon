-- =====================================================
-- Remove api_key column from message table
-- =====================================================
ALTER TABLE message 
    DROP COLUMN IF EXISTS api_key;


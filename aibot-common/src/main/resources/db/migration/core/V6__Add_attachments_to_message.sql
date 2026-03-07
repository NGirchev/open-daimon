-- =====================================================
-- Add attachments column to message
-- Stores MinIO file references and expiry (TTL).
-- Format: array of { "storageKey", "expiresAt", "mimeType", "filename" }
-- =====================================================
ALTER TABLE message
ADD COLUMN IF NOT EXISTS attachments JSONB DEFAULT '[]'::jsonb;

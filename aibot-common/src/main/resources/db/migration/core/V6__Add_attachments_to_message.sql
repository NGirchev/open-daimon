-- =====================================================
-- Добавление колонки attachments в message
-- Хранит ссылки на файлы в MinIO и время истечения (TTL).
-- Формат: массив объектов { "storageKey", "expiresAt", "mimeType", "filename" }
-- =====================================================
ALTER TABLE message
ADD COLUMN IF NOT EXISTS attachments JSONB DEFAULT '[]'::jsonb;

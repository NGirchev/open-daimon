-- =====================================================
-- Add messages_at_last_summarization to conversation_thread
-- Tracks message count at last summarization
-- =====================================================
ALTER TABLE conversation_thread 
ADD COLUMN IF NOT EXISTS messages_at_last_summarization INTEGER;

-- For existing rows with summary set current message count
-- For rows without summary leave NULL
UPDATE conversation_thread 
SET messages_at_last_summarization = total_messages 
WHERE summary IS NOT NULL AND summary != '';

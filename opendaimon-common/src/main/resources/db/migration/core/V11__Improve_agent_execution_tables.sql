-- Fix timestamp columns to use timezone-aware type (consistent with rest of schema)
ALTER TABLE agent_execution
    ALTER COLUMN started_at TYPE TIMESTAMP WITH TIME ZONE,
    ALTER COLUMN finished_at TYPE TIMESTAMP WITH TIME ZONE;

ALTER TABLE agent_execution_step
    ALTER COLUMN started_at TYPE TIMESTAMP WITH TIME ZONE,
    ALTER COLUMN finished_at TYPE TIMESTAMP WITH TIME ZONE;

-- Add missing indexes for common query patterns
DROP INDEX IF EXISTS idx_agent_execution_conversation;
CREATE INDEX idx_agent_execution_conversation ON agent_execution(conversation_id) WHERE conversation_id IS NOT NULL;
CREATE INDEX idx_agent_execution_started_at ON agent_execution(started_at);
CREATE INDEX idx_agent_execution_step_step_id ON agent_execution_step(step_id);

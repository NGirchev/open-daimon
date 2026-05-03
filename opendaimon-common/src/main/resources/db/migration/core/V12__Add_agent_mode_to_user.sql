ALTER TABLE "user"
    ADD COLUMN IF NOT EXISTS agent_mode_enabled BOOLEAN;
